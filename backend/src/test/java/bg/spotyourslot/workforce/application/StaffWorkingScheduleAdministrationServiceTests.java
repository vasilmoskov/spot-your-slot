package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffMemberInactive;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffMemberNotFound;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleAdministrationDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

@ExtendWith(MockitoExtension.class)
class StaffWorkingScheduleAdministrationServiceTests {
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Sofia");
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Mock StaffMemberStore staffMembers;
    @Mock StaffWorkingScheduleService schedules;
    @Mock StaffWorkingScheduleInputValidator validator;
    @Mock BusinessScheduleContextAccess businesses;
    @Mock SelectedBusinessOwnerAccess owners;

    private StaffWorkingScheduleAdministrationService service;
    private AuthenticatedBusinessContext context;

    @BeforeEach
    void setUp() {
        service = new StaffWorkingScheduleAdministrationService(
                staffMembers, schedules, validator, businesses, owners);
        context = new TestContext(USER_ID, BUSINESS_ID);
    }

    @Test
    void requiresAuthenticationBeforeUsingAnyCollaborator() {
        assertThatThrownBy(() -> service.get(null, STAFF_MEMBER_ID))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("Authentication is required");
        assertThatThrownBy(() -> service.get(new TestContext(null, BUSINESS_ID), STAFF_MEMBER_ID))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("Authentication is required");
        verifyNoInteractions(staffMembers, schedules, validator, businesses, owners);
    }

    @Test
    void requiresSelectedBusinessBeforeAnyCollaborator() {
        assertThatThrownBy(() -> service.get(new TestContext(USER_ID, null), STAFF_MEMBER_ID))
                .isInstanceOf(SelectedBusinessRequired.class);
        assertThatThrownBy(() ->
                        service.replace(new TestContext(USER_ID, null), STAFF_MEMBER_ID, command()))
                .isInstanceOf(SelectedBusinessRequired.class);
        verifyNoInteractions(staffMembers, schedules, validator, businesses, owners);
    }

    @Test
    void readDeniesAccessWhenBusinessScheduleContextIsMissing() {
        when(businesses.findScheduleContext(BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(context, STAFF_MEMBER_ID))
                .isInstanceOf(BusinessAccessDenied.class)
                .hasMessage("Business access to working schedules is denied");
        verifyNoInteractions(owners, staffMembers, schedules, validator);
    }

    @Test
    void readDeniesAccessWhenOwnerIsNotAuthorized() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.DENIED);

