package bg.spotyourslot.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessAccessDenied;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessSuspended;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConflictField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ContactConflict;
import bg.spotyourslot.customer.application.CustomerAdministrationException.CustomerNotFound;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InputField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InvalidInput;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CreateCustomerCommand;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerSearchCommand;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.UpdateCustomerCommand;
import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.CustomerSearchCriteria;
import bg.spotyourslot.customer.domain.CustomerSortField;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceFailures;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CustomerAdministrationServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-03T08:00:00Z");
    private static final String SENTINEL_NAME = "Sentinel Customer Name";
    private static final String SENTINEL_PHONE = "0895555777";
    private static final String SENTINEL_EMAIL = "sentinel@example.test";

    private final UUID userId = UUID.randomUUID();
    private final UUID businessId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID newId = UUID.randomUUID();

    private CustomerStore store;
    private BusinessLifecycleAccess businesses;
    private SelectedBusinessOwnerAccess owners;
    private CustomerAdministrationService service;

    @BeforeEach
    void setUp() {
        store = mock(CustomerStore.class);
        businesses = mock(BusinessLifecycleAccess.class);
        owners = mock(SelectedBusinessOwnerAccess.class);
        service = new CustomerAdministrationService(
                store,
                new CustomerInputValidator(),
                businesses,
                owners,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> newId);
        allow(LifecycleStatus.ACTIVE);
    }

    // ---- authorization and lock order ------------------------------------------------------

    @Test
    void readsUseNonLockingChecksAndNeverTheLockingOnes() {
        when(store.list(any(), any(), anyIntValue(), anyIntValue(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of());

        service.list(context(), null, null, null, null);

        verify(businesses).findLifecycle(businessId);
        verify(owners).authorize(userId, businessId);
        verify(businesses, never()).lockLifecycle(any());
        verify(owners, never()).lockAndAuthorize(any(), any());
    }

    @Test
    void mutationsLockTheBusinessThenTheMembershipThenWrite() {
        when(store.findHolders(any(), any(), any())).thenReturn(List.of());
        when(store.insert(any())).thenAnswer(call -> {
            var creation = (bg.spotyourslot.customer.domain.NewCustomer) call.getArgument(0);
            return new Customer(creation.id(), businessId, creation.profile(), 0,
                    creation.createdAt(), creation.createdAt());
        });

        service.create(context(), new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null));

        var order = inOrder(businesses, owners, store);
        order.verify(businesses).lockLifecycle(businessId);
        order.verify(owners).lockAndAuthorize(userId, businessId);
        order.verify(store).findHolders(any(), any(), any());
        order.verify(store).insert(any());
        verify(businesses, never()).findLifecycle(any());
    }

    @Test
    void aSuspendedBusinessRejectsCreateAndUpdateBeforeAnyCustomerAccess() {
        allow(LifecycleStatus.SUSPENDED);

        assertThatThrownBy(() -> service.create(
                        context(), new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null)))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> service.update(
                        context(), customerId,
                        new UpdateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null, 0L)))
                .isInstanceOf(BusinessSuspended.class);

        verifyNoInteractions(store);
    }

    @Test
    void aSuspendedBusinessStillAllowsReads() {
        allow(LifecycleStatus.SUSPENDED);
        when(store.findById(businessId, customerId)).thenReturn(Optional.of(customer(0)));

        assertThat(service.get(context(), customerId).id()).isEqualTo(customerId);
    }

    @Test
    void aMembershipThatIsNotGrantedIsDeniedBeforeAnyCustomerAccess() {
        when(owners.authorize(userId, businessId)).thenReturn(Authorization.DENIED);
        when(owners.lockAndAuthorize(userId, businessId)).thenReturn(Authorization.DENIED);

        assertThatThrownBy(() -> service.list(context(), null, null, null, null))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> service.search(context(), new CustomerSearchCommand("x", null, null, null, null)))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> service.get(context(), customerId)).isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> service.create(
                        context(), new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null)))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> service.update(
                        context(), customerId,
                        new UpdateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null, 0L)))
                .isInstanceOf(BusinessAccessDenied.class);

        verifyNoInteractions(store);
    }

    @Test
    void aMissingBusinessLifecycleIsDenied() {
        when(businesses.findLifecycle(businessId)).thenReturn(Optional.empty());
        when(businesses.lockLifecycle(businessId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(context(), customerId)).isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> service.create(
                        context(), new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null)))
                .isInstanceOf(BusinessAccessDenied.class);
        verifyNoInteractions(store);
    }

    @Test
    void anAbsentBusinessSelectionIsRequired() {
        AuthenticatedBusinessContext noSelection = new TestContext(userId, null);

        assertThatThrownBy(() -> service.list(noSelection, null, null, null, null))
                .isInstanceOf(SelectedBusinessRequired.class);
        assertThatThrownBy(() -> service.create(
                        noSelection, new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null)))
                .isInstanceOf(SelectedBusinessRequired.class);
        verifyNoInteractions(store, businesses, owners);
    }

    // ---- list and search ----------------------------------------------------------------------

    @Test
    void searchPassesDerivedCriteriaAndTheValidatedPagingToTheStore() {
        when(store.list(any(), any(), anyIntValue(), anyIntValue(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of());
        when(store.count(any(), any())).thenReturn(0L);

        service.search(context(), new CustomerSearchCommand("Анна", 2, 25, "email", "desc"));

        ArgumentCaptor<CustomerSearchCriteria> criteria = ArgumentCaptor.forClass(CustomerSearchCriteria.class);
        verify(store).list(
                org.mockito.ArgumentMatchers.eq(businessId), criteria.capture(), org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(25), org.mockito.ArgumentMatchers.eq(CustomerSortField.EMAIL),
                org.mockito.ArgumentMatchers.eq(false));
        assertThat(criteria.getValue().nameFragment()).isEqualTo("Анна");
        verify(store).count(businessId, criteria.getValue());
    }

    @Test
    void invalidPagingSortOrSearchNeverReachesTheStore() {
        assertInvalid(() -> service.list(context(), -1, 10, null, null), InputField.PAGE);
        assertInvalid(() -> service.list(context(), 0, 11, null, null), InputField.SIZE);
        assertInvalid(() -> service.list(context(), 0, 10, "id", null), InputField.SORT);
        assertInvalid(() -> service.list(context(), 0, 10, null, "up"), InputField.DIRECTION);
        assertInvalid(() -> service.search(context(),
                new CustomerSearchCommand("x".repeat(101), null, null, null, null)), InputField.SEARCH);
        assertInvalid(() -> service.search(context(), null), InputField.COMMAND);

        verifyNoInteractions(store);
    }

    // ---- create ---------------------------------------------------------------------------------

    @Test
    void createReportsEveryConflictingIdentifierTogetherAndWritesNothing() {
        when(store.findHolders(businessId, "+359895555777", "sentinel@example.test"))
                .thenReturn(List.of(
                        customer(UUID.randomUUID(), "+359895555777", null, 0),
                        customer(UUID.randomUUID(), null, "sentinel@example.test", 0)));

        assertThatThrownBy(() -> service.create(
                        context(), new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL)))
                .isInstanceOfSatisfying(ContactConflict.class, conflict -> assertThat(conflict.fields())
                        .containsExactlyInAnyOrder(ConflictField.PHONE, ConflictField.EMAIL));

        verify(store, never()).insert(any());
    }

    @Test
    void createRejectsAnExistingCustomerWithTheSameContactInsteadOfReusingIt() {
        when(store.findHolders(any(), any(), any()))
                .thenReturn(List.of(customer(UUID.randomUUID(), "+359895555777", "sentinel@example.test", 0)));

        assertThatThrownBy(() -> service.create(
                        context(), new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL)))
                .isInstanceOf(ContactConflict.class);
        verify(store, never()).insert(any());
    }

    @Test
    void createUsesTheGeneratedIdTheSelectedBusinessAndTheClock() {
        when(store.findHolders(any(), any(), any())).thenReturn(List.of());
        when(store.insert(any())).thenAnswer(call -> {
            var creation = (bg.spotyourslot.customer.domain.NewCustomer) call.getArgument(0);
            return new Customer(creation.id(), creation.businessId(), creation.profile(), 0,
                    creation.createdAt(), creation.createdAt());
        });

        var created = service.create(context(), new CreateCustomerCommand(" Анна  Иванова", SENTINEL_PHONE, null));

        assertThat(created.id()).isEqualTo(newId);
        assertThat(created.displayName()).isEqualTo("Анна Иванова");
        assertThat(created.phone()).isEqualTo("+359895555777");
        assertThat(created.createdAt()).isEqualTo(NOW);
        ArgumentCaptor<bg.spotyourslot.customer.domain.NewCustomer> inserted =
                ArgumentCaptor.forClass(bg.spotyourslot.customer.domain.NewCustomer.class);
        verify(store).insert(inserted.capture());
        assertThat(inserted.getValue().businessId()).isEqualTo(businessId);
    }

    @Test
    void invalidCreateInputIsReportedByFieldWithoutTouchingTheStore() {
        assertThatThrownBy(() -> service.create(context(), new CreateCustomerCommand(" ", null, null)))
                .isInstanceOfSatisfying(InvalidInput.class, invalid -> assertThat(invalid.fields())
                        .containsExactlyInAnyOrder(InputField.DISPLAY_NAME, InputField.CONTACT));
        verifyNoInteractions(store);
    }

    // ---- update ---------------------------------------------------------------------------------

    @Test
    void updateOfAMissingOrForeignCustomerIsNotFoundWithoutAWrite() {
        when(store.findById(businessId, customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                        context(), customerId, new UpdateCustomerCommand("Анна", SENTINEL_PHONE, null, 0L)))
                .isInstanceOf(CustomerNotFound.class);
        verify(store, never()).update(any(), any(), any(), anyLong(), any());
    }

    @Test
    void aStaleVersionIsAConcurrentUpdateWithoutAWrite() {
        when(store.findById(businessId, customerId)).thenReturn(Optional.of(customer(3)));

        assertThatThrownBy(() -> service.update(
                        context(), customerId, new UpdateCustomerCommand("Анна", SENTINEL_PHONE, null, 2L)))
                .isInstanceOf(ConcurrentUpdate.class);
        verify(store, never()).update(any(), any(), any(), anyLong(), any());
    }

    @Test
    void aLostRaceAtTheGuardedWriteIsAConcurrentUpdate() {
        when(store.findById(businessId, customerId)).thenReturn(Optional.of(customer(3)));
        when(store.findHolders(any(), any(), any())).thenReturn(List.of());
        when(store.update(any(), any(), any(), anyLong(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                        context(), customerId, new UpdateCustomerCommand("Анна", SENTINEL_PHONE, null, 3L)))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void anUpdateIgnoresTheCustomersOwnIdentifiersAndWritesWithTheClock() {
        Customer current = customer(customerId, "+359895555777", "sentinel@example.test", 4);
        when(store.findById(businessId, customerId)).thenReturn(Optional.of(current));
        when(store.findHolders(any(), any(), any())).thenReturn(List.of(current));
        Customer updated = new Customer(customerId, businessId,
                new CustomerProfile("Анна", "+359895555777", null), 5, NOW.minusSeconds(60), NOW);
        when(store.update(businessId, customerId,
                new CustomerProfile("Анна", "+359895555777", null), 4L, NOW))
                .thenReturn(Optional.of(updated));

        var result = service.update(
                context(), customerId, new UpdateCustomerCommand("Анна", SENTINEL_PHONE, "", 4L));

        assertThat(result.version()).isEqualTo(5);
        assertThat(result.email()).isNull();
    }

    @Test
    void anUpdateOntoAnotherCustomersIdentifierReportsThatFieldOnly() {
        when(store.findById(businessId, customerId)).thenReturn(Optional.of(customer(0)));
        when(store.findHolders(any(), any(), any()))
                .thenReturn(List.of(customer(UUID.randomUUID(), null, "sentinel@example.test", 0)));

        assertThatThrownBy(() -> service.update(context(), customerId,
                        new UpdateCustomerCommand("Анна", SENTINEL_PHONE, SENTINEL_EMAIL, 0L)))
                .isInstanceOfSatisfying(ContactConflict.class,
                        conflict -> assertThat(conflict.fields()).containsExactly(ConflictField.EMAIL));
        verify(store, never()).update(any(), any(), any(), anyLong(), any());
    }

    @Test
    void anUpdateRequiresAnExpectedVersion() {
        assertInvalid(() -> service.update(context(), customerId,
                new UpdateCustomerCommand("Анна", SENTINEL_PHONE, null, null)), InputField.EXPECTED_VERSION);
        verifyNoInteractions(store);
    }

    // ---- failure translation and hygiene --------------------------------------------------------

    @Test
    void uniqueRaceViolationsBecomeTheMatchingContactConflict() {
        when(store.findHolders(any(), any(), any())).thenReturn(List.of());
        doThrow(CustomerPersistenceFailures.duplicatePhone()).when(store).insert(any());
        assertThatThrownBy(() -> create())
                .isInstanceOfSatisfying(ContactConflict.class,
                        conflict -> assertThat(conflict.fields()).containsExactly(ConflictField.PHONE));

        doThrow(CustomerPersistenceFailures.duplicateEmail()).when(store).insert(any());
        assertThatThrownBy(() -> create())
                .isInstanceOfSatisfying(ContactConflict.class,
                        conflict -> assertThat(conflict.fields()).containsExactly(ConflictField.EMAIL));
    }

    @Test
    void serializationFailureAndDeadlockBecomeTheTypedConcurrentConflict() {
        when(store.findHolders(any(), any(), any())).thenReturn(List.of());
        for (String sqlState : new String[] {"40001", "40P01"}) {
            doThrow(CustomerPersistenceFailures.unexpected(sqlState)).when(store).insert(any());

            assertThatThrownBy(() -> create()).isExactlyInstanceOf(CustomerConcurrentConflict.class);
        }
    }

    @Test
    void everyOtherPersistenceFailureBecomesTheSanitizedOperationFailure() {
        when(store.findHolders(any(), any(), any())).thenReturn(List.of());
        for (RuntimeException failure : List.of(
                CustomerPersistenceFailures.unexpected(null),
                CustomerPersistenceFailures.unexpected("08006"),
                CustomerPersistenceFailures.unknownBusiness(),
                CustomerPersistenceFailures.invalidData())) {
            doThrow(failure).when(store).insert(any());

            assertThatThrownBy(() -> create())
                    .isExactlyInstanceOf(CustomerOperationFailure.class)
                    .hasNoCause()
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
        }
    }

    @Test
    void readFailuresAreTranslatedToo() {
        doThrow(CustomerPersistenceFailures.unexpected("40001")).when(store).findById(any(), any());
        assertThatThrownBy(() -> service.get(context(), customerId))
                .isExactlyInstanceOf(CustomerConcurrentConflict.class);

        doThrow(CustomerPersistenceFailures.unexpected("XX000")).when(store).findById(any(), any());
        assertThatThrownBy(() -> service.get(context(), customerId))
                .isExactlyInstanceOf(CustomerOperationFailure.class);
    }

    @Test
    void noExceptionCommandOrReadModelShowsASubmittedValue() {
        when(store.findHolders(any(), any(), any())).thenReturn(List.of(
                customer(UUID.randomUUID(), "+359895555777", "sentinel@example.test", 0)));
        var command = new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL);
        var update = new UpdateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, SENTINEL_EMAIL, 1L);
        var search = new CustomerSearchCommand(SENTINEL_NAME, null, null, null, null);

        RuntimeException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
                RuntimeException.class, () -> service.create(context(), command));

        for (String text : new String[] {
                thrown.toString(), String.valueOf(thrown.getMessage()), command.toString(),
                update.toString(), search.toString()}) {
            assertThat(text)
                    .doesNotContain("Sentinel")
                    .doesNotContain("sentinel")
                    .doesNotContain("0895")
                    .doesNotContain("359");
        }
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private void create() {
        service.create(context(), new CreateCustomerCommand(SENTINEL_NAME, SENTINEL_PHONE, null));
    }

    private void assertInvalid(Runnable action, InputField field) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(InvalidInput.class,
                        invalid -> assertThat(invalid.fields()).containsExactly(field));
    }

    private void allow(LifecycleStatus status) {
        BusinessLifecycle lifecycle = new BusinessLifecycle(businessId, status);
        when(businesses.findLifecycle(businessId)).thenReturn(Optional.of(lifecycle));
        when(businesses.lockLifecycle(businessId)).thenReturn(Optional.of(lifecycle));
        when(owners.authorize(userId, businessId)).thenReturn(Authorization.GRANTED);
        when(owners.lockAndAuthorize(userId, businessId)).thenReturn(Authorization.GRANTED);
    }

    private AuthenticatedBusinessContext context() {
        return new TestContext(userId, businessId);
    }

    private Customer customer(long version) {
        return customer(customerId, "+359895555777", null, version);
    }

    private Customer customer(UUID id, String phone, String email, long version) {
        return new Customer(id, businessId, new CustomerProfile("Анна Иванова", phone, email),
                version, NOW.minusSeconds(3600), NOW.minusSeconds(60));
    }

    private static int anyIntValue() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private record TestContext(UUID userId, UUID selected) implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.ofNullable(selected);
        }
    }
}
