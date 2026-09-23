package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.catalog.ServiceReferenceAccess;
import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReference;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ServiceInactive;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ServiceNotFound;
import bg.spotyourslot.workforce.StaffMemberApplicationException.StaffMemberNotFound;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.application.StaffMemberInputValidator.PageInput;
import bg.spotyourslot.workforce.infrastructure.NewStaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.NewStaffWorkingScheduleRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import bg.spotyourslot.workforce.infrastructure.StaffMemberProfileUpdateRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingScheduleStore;
import java.time.Clock;
import java.time.Instant;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

@ExtendWith(MockitoExtension.class)
class StaffMemberAdministrationServiceTests {
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Mock StaffMemberStore store;
    @Mock StaffWorkingScheduleStore scheduleStore;
    @Mock StaffMemberInputValidator validator;
    @Mock BusinessLifecycleAccess businesses;
    @Mock SelectedBusinessOwnerAccess owners;
    @Mock ServiceReferenceAccess serviceReferences;

    private StaffMemberAdministrationService service;
    private AuthenticatedBusinessContext context;

    @BeforeEach
    void setUp() {
        service = new StaffMemberAdministrationService(
                store,
                scheduleStore,
                validator,
                businesses,
                owners,
                serviceReferences,
                Clock.fixed(NOW, ZoneOffset.UTC));
        context = new TestContext(USER_ID, BUSINESS_ID);
    }

    @Test
    void requiresAuthenticationBeforeUsingCollaborators() {
        assertThatThrownBy(() -> service.list(null, 0, 20))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("Authentication is required");
        assertThatThrownBy(() -> service.list(new TestContext(null, BUSINESS_ID), 0, 20))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("Authentication is required");
        verifyNoInteractions(store, scheduleStore, validator, businesses, owners);
    }