        assertThatThrownBy(() -> service.get(context, STAFF_MEMBER_ID))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(staffMembers, schedules, validator);
    }

    @Test
    void readOrdersBusinessThenOwnerThenValidationThenStaffMemberThenSchedule() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.DRAFT)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(staffMembers.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(staffMemberRow(true)));
        when(schedules.find(BUSINESS_ID, STAFF_MEMBER_ID)).thenReturn(scheduleDetails());

        service.get(context, STAFF_MEMBER_ID);

        InOrder order = Mockito.inOrder(businesses, owners, validator, staffMembers, schedules);
        order.verify(businesses).findScheduleContext(BUSINESS_ID);
        order.verify(owners).authorize(USER_ID, BUSINESS_ID);
        order.verify(validator).validateStaffMemberId(STAFF_MEMBER_ID);
        order.verify(staffMembers).findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID);
        order.verify(schedules).find(BUSINESS_ID, STAFF_MEMBER_ID);
        verify(businesses, never()).lockScheduleContext(any());
        verify(owners, never()).lockAndAuthorize(any(), any());
    }

    @Test
    void readReturnsSafeNotFoundForMissingStaffMemberWithoutTouchingSchedule() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(staffMembers.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(context, STAFF_MEMBER_ID))
                .isInstanceOf(StaffMemberNotFound.class)
                .hasMessage("StaffMember was not found");
        verifyNoInteractions(schedules);
    }

    @Test
    void readReturnsInactiveStaffMemberScheduleWithLiveTimezone() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(staffMembers.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(staffMemberRow(false)));
        when(schedules.find(BUSINESS_ID, STAFF_MEMBER_ID)).thenReturn(scheduleDetails());

        StaffWorkingScheduleAdministrationDetails result = service.get(context, STAFF_MEMBER_ID);

        assertThat(result.staffMemberId()).isEqualTo(STAFF_MEMBER_ID);
        assertThat(result.timezone()).isEqualTo(TIMEZONE);
        assertThat(result.periods()).containsExactly(period());
        assertThat(result.version()).isEqualTo(3);
        assertThatThrownBy(() -> result.periods().add(period()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutationDeniesAccessWhenBusinessScheduleContextIsMissing() {
        when(businesses.lockScheduleContext(BUSINESS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(context, STAFF_MEMBER_ID, command()))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(owners, staffMembers, schedules, validator);
    }

    @Test
    void mutationDeniesAccessWhenOwnerIsNotAuthorized() {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.DENIED);

        assertThatThrownBy(() -> service.replace(context, STAFF_MEMBER_ID, command()))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(staffMembers, schedules, validator);
    }

    @Test
    void mutationRejectsSuspendedBusinessBeforeStaffMemberOrScheduleAccess() {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.SUSPENDED)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);

        assertThatThrownBy(() -> service.replace(context, STAFF_MEMBER_ID, command()))
                .isInstanceOf(BusinessSuspended.class)
                .hasMessage("Suspended Business cannot mutate working schedules");
        verifyNoInteractions(staffMembers, schedules, validator);
    }

    @Test
    void mutationOrdersBusinessThenMembershipThenStaffMemberThenSchedule() {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(staffMembers.lockActiveState(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(staffMemberRow(true)));
        when(schedules.replace(BUSINESS_ID, STAFF_MEMBER_ID, command()))
                .thenReturn(scheduleDetails());

        service.replace(context, STAFF_MEMBER_ID, command());

        InOrder order = Mockito.inOrder(businesses, owners, validator, staffMembers, schedules);
        order.verify(businesses).lockScheduleContext(BUSINESS_ID);
        order.verify(owners).lockAndAuthorize(USER_ID, BUSINESS_ID);
        order.verify(validator).validateStaffMemberId(STAFF_MEMBER_ID);
        order.verify(staffMembers).lockActiveState(BUSINESS_ID, STAFF_MEMBER_ID);
        order.verify(schedules).replace(BUSINESS_ID, STAFF_MEMBER_ID, command());
        verify(businesses, never()).findScheduleContext(any());
        verify(owners, never()).authorize(any(), any());
    }

    @Test
    void mutationReturnsSafeNotFoundForMissingOrCrossBusinessStaffMemberWithoutSchedule() {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(staffMembers.lockActiveState(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(context, STAFF_MEMBER_ID, command()))
                .isInstanceOf(StaffMemberNotFound.class)
                .hasMessage("StaffMember was not found");
        verifyNoInteractions(schedules);
    }

    @Test
    void mutationRejectsInactiveStaffMemberWithoutTouchingSchedule() {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(staffMembers.lockActiveState(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(staffMemberRow(false)));

        assertThatThrownBy(() -> service.replace(context, STAFF_MEMBER_ID, command()))
                .isInstanceOf(StaffMemberInactive.class)
                .hasMessage("Inactive StaffMember cannot receive a schedule replacement");
        verifyNoInteractions(schedules);
    }

    @Test
    void mutationReturnsReplacedScheduleWithLiveTimezoneOnActiveStaffMember() {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.DRAFT)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(staffMembers.lockActiveState(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(staffMemberRow(true)));
        when(schedules.replace(BUSINESS_ID, STAFF_MEMBER_ID, command()))
                .thenReturn(scheduleDetails());

        StaffWorkingScheduleAdministrationDetails result =
                service.replace(context, STAFF_MEMBER_ID, command());

        assertThat(result.staffMemberId()).isEqualTo(STAFF_MEMBER_ID);
        assertThat(result.timezone()).isEqualTo(TIMEZONE);
        assertThat(result.periods()).containsExactly(period());
        assertThat(result.version()).isEqualTo(3);
    }

    private BusinessScheduleContext scheduleContext(LifecycleStatus status) {
        return new BusinessScheduleContext(BUSINESS_ID, status, TIMEZONE);
    }

    private StaffMemberRow staffMemberRow(boolean active) {
        return new StaffMemberRow(
                STAFF_MEMBER_ID,
                BUSINESS_ID,
                "Анна Иванова",
                "member@example.invalid",
                "+359 888 123 456",
                active,
                1,
                NOW,
                NOW);
    }

    private WorkingPeriod period() {
        return new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    private StaffWorkingScheduleDetails scheduleDetails() {
        return new StaffWorkingScheduleDetails(
                STAFF_MEMBER_ID, List.of(period()), 3, NOW, NOW);
    }

    private ReplaceWorkingPeriodsCommand command() {
        return new ReplaceWorkingPeriodsCommand(List.of(period()), 2L);
    }

    private record TestContext(UUID userId, UUID businessId)
            implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.ofNullable(businessId);
        }
    }
}
