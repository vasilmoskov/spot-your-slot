package bg.spotyourslot.catalog.application;

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
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessAccessDenied;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessSuspended;
import bg.spotyourslot.catalog.ServiceApplicationException.ConcurrentUpdate;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNameConflict;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNotFound;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.catalog.application.ServiceInputValidator.PageInput;
import bg.spotyourslot.catalog.infrastructure.NewServiceRow;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.NameConflict;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import bg.spotyourslot.catalog.infrastructure.ServiceRow;
import bg.spotyourslot.catalog.infrastructure.ServiceStore;
import bg.spotyourslot.catalog.infrastructure.ServiceUpdateRow;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.math.BigDecimal;
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
class ServiceAdministrationServiceTests {
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID SERVICE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

    @Mock ServiceStore store;
    @Mock ServiceInputValidator validator;
    @Mock BusinessLifecycleAccess businesses;
    @Mock SelectedBusinessOwnerAccess owners;

    private ServiceAdministrationService service;
    private AuthenticatedBusinessContext context;

    @BeforeEach
    void setUp() {
        service = new ServiceAdministrationService(
                store,
                validator,
                businesses,
                owners,
                Clock.fixed(NOW, ZoneOffset.UTC));
        context = new TestContext(USER_ID, BUSINESS_ID);
    }

