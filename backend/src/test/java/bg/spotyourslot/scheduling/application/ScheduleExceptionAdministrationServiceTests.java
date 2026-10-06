package bg.spotyourslot.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessAccessDenied;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessSuspended;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ConcurrentUpdate;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InputField;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.OverlapConflict;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ScheduleExceptionNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberInactive;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionAdministrationDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionWindow;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess.StaffMemberReference;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

@ExtendWith(MockitoExtension.class)
class ScheduleExceptionAdministrationServiceTests {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000a01");
    private static final UUID BUSINESS_ID = UUID.fromString("00000000-0000-0000-0000-000000000a02");
    private static final UUID STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000a03");
    private static final UUID EXCEPTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000a04");
    private static final ZoneId TIMEZONE = ZoneId.of("Europe/Sofia");
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);

    @Mock ScheduleExceptionStore store;
    @Mock BusinessScheduleContextAccess businesses;
    @Mock SelectedBusinessOwnerAccess owners;
    @Mock StaffMemberReferenceAccess staffMembers;
    @Mock ScheduleRevisionBump scheduleRevision;

    private ScheduleExceptionAdministrationService service;
    private AuthenticatedBusinessContext context;

    @BeforeEach
    void setUp() {
        service = new ScheduleExceptionAdministrationService(
                store,
                new ScheduleExceptionInputValidator(),
                businesses,
                owners,
                staffMembers,
                scheduleRevision,
                Clock.fixed(NOW, ZoneOffset.UTC));
        context = new TestContext(USER_ID, BUSINESS_ID);
    }

    @Test
    void requiresAuthenticationAndSelectedBusinessBeforeAnyCollaborator() {
        assertThatThrownBy(() -> service.get(null, EXCEPTION_ID))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.get(new TestContext(null, BUSINESS_ID), EXCEPTION_ID))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> service.create(new TestContext(USER_ID, null), closure()))
                .isInstanceOf(SelectedBusinessRequired.class);
        assertThatThrownBy(() -> service.delete(new TestContext(USER_ID, null), EXCEPTION_ID, 0L))
                .isInstanceOf(SelectedBusinessRequired.class);
        verifyNoInteractions(store, businesses, owners, staffMembers, scheduleRevision);
    }

    @Test
    void readsUseNonLockingAuthorizationAndDenyMissingBusinessOrOwner() {
        when(businesses.findScheduleContext(BUSINESS_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(context, EXCEPTION_ID))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(owners, store);

        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.DENIED);
        assertThatThrownBy(() -> service.list(context, DATE, DATE))
                .isInstanceOf(BusinessAccessDenied.class);
        verify(businesses, never()).lockScheduleContext(any());
        verify(owners, never()).lockAndAuthorize(any(), any());
        verifyNoInteractions(store);
    }

    @Test
    void suspendedBusinessCanReadButEveryMutationIsRejectedBeforeAnyOtherWork() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.SUSPENDED)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID))
                .thenReturn(Optional.of(stored(closureContent(), 0)));
        assertThat(service.get(context, EXCEPTION_ID).exception().id()).isEqualTo(EXCEPTION_ID);

        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.SUSPENDED)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);

        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(0L)))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, 0L))
                .isInstanceOf(BusinessSuspended.class);
        verify(store, never()).insert(any());
        verify(store, never()).replace(any(), any(), anyLong(), any(), any());
        verify(store, never()).delete(any(), any(), anyLong());
        verifyNoInteractions(staffMembers);
    }

    @Test
    void draftAndActiveBusinessesAllowMutations() {
        for (LifecycleStatus status : List.of(LifecycleStatus.DRAFT, LifecycleStatus.ACTIVE)) {
            authorizeMutation(status);
            Mockito.doAnswer(invocation -> {
                NewScheduleException input = invocation.getArgument(0);
                return new ScheduleException(
                        input.id(), input.businessId(), input.content(), 0, NOW, NOW);
            }).when(store).insert(any());

            ScheduleExceptionAdministrationDetails result = service.create(context, closure());

            assertThat(result.exception().version()).isZero();
            assertThat(result.timezone()).isEqualTo(TIMEZONE);
        }
    }

    @Test
    void mutationRequiresOwnerAuthorityAfterTheBusinessLock() {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.DENIED);

        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(store, staffMembers);
    }

    @Test
    void createLocksBusinessThenMembershipThenStaffMemberThenInsertsWithoutAnOverlapPreCheck() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(new StaffMemberReference(STAFF_MEMBER_ID, true)));
        when(store.insert(any())).thenAnswer(invocation -> {
            NewScheduleException input = invocation.getArgument(0);
            return new ScheduleException(input.id(), input.businessId(), input.content(), 0, NOW, NOW);
        });

        ScheduleExceptionAdministrationDetails result = service.create(context, timeOff());

        InOrder order = Mockito.inOrder(businesses, owners, staffMembers, scheduleRevision, store);
        order.verify(businesses).lockScheduleContext(BUSINESS_ID);
        order.verify(owners).lockAndAuthorize(USER_ID, BUSINESS_ID);
        order.verify(staffMembers).lockReference(BUSINESS_ID, STAFF_MEMBER_ID);
        order.verify(scheduleRevision).advance(BUSINESS_ID);
        order.verify(store).insert(any());
        verify(store, never()).findOverlapping(any(), any(), any());
        verify(store, never()).findOverlappingForStaff(any(), any(), any(), any());
        ArgumentCaptor<NewScheduleException> captured =
                ArgumentCaptor.forClass(NewScheduleException.class);
        verify(store).insert(captured.capture());
        assertThat(captured.getValue().businessId()).isEqualTo(BUSINESS_ID);
        assertThat(captured.getValue().createdAt()).isEqualTo(NOW);
        assertThat(result.exception().staffMemberId()).isEqualTo(STAFF_MEMBER_ID);
    }

    @Test
    void businessClosureCreationNeverLocksAStaffMember() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(store.insert(any())).thenAnswer(invocation -> {
            NewScheduleException input = invocation.getArgument(0);
            return new ScheduleException(input.id(), input.businessId(), input.content(), 0, NOW, NOW);
        });

        service.create(context, closure());

        verifyNoInteractions(staffMembers);
    }

    @Test
    void createRejectsMissingForeignAndInactiveStaffMembers() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(context, timeOff()))
                .isInstanceOf(StaffMemberNotFound.class);

        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(new StaffMemberReference(STAFF_MEMBER_ID, false)));
        assertThatThrownBy(() -> service.create(context, timeOff()))
                .isInstanceOf(StaffMemberInactive.class);
        verify(store, never()).insert(any());
    }

    @Test
    void createValidatesAfterAuthorizationAndBeforeLockingTheStaffMember() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        CreateScheduleExceptionCommand invalid = new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.STAFF_TIME_OFF, STAFF_MEMBER_ID, DATE, DATE, true, List.of(
                        new ExceptionPeriod(LocalTime.of(9, 0), LocalTime.of(10, 0))));

        assertThatThrownBy(() -> service.create(context, invalid))
                .isInstanceOfSatisfying(InvalidInput.class, failure ->
                        assertThat(failure.field()).isEqualTo(InputField.PERIODS));
        verifyNoInteractions(staffMembers);
        verify(store, never()).insert(any());
    }

    @Test
    void persistenceOverlapAndConcurrencyFailuresAreTranslatedAndOthersPropagate() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        var cause = new DataAccessResourceFailureException("sql", new SQLException("secret", "23P01"));
        Mockito.doThrow(new ScheduleExceptionPersistenceException.OverlapConflict(cause))
                .when(store).insert(any());
        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(OverlapConflict.class)
                .hasMessageNotContaining("secret");

        Mockito.doThrow(new ScheduleExceptionPersistenceException.ConcurrentWriteConflict(cause))
                .when(store).insert(any());
        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(ConcurrentUpdate.class)
                .hasMessageNotContaining("secret");

        Mockito.doThrow(new ScheduleExceptionPersistenceException.InvalidReference(cause))
                .when(store).insert(any());
        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(ScheduleExceptionPersistenceException.InvalidReference.class);
        Mockito.doThrow(new ScheduleExceptionPersistenceException.UnexpectedFailure(cause))
                .when(store).insert(any());
        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(ScheduleExceptionPersistenceException.UnexpectedFailure.class);
    }

    @Test
    void replaceReadsThenLocksTheStoredStaffMemberThenUsesStoredKindAndStaffMember() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        ScheduleExceptionContent stored = ScheduleExceptionContent.staffTimeOffDays(
                STAFF_MEMBER_ID, DATE, DATE);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID))
                .thenReturn(Optional.of(stored(stored, 4)));
        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(new StaffMemberReference(STAFF_MEMBER_ID, true)));
        when(store.replace(any(), any(), anyLong(), any(), any())).thenAnswer(invocation ->
                Optional.of(new ScheduleException(
                        EXCEPTION_ID, BUSINESS_ID, invocation.getArgument(3), 5, NOW, NOW)));

        ScheduleExceptionAdministrationDetails result = service.replace(
                context,
                EXCEPTION_ID,
                new ReplaceScheduleExceptionCommand(4L, DATE, DATE.plusDays(3), true, List.of()));

        InOrder order = Mockito.inOrder(businesses, owners, store, staffMembers, scheduleRevision);
        order.verify(businesses).lockScheduleContext(BUSINESS_ID);
        order.verify(owners).lockAndAuthorize(USER_ID, BUSINESS_ID);
        order.verify(store).findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID);
        order.verify(staffMembers).lockReference(BUSINESS_ID, STAFF_MEMBER_ID);
        order.verify(scheduleRevision).advance(BUSINESS_ID);
        order.verify(store).replace(
                any(), any(), anyLong(), any(), any());
        ArgumentCaptor<ScheduleExceptionContent> content =
                ArgumentCaptor.forClass(ScheduleExceptionContent.class);
        verify(store).replace(
                Mockito.eq(BUSINESS_ID), Mockito.eq(EXCEPTION_ID), Mockito.eq(4L),
                content.capture(), Mockito.eq(NOW));
        assertThat(content.getValue().kind()).isEqualTo(ScheduleExceptionKind.STAFF_TIME_OFF);
        assertThat(content.getValue().staffMemberId()).isEqualTo(STAFF_MEMBER_ID);
        assertThat(result.exception().kind()).isEqualTo(ScheduleExceptionKind.STAFF_TIME_OFF);
        assertThat(result.exception().staffMemberId()).isEqualTo(STAFF_MEMBER_ID);
        assertThat(result.exception().version()).isEqualTo(5);
    }

    @Test
    void replaceOfAClosureNeverLocksAStaffMember() {
        authorizeMutation(LifecycleStatus.DRAFT);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID))
                .thenReturn(Optional.of(stored(closureContent(), 0)));
        when(store.replace(any(), any(), anyLong(), any(), any())).thenAnswer(invocation ->
                Optional.of(new ScheduleException(
                        EXCEPTION_ID, BUSINESS_ID, invocation.getArgument(3), 1, NOW, NOW)));

        service.replace(context, EXCEPTION_ID, replacement(0L));

        verifyNoInteractions(staffMembers);
    }

    @Test
    void replaceAndDeleteReportNotFoundOnlyWhenTheInitialReadIsEmpty() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(0L)))
                .isInstanceOf(ScheduleExceptionNotFound.class);
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, 0L))
                .isInstanceOf(ScheduleExceptionNotFound.class);
        verify(store, never()).replace(any(), any(), anyLong(), any(), any());
        verify(store, never()).delete(any(), any(), anyLong());
    }

    @Test
    void getReportsNotFoundWhenTheTenantScopedReadIsEmpty() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(context, EXCEPTION_ID))
                .isInstanceOf(ScheduleExceptionNotFound.class);
    }

    @Test
    void failedConditionalMutationAfterASuccessfulReadIsAConcurrentUpdate() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID))
                .thenReturn(Optional.of(stored(closureContent(), 2)));
        when(store.replace(any(), any(), anyLong(), any(), any())).thenReturn(Optional.empty());
        when(store.delete(BUSINESS_ID, EXCEPTION_ID, 2L)).thenReturn(false);

        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(2L)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, 2L))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void deleteLocksTheStoredStaffMemberBeforeTheConditionalDeleteAndRejectsInactive() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID)).thenReturn(Optional.of(
                stored(ScheduleExceptionContent.staffTimeOffDays(STAFF_MEMBER_ID, DATE, DATE), 1)));
        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(new StaffMemberReference(STAFF_MEMBER_ID, false)));

        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, 1L))
                .isInstanceOf(StaffMemberInactive.class);
        verify(store, never()).delete(any(), any(), anyLong());

        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(new StaffMemberReference(STAFF_MEMBER_ID, true)));
        when(store.delete(BUSINESS_ID, EXCEPTION_ID, 1L)).thenReturn(true);
        service.delete(context, EXCEPTION_ID, 1L);
        InOrder order = Mockito.inOrder(staffMembers, scheduleRevision, store);
        order.verify(staffMembers, Mockito.atLeastOnce()).lockReference(BUSINESS_ID, STAFF_MEMBER_ID);
        order.verify(scheduleRevision).advance(BUSINESS_ID);
        order.verify(store).delete(BUSINESS_ID, EXCEPTION_ID, 1L);
    }

    @Test
    void replaceAppliesKindRulesToTheStoredKind() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID)).thenReturn(Optional.of(
                stored(ScheduleExceptionContent.workingDayOverride(STAFF_MEMBER_ID, DATE, List.of()), 0)));

        assertThatThrownBy(() -> service.replace(
                        context,
                        EXCEPTION_ID,
                        new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())))
                .isInstanceOfSatisfying(InvalidInput.class, failure ->
                        assertThat(failure.field()).isEqualTo(InputField.ALL_DAY));
        verifyNoInteractions(staffMembers);
        verify(store, never()).replace(any(), any(), anyLong(), any(), any());
    }

    @Test
    void replaceAndDeleteValidateInputAfterAuthorizationBeforeReading() {
        authorizeMutation(LifecycleStatus.ACTIVE);

        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(-1L)))
                .isInstanceOfSatisfying(InvalidInput.class, failure ->
                        assertThat(failure.field()).isEqualTo(InputField.EXPECTED_VERSION));
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, null))
                .isInstanceOfSatisfying(InvalidInput.class, failure ->
                        assertThat(failure.field()).isEqualTo(InputField.EXPECTED_VERSION));
        verifyNoInteractions(store, staffMembers);
    }

    @Test
    void listReturnsTheWindowTimezoneOnceAndPreservesStoreOrder() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        ScheduleException first = stored(closureContent(), 0);
        ScheduleException second = new ScheduleException(
                UUID.fromString("00000000-0000-0000-0000-000000000a05"),
                BUSINESS_ID,
                ScheduleExceptionContent.staffTimeOffPartial(
                        STAFF_MEMBER_ID,
                        DATE,
                        List.of(new LocalPeriod(LocalTime.of(9, 0), LocalTime.of(10, 0)))),
                2,
                NOW,
                NOW);
        when(store.findOverlapping(BUSINESS_ID, DATE, DATE.plusDays(1)))
                .thenReturn(List.of(first, second));

        ScheduleExceptionWindow window = service.list(context, DATE, DATE.plusDays(1));

        assertThat(window.timezone()).isEqualTo(TIMEZONE);
        assertThat(window.from()).isEqualTo(DATE);
        assertThat(window.exceptions()).extracting(item -> item.id())
                .containsExactly(EXCEPTION_ID, second.id());
        assertThat(window.exceptions().get(1).periods()).containsExactly(
                new ExceptionPeriod(LocalTime.of(9, 0), LocalTime.of(10, 0)));
    }

    @Test
    void listRejectsAnInvalidWindowWithoutReadingTheStore() {
        when(businesses.findScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);

        assertThatThrownBy(() -> service.list(context, DATE, DATE.plusDays(93)))
                .isInstanceOfSatisfying(InvalidInput.class, failure ->
                        assertThat(failure.field()).isEqualTo(InputField.WINDOW));
        verifyNoInteractions(store);
    }

    @Test
    void everyAcceptedMutationAdvancesTheRevisionExactlyOnceBeforeTheAggregateWrite() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(store.insert(any())).thenAnswer(invocation -> {
            NewScheduleException input = invocation.getArgument(0);
            return new ScheduleException(input.id(), input.businessId(), input.content(), 0, NOW, NOW);
        });
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID))
                .thenReturn(Optional.of(stored(closureContent(), 0)));
        when(store.replace(any(), any(), anyLong(), any(), any())).thenAnswer(invocation ->
                Optional.of(new ScheduleException(
                        EXCEPTION_ID, BUSINESS_ID, invocation.getArgument(3), 1, NOW, NOW)));
        when(store.delete(BUSINESS_ID, EXCEPTION_ID, 0L)).thenReturn(true);

        service.create(context, closure());
        service.replace(context, EXCEPTION_ID, replacement(0L));
        service.delete(context, EXCEPTION_ID, 0L);

        verify(scheduleRevision, Mockito.times(3)).advance(BUSINESS_ID);
        InOrder order = Mockito.inOrder(scheduleRevision, store);
        order.verify(scheduleRevision).advance(BUSINESS_ID);
        order.verify(store).insert(any());
        order.verify(scheduleRevision).advance(BUSINESS_ID);
        order.verify(store).replace(any(), any(), anyLong(), any(), any());
        order.verify(scheduleRevision).advance(BUSINESS_ID);
        order.verify(store).delete(BUSINESS_ID, EXCEPTION_ID, 0L);
    }

    @Test
    void rejectedMutationsNeverAdvanceTheRevision() {
        // Authorization and lifecycle rejections.
        when(businesses.lockScheduleContext(BUSINESS_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(BusinessAccessDenied.class);
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.SUSPENDED)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(BusinessSuspended.class);
        verifyNoInteractions(scheduleRevision);

        // Validation, missing exception, StaffMember and stale-version rejections.
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(LifecycleStatus.ACTIVE)));
        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(-1L)))
                .isInstanceOf(InvalidInput.class);
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, null))
                .isInstanceOf(InvalidInput.class);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(0L)))
                .isInstanceOf(ScheduleExceptionNotFound.class);
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, 0L))
                .isInstanceOf(ScheduleExceptionNotFound.class);
        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(context, timeOff()))
                .isInstanceOf(StaffMemberNotFound.class);
        when(staffMembers.lockReference(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(new StaffMemberReference(STAFF_MEMBER_ID, false)));
        assertThatThrownBy(() -> service.create(context, timeOff()))
                .isInstanceOf(StaffMemberInactive.class);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID))
                .thenReturn(Optional.of(stored(closureContent(), 5)));
        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(4L)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, 4L))
                .isInstanceOf(ConcurrentUpdate.class);

        verifyNoInteractions(scheduleRevision);
        verify(store, never()).insert(any());
        verify(store, never()).replace(any(), any(), anyLong(), any(), any());
        verify(store, never()).delete(any(), any(), anyLong());
    }

    @Test
    void aRevisionConcurrencyVictimIsTheExistingSanitizedConflictAndNothingIsWritten() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(store.findByBusinessIdAndId(BUSINESS_ID, EXCEPTION_ID))
                .thenReturn(Optional.of(stored(closureContent(), 0)));
        Mockito.doThrow(new ScheduleRevisionConcurrentConflict())
                .when(scheduleRevision).advance(BUSINESS_ID);

        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(ConcurrentUpdate.class)
                .hasNoCause();
        assertThatThrownBy(() -> service.replace(context, EXCEPTION_ID, replacement(0L)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> service.delete(context, EXCEPTION_ID, 0L))
                .isInstanceOf(ConcurrentUpdate.class);

        verify(store, never()).insert(any());
        verify(store, never()).replace(any(), any(), anyLong(), any(), any());
        verify(store, never()).delete(any(), any(), anyLong());
    }

    @Test
    void aRevisionFailureIsNotMaskedAndNothingIsWritten() {
        authorizeMutation(LifecycleStatus.ACTIVE);
        Mockito.doThrow(new ScheduleRevisionFailure())
                .when(scheduleRevision).advance(BUSINESS_ID);

        assertThatThrownBy(() -> service.create(context, closure()))
                .isInstanceOf(ScheduleRevisionFailure.class);
        verify(store, never()).insert(any());
    }

    private void authorizeMutation(LifecycleStatus status) {
        when(businesses.lockScheduleContext(BUSINESS_ID))
                .thenReturn(Optional.of(scheduleContext(status)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
    }

    private static BusinessScheduleContext scheduleContext(LifecycleStatus status) {
        return new BusinessScheduleContext(BUSINESS_ID, status, TIMEZONE);
    }

    private static CreateScheduleExceptionCommand closure() {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE.plusDays(1), true, List.of());
    }

    private static CreateScheduleExceptionCommand timeOff() {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.STAFF_TIME_OFF, STAFF_MEMBER_ID, DATE, DATE, true, List.of());
    }

    private static ReplaceScheduleExceptionCommand replacement(long expectedVersion) {
        return new ReplaceScheduleExceptionCommand(
                expectedVersion, DATE, DATE.plusDays(2), true, List.of());
    }

    private static ScheduleExceptionContent closureContent() {
        return ScheduleExceptionContent.businessClosureDays(DATE, DATE.plusDays(1));
    }

    private static ScheduleException stored(ScheduleExceptionContent content, long version) {
        return new ScheduleException(EXCEPTION_ID, BUSINESS_ID, content, version, NOW, NOW);
    }

    private record TestContext(UUID userId, UUID businessId)
            implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.ofNullable(businessId);
        }
    }
}