    @Test
    void distinguishesMissingSelectionLifecycleAndOwnerAccess() {
        assertThatThrownBy(() -> service.list(new TestContext(USER_ID, null), 0, 20))
                .isInstanceOf(SelectedBusinessRequired.class);

        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(businesses.findLifecycle(BUSINESS_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(context, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class)
                .hasMessage("Business access to StaffMembers is denied");
        verifyNoInteractions(owners);

        when(businesses.findLifecycle(BUSINESS_ID))
                .thenReturn(Optional.of(lifecycle(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.DENIED);
        assertThatThrownBy(() -> service.list(context, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(store);
    }

    @Test
    void mapsRowsToDeterministicSafeDefensivePage() {
        StaffMemberRow first = row(STAFF_MEMBER_ID, true, 2, "Анна Иванова");
        StaffMemberRow second = row(UUID.randomUUID(), false, 4, "Борис Петров");
        authorizeRead(LifecycleStatus.SUSPENDED);
        when(validator.validatePage(1, 2)).thenReturn(new PageInput(1, 2));
        when(store.list(BUSINESS_ID, 1, 2)).thenReturn(List.of(first, second));
        when(store.count(BUSINESS_ID)).thenReturn(5L);

        var result = service.list(context, 1, 2);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.staffMembers()).containsExactly(details(first), details(second));
        assertThatThrownBy(() -> result.staffMembers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(StaffMemberDetails.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("businessId", "membershipId", "userId");
        verify(businesses, never()).lockLifecycle(any());
        verify(owners, never()).lockAndAuthorize(any(), any());
    }

    @Test
    void returnsTenantSafeNotFoundForMissingDetail() {
        authorizeRead(LifecycleStatus.DRAFT);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(context, STAFF_MEMBER_ID))
                .isInstanceOf(StaffMemberNotFound.class)
                .hasMessage("StaffMember was not found");
    }

    @Test
    void createsFromCanonicalValuesWithGeneratedIdAndOneClockInstant() {
        CreateStaffMemberCommand raw = new CreateStaffMemberCommand(
                " Raw ", "PRIVATE@EXAMPLE.INVALID", " +359 123 ");
        CreateStaffMemberCommand validated = new CreateStaffMemberCommand(
                "Canonical", "private@example.invalid", "+359 123");
        StaffMemberRow stored = row(STAFF_MEMBER_ID, true, 0, "Canonical");
        authorizeMutation(LifecycleStatus.DRAFT);
        when(validator.validateCreate(raw)).thenReturn(validated);
        when(store.create(any())).thenReturn(stored);

        assertThat(service.create(context, raw)).isEqualTo(details(stored));

        var captor = ArgumentCaptor.forClass(NewStaffMemberRow.class);
        verify(store).create(captor.capture());
        assertThat(captor.getValue().id()).isNotNull();
        assertThat(captor.getValue().businessId()).isEqualTo(BUSINESS_ID);
        assertThat(captor.getValue().displayName()).isEqualTo("Canonical");
        assertThat(captor.getValue().contactEmail()).isEqualTo("private@example.invalid");
        assertThat(captor.getValue().contactPhone()).isEqualTo("+359 123");
        assertThat(captor.getValue().createdAt()).isEqualTo(NOW);

        var scheduleCaptor = ArgumentCaptor.forClass(NewStaffWorkingScheduleRow.class);
        verify(scheduleStore).create(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().businessId()).isEqualTo(BUSINESS_ID);
        assertThat(scheduleCaptor.getValue().staffMemberId()).isEqualTo(stored.id());
        assertThat(scheduleCaptor.getValue().createdAt()).isEqualTo(NOW);
        verifyMutationAuthorizationOrder();
    }

    @Test
    void authorizesMutationBeforePrivateValidationAndRejectsSuspendedBusiness() {
        CreateStaffMemberCommand privateCommand = new CreateStaffMemberCommand(
                "Private Name", "private@example.invalid", "+359123456");
        authorizeMutation(LifecycleStatus.SUSPENDED);

        assertThatThrownBy(() -> service.create(context, privateCommand))
                .isInstanceOf(BusinessSuspended.class)
                .hasMessage("Suspended Business cannot mutate StaffMembers");
        verifyMutationAuthorizationOrder();
        verify(validator, never()).validateCreate(any());
        verifyNoInteractions(store, scheduleStore);
    }

    @Test
    void deniedMutationDoesNotValidatePrivateCommandOrGeneratePersistentInput() {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(businesses.lockLifecycle(BUSINESS_ID))
                .thenReturn(Optional.of(lifecycle(LifecycleStatus.ACTIVE)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.DENIED);

        assertThatThrownBy(() -> service.create(context, new CreateStaffMemberCommand(
                        "Private Name", "private@example.invalid", "+359123456")))
                .isInstanceOf(BusinessAccessDenied.class);
        verify(validator, never()).validateCreate(any());
        verifyNoInteractions(store, scheduleStore);
    }

    @Test
    void updatesActiveAndInactiveStaffMembersWithCanonicalProfile() {
        for (boolean active : List.of(true, false)) {
            StaffMemberRow current = row(STAFF_MEMBER_ID, active, 3, "Current");
            StaffMemberRow stored = new StaffMemberRow(
                    STAFF_MEMBER_ID,
                    BUSINESS_ID,
                    "Updated",
                    "updated@example.invalid",
                    "+359 888 123",
                    active,
                    4,
                    current.createdAt(),
                    NOW);
            UpdateStaffMemberCommand raw = new UpdateStaffMemberCommand(
                    " Raw ", "UPDATED@EXAMPLE.INVALID", " +359 888 123 ", 3L);
            UpdateStaffMemberCommand validated = new UpdateStaffMemberCommand(
                    "Updated", "updated@example.invalid", "+359 888 123", 3L);
            authorizeMutation(LifecycleStatus.ACTIVE);
            when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
            when(validator.validateUpdate(raw)).thenReturn(validated);
            when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                    .thenReturn(Optional.of(current));
            when(store.updateProfile(eq(BUSINESS_ID), eq(STAFF_MEMBER_ID), any()))
                    .thenReturn(Optional.of(stored));

            assertThat(service.update(context, STAFF_MEMBER_ID, raw))
                    .isEqualTo(details(stored));

            var captor = ArgumentCaptor.forClass(StaffMemberProfileUpdateRow.class);
            verify(store).updateProfile(eq(BUSINESS_ID), eq(STAFF_MEMBER_ID), captor.capture());
            assertThat(captor.getValue()).isEqualTo(new StaffMemberProfileUpdateRow(
                    "Updated", "updated@example.invalid", "+359 888 123", 3, NOW));
            org.mockito.Mockito.reset(store, validator, businesses, owners);
        }
    }

    @Test
    void classifiesMissingStaleAndFinalProfileMisses() {
        UpdateStaffMemberCommand command = new UpdateStaffMemberCommand(
                "Updated", null, null, 3L);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(validator.validateUpdate(command)).thenReturn(command);
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(context, STAFF_MEMBER_ID, command))
                .isInstanceOf(StaffMemberNotFound.class);

        StaffMemberRow current = row(STAFF_MEMBER_ID, true, 4, "Current");
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.update(context, STAFF_MEMBER_ID, command))
                .isInstanceOf(ConcurrentUpdate.class)
                .hasMessage("StaffMember was changed by another operation");
        verify(store, never()).updateProfile(any(), any(), any());

        UpdateStaffMemberCommand currentCommand = new UpdateStaffMemberCommand(
                "Updated", null, null, 4L);
        when(validator.validateUpdate(currentCommand)).thenReturn(currentCommand);
        when(store.updateProfile(eq(BUSINESS_ID), eq(STAFF_MEMBER_ID), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(context, STAFF_MEMBER_ID, currentCommand))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void lifecycleChecksVersionBeforeStateAndClassifiesFinalMiss() {
        StaffMemberRow inactive = row(STAFF_MEMBER_ID, false, 5, "Inactive");
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(validator.validateVersion(any())).thenAnswer(invocation ->
                ((StaffMemberVersionCommand) invocation.getArgument(0)).expectedVersion());
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.deactivate(
                        context, STAFF_MEMBER_ID, new StaffMemberVersionCommand(4L)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> service.deactivate(
                        context, STAFF_MEMBER_ID, new StaffMemberVersionCommand(5L)))
                .isInstanceOf(InvalidLifecycleTransition.class)
                .hasMessage("StaffMember lifecycle transition is not allowed");
        verify(store, never()).deactivate(any(), any(), anyLong(), any());

        when(store.reactivate(BUSINESS_ID, STAFF_MEMBER_ID, 5, NOW))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reactivate(
                        context, STAFF_MEMBER_ID, new StaffMemberVersionCommand(5L)))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void preservesSanitizedUnexpectedPersistenceFailureAndCause() {
        CreateStaffMemberCommand command = new CreateStaffMemberCommand(
                "Failure", null, null);
        var cause = new IllegalStateException("internal diagnostic");
        var failure = new UnexpectedFailure(cause);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateCreate(command)).thenReturn(command);
        when(store.create(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.create(context, command))
                .isSameAs(failure)
                .hasMessage("StaffMember persistence operation failed")
                .hasCause(cause);
    }

    @Test
    void applicationExceptionsUseOnlyFixedSafeMessages() {
        assertThat(new BusinessAccessDenied()).hasMessage("Business access to StaffMembers is denied");
        assertThat(new StaffMemberNotFound()).hasMessage("StaffMember was not found");
        assertThat(new InvalidLifecycleTransition())
                .hasMessage("StaffMember lifecycle transition is not allowed");
        assertThat(new ConcurrentUpdate())
                .hasMessage("StaffMember was changed by another operation");
        assertThat(new BusinessSuspended())
                .hasMessage("Suspended Business cannot mutate StaffMembers");
        assertThat(new ServiceNotFound()).hasMessage("Service was not found");
        assertThat(new ServiceInactive())
                .hasMessage("Inactive Service cannot be assigned to StaffMember");
    }

    @Test
    void listsDeterministicSafeAssignmentSummaries() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        StaffMemberRow current = row(STAFF_MEMBER_ID, false, 3, "Inactive member");
        authorizeRead(LifecycleStatus.SUSPENDED);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(current));
        when(store.listAssignedServiceIds(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(List.of(second, first));
        when(serviceReferences.findReferences(BUSINESS_ID, List.of(second, first)))
                .thenReturn(List.of(
                        new ServiceReference(first, "А услуга", false),
                        new ServiceReference(second, "Б услуга", true)));

        var result = service.listServiceAssignments(context, STAFF_MEMBER_ID);

        assertThat(result.staffMemberId()).isEqualTo(STAFF_MEMBER_ID);
        assertThat(result.version()).isEqualTo(3);
        assertThat(result.createdAt()).isEqualTo(current.createdAt());
        assertThat(result.updatedAt()).isEqualTo(current.updatedAt());
        assertThat(result.services()).extracting(summary -> summary.id())
                .containsExactly(first, second);
    }

    @Test
    void replacesAssignmentsForInactiveStaffAndValidatesOnlyAdditions() {
        UUID retainedInactive = UUID.randomUUID();
        UUID removedInactive = UUID.randomUUID();
        UUID activeAddition = UUID.randomUUID();
        StaffMemberRow current = row(STAFF_MEMBER_ID, false, 3, "Inactive member");
        StaffMemberRow guarded = row(STAFF_MEMBER_ID, false, 4, "Inactive member");
        var command = new ReplaceServiceAssignmentsCommand(
                List.of(retainedInactive, activeAddition), 3L);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(validator.validateAssignments(command)).thenReturn(command);
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(current));
        when(store.advanceAssignmentVersion(BUSINESS_ID, STAFF_MEMBER_ID, 3, NOW))
                .thenReturn(Optional.of(guarded));
        when(store.listAssignedServiceIds(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(List.of(retainedInactive, removedInactive));
        when(serviceReferences.lockReferences(BUSINESS_ID, List.of(activeAddition)))
                .thenReturn(List.of(new ServiceReference(activeAddition, "Active", true)));
        when(serviceReferences.findReferences(
                        BUSINESS_ID, List.of(retainedInactive, activeAddition)))
                .thenReturn(List.of(
                        new ServiceReference(activeAddition, "Active", true),
                        new ServiceReference(retainedInactive, "Inactive", false)));

        var result = service.replaceServiceAssignments(
                context, STAFF_MEMBER_ID, command);

        assertThat(result.version()).isEqualTo(4);
        assertThat(result.services()).extracting(summary -> summary.id())
                .containsExactly(activeAddition, retainedInactive);
        verify(store).reconcileServiceAssignments(
                BUSINESS_ID,
                STAFF_MEMBER_ID,
                List.of(removedInactive),
                List.of(activeAddition));
        verifyMutationAuthorizationOrder();
    }

    @Test
    void sameSetReplacementStillAdvancesVersionWithoutServiceActivityCheck() {
        UUID inactive = UUID.randomUUID();
        StaffMemberRow current = row(STAFF_MEMBER_ID, true, 3, "Member");
        StaffMemberRow guarded = row(STAFF_MEMBER_ID, true, 4, "Member");
        var command = new ReplaceServiceAssignmentsCommand(List.of(inactive), 3L);
        authorizeMutation(LifecycleStatus.DRAFT);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(validator.validateAssignments(command)).thenReturn(command);
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(current));
        when(store.advanceAssignmentVersion(BUSINESS_ID, STAFF_MEMBER_ID, 3, NOW))
                .thenReturn(Optional.of(guarded));
        when(store.listAssignedServiceIds(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(List.of(inactive));
        when(serviceReferences.lockReferences(BUSINESS_ID, List.of()))
                .thenReturn(List.of());
        when(serviceReferences.findReferences(BUSINESS_ID, List.of(inactive)))
                .thenReturn(List.of(new ServiceReference(inactive, "Inactive", false)));

        assertThat(service.replaceServiceAssignments(context, STAFF_MEMBER_ID, command).version())
                .isEqualTo(4);
        verify(store).reconcileServiceAssignments(
                BUSINESS_ID, STAFF_MEMBER_ID, List.of(), List.of());
    }

    @Test
    void classifiesAssignmentMissingInactiveAndConcurrentOutcomes() {
        UUID serviceId = UUID.randomUUID();
        StaffMemberRow current = row(STAFF_MEMBER_ID, true, 3, "Member");
        StaffMemberRow guarded = row(STAFF_MEMBER_ID, true, 4, "Member");
        var command = new ReplaceServiceAssignmentsCommand(List.of(serviceId), 3L);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateStaffMemberId(STAFF_MEMBER_ID)).thenReturn(STAFF_MEMBER_ID);
        when(validator.validateAssignments(command)).thenReturn(command);
        when(store.findByBusinessIdAndId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(current));
        when(store.advanceAssignmentVersion(BUSINESS_ID, STAFF_MEMBER_ID, 3, NOW))
                .thenReturn(Optional.of(guarded));
        when(store.listAssignedServiceIds(BUSINESS_ID, STAFF_MEMBER_ID)).thenReturn(List.of());
        when(serviceReferences.lockReferences(BUSINESS_ID, List.of(serviceId)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.replaceServiceAssignments(
                        context, STAFF_MEMBER_ID, command))
                .isInstanceOf(ServiceNotFound.class);
        verify(store, never()).reconcileServiceAssignments(any(), any(), any(), any());

        when(serviceReferences.lockReferences(BUSINESS_ID, List.of(serviceId)))
                .thenReturn(List.of(new ServiceReference(serviceId, "Inactive", false)));
        assertThatThrownBy(() -> service.replaceServiceAssignments(
                        context, STAFF_MEMBER_ID, command))
                .isInstanceOf(ServiceInactive.class);

        when(store.advanceAssignmentVersion(BUSINESS_ID, STAFF_MEMBER_ID, 3, NOW))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replaceServiceAssignments(
                        context, STAFF_MEMBER_ID, command))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    private void authorizeRead(LifecycleStatus status) {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(businesses.findLifecycle(BUSINESS_ID))
                .thenReturn(Optional.of(lifecycle(status)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.GRANTED);
    }

    private void authorizeMutation(LifecycleStatus status) {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(businesses.lockLifecycle(BUSINESS_ID))
                .thenReturn(Optional.of(lifecycle(status)));
        when(owners.lockAndAuthorize(USER_ID, BUSINESS_ID))
                .thenReturn(Authorization.GRANTED);
    }

    private void verifyMutationAuthorizationOrder() {
        InOrder order = inOrder(businesses, owners);
        order.verify(businesses).lockLifecycle(BUSINESS_ID);
        order.verify(owners).lockAndAuthorize(USER_ID, BUSINESS_ID);
    }

    private BusinessLifecycle lifecycle(LifecycleStatus status) {
        return new BusinessLifecycle(BUSINESS_ID, status);
    }

    private StaffMemberRow row(UUID id, boolean active, long version, String displayName) {
        return new StaffMemberRow(
                id,
                BUSINESS_ID,
                displayName,
                "member@example.invalid",
                "+359 888 123 456",
                active,
                version,
                NOW.minusSeconds(60),
                NOW);
    }

    private StaffMemberDetails details(StaffMemberRow row) {
        return new StaffMemberDetails(
                row.id(),
                row.displayName(),
                row.contactEmail(),
                row.contactPhone(),
                row.active(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    private record TestContext(UUID userId, UUID businessId)
            implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.ofNullable(businessId);
        }
    }
}