    @Test
    void requiresAuthenticationBeforeUsingCollaborators() {
        assertThatThrownBy(() -> service.list(null, 0, 20))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class)
                .hasMessage("Authentication is required");
        verifyNoInteractions(store, validator, businesses, owners);
    }

    @Test
    void distinguishesMissingSelectionFromDeniedBusinessAccess() {
        var noSelection = new TestContext(USER_ID, null);

        assertThatThrownBy(() -> service.list(noSelection, 0, 20))
                .isInstanceOf(SelectedBusinessRequired.class);

        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(businesses.findLifecycle(BUSINESS_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(context, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(owners);
    }

    @Test
    void mapsEveryReadFieldWithoutBusinessIdentity() {
        ServiceRow row = row(true, 4, NOW.minusSeconds(60), NOW);
        authorizeRead();
        when(validator.validatePage(1, 10)).thenReturn(new PageInput(1, 10));
        when(store.list(BUSINESS_ID, 1, 10)).thenReturn(List.of(row));
        when(store.count(BUSINESS_ID)).thenReturn(11L);

        var result = service.list(context, 1, 10);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(11);
        assertThat(result.services()).singleElement().satisfies(details -> {
            assertThat(details.id()).isEqualTo(row.id());
            assertThat(details.name()).isEqualTo(row.name());
            assertThat(details.description()).isEqualTo(row.description());
            assertThat(details.durationMinutes()).isEqualTo(row.durationMinutes());
            assertThat(details.price()).isEqualByComparingTo(row.price());
            assertThat(details.active()).isEqualTo(row.active());
            assertThat(details.version()).isEqualTo(row.version());
            assertThat(details.createdAt()).isEqualTo(row.createdAt());
            assertThat(details.updatedAt()).isEqualTo(row.updatedAt());
        });
        verify(businesses, never()).lockLifecycle(any());
        verify(owners, never()).lockAndAuthorize(any(), any());
    }

    @Test
    void deniesReadWhenOwnerAccessIsNotGranted() {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(businesses.findLifecycle(BUSINESS_ID))
                .thenReturn(Optional.of(lifecycle(LifecycleStatus.ACTIVE)));
        when(owners.authorize(USER_ID, BUSINESS_ID)).thenReturn(Authorization.DENIED);

        assertThatThrownBy(() -> service.get(context, SERVICE_ID))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(store);
    }

    @Test
    void returnsSameNotFoundOutcomeForAnyMissingTenantScopedService() {
        authorizeRead();
        when(validator.validateServiceId(SERVICE_ID)).thenReturn(SERVICE_ID);
        when(store.findByBusinessIdAndId(BUSINESS_ID, SERVICE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(context, SERVICE_ID))
                .isInstanceOf(ServiceNotFound.class)
                .hasMessage("Service was not found");
    }

    @Test
    void createsFromCanonicalValuesWithSelectedBusinessAndOneTimestamp() {
        CreateServiceCommand raw = createCommand("  Raw  ");
        CreateServiceCommand validated = createCommand("Canonical");
        ServiceRow stored = row(true, 0, NOW, NOW);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateCreate(raw)).thenReturn(validated);
        when(store.create(any())).thenReturn(stored);

        var result = service.create(context, raw);

        var captor = ArgumentCaptor.forClass(NewServiceRow.class);
        verify(store).create(captor.capture());
        assertThat(captor.getValue().id()).isNotNull();
        assertThat(captor.getValue().businessId()).isEqualTo(BUSINESS_ID);
        assertThat(captor.getValue().name()).isEqualTo(validated.name());
        assertThat(captor.getValue().description()).isEqualTo(validated.description());
        assertThat(captor.getValue().durationMinutes()).isEqualTo(validated.durationMinutes());
        assertThat(captor.getValue().price()).isSameAs(validated.price());
        assertThat(captor.getValue().createdAt()).isEqualTo(NOW);
        assertThat(result.id()).isEqualTo(stored.id());
        verifyMutationAuthorizationOrder();
    }

    @Test
    void rejectsSuspendedMutationAfterBothLocksWithoutValidatingOrWriting() {
        authorizeMutation(LifecycleStatus.SUSPENDED);

        assertThatThrownBy(() -> service.create(context, createCommand("Valid")))
                .isInstanceOf(BusinessSuspended.class);

        verifyMutationAuthorizationOrder();
        verifyNoInteractions(store);
        verify(validator, never()).validateCreate(any());
    }

    @Test
    void updatesOnlyAfterTenantReadAndVersionCheck() {
        ServiceRow current = row(true, 3, NOW.minusSeconds(120), NOW.minusSeconds(60));
        ServiceRow stored = new ServiceRow(
                SERVICE_ID,
                BUSINESS_ID,
                "Updated",
                "Description",
                45,
                new BigDecimal("28.50"),
                true,
                4,
                current.createdAt(),
                NOW);
        UpdateServiceCommand raw = updateCommand(" Raw ", 3);
        UpdateServiceCommand validated = updateCommand("Updated", 3);
        authorizeMutation(LifecycleStatus.DRAFT);
        when(validator.validateServiceId(SERVICE_ID)).thenReturn(SERVICE_ID);
        when(validator.validateUpdate(raw)).thenReturn(validated);
        when(store.findByBusinessIdAndId(BUSINESS_ID, SERVICE_ID))
                .thenReturn(Optional.of(current));
        when(store.update(eq(BUSINESS_ID), eq(SERVICE_ID), any()))
                .thenReturn(Optional.of(stored));

        var result = service.update(context, SERVICE_ID, raw);

        var captor = ArgumentCaptor.forClass(ServiceUpdateRow.class);
        verify(store).update(eq(BUSINESS_ID), eq(SERVICE_ID), captor.capture());
        assertThat(captor.getValue().name()).isEqualTo(validated.name());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(3);
        assertThat(captor.getValue().updatedAt()).isEqualTo(NOW);
        assertThat(result).extracting("id", "active", "version", "createdAt", "updatedAt")
                .containsExactly(SERVICE_ID, true, 4L, current.createdAt(), NOW);
    }

    @Test
    void classifiesPreliminaryAndFinalVersionRacesAsConcurrentUpdates() {
        ServiceRow current = row(true, 4, NOW, NOW);
        UpdateServiceCommand staleCommand = updateCommand("Updated", 3);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateServiceId(SERVICE_ID)).thenReturn(SERVICE_ID);
        when(validator.validateUpdate(staleCommand)).thenReturn(staleCommand);
        when(store.findByBusinessIdAndId(BUSINESS_ID, SERVICE_ID))
                .thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.update(context, SERVICE_ID, staleCommand))
                .isInstanceOf(ConcurrentUpdate.class);
        verify(store, never()).update(any(), any(), any());

        UpdateServiceCommand currentCommand = updateCommand("Updated", 4);
        when(validator.validateUpdate(currentCommand)).thenReturn(currentCommand);
        when(store.update(eq(BUSINESS_ID), eq(SERVICE_ID), any()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(context, SERVICE_ID, currentCommand))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void lifecycleTransitionChecksVersionBeforeCurrentState() {
        ServiceRow inactive = row(false, 5, NOW, NOW);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateServiceId(SERVICE_ID)).thenReturn(SERVICE_ID);
        when(validator.validateVersion(any())).thenAnswer(invocation ->
                ((ServiceVersionCommand) invocation.getArgument(0)).expectedVersion());
        when(store.findByBusinessIdAndId(BUSINESS_ID, SERVICE_ID))
                .thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.deactivate(
                        context, SERVICE_ID, new ServiceVersionCommand(4L)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> service.deactivate(
                        context, SERVICE_ID, new ServiceVersionCommand(5L)))
                .isInstanceOf(InvalidLifecycleTransition.class);
        verify(store, never()).deactivate(any(), any(), anyLong(), any());
    }

    @Test
    void translatesOnlyTheKnownNameConflict() {
        CreateServiceCommand command = createCommand("Duplicate");
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateCreate(command)).thenReturn(command);
        when(store.create(any())).thenThrow(new NameConflict());

        assertThatThrownBy(() -> service.create(context, command))
                .isInstanceOf(ServiceNameConflict.class)
                .hasNoCause();
    }

    @Test
    void preservesUnexpectedPersistenceFailureAndCause() {
        CreateServiceCommand command = createCommand("Failure");
        var cause = new IllegalStateException("internal diagnostic");
        var failure = new UnexpectedFailure(cause);
        authorizeMutation(LifecycleStatus.ACTIVE);
        when(validator.validateCreate(command)).thenReturn(command);
        when(store.create(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.create(context, command))
                .isSameAs(failure)
                .hasCause(cause);
    }

    private void authorizeRead() {
        when(validator.validateBusinessId(BUSINESS_ID)).thenReturn(BUSINESS_ID);
        when(businesses.findLifecycle(BUSINESS_ID))
                .thenReturn(Optional.of(lifecycle(LifecycleStatus.ACTIVE)));
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

    private ServiceRow row(boolean active, long version, Instant createdAt, Instant updatedAt) {
        return new ServiceRow(
                SERVICE_ID,
                BUSINESS_ID,
                "Service",
                "Description",
                30,
                new BigDecimal("20.00"),
                active,
                version,
                createdAt,
                updatedAt);
    }

    private CreateServiceCommand createCommand(String name) {
        return new CreateServiceCommand(
                name, "Description", 30, new BigDecimal("20.00"));
    }

    private UpdateServiceCommand updateCommand(String name, long version) {
        return new UpdateServiceCommand(
                name, "Description", 45, new BigDecimal("28.50"), version);
    }

    private record TestContext(UUID userId, UUID businessId)
            implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.ofNullable(businessId);
        }
    }
}
