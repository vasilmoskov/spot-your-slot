package bg.spotyourslot.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerIdentity;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.CustomerMatchOutcome.CreatedCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.ExistingCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.IdentityConflict;
import bg.spotyourslot.customer.CustomerMatchOutcome.InvalidIdentity;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.CustomerReferenceAccess.CustomerReference;
import bg.spotyourslot.customer.IdentityField;
import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerField;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.NewCustomer;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceFailures;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import bg.spotyourslot.integration.MutableTestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Truth table, validation, bounds, and failure sanitizing of the Customer capability without a database. */
class CustomerIdentificationServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private static final String NAME = "Анна Иванова";
    private static final String PHONE = "+359895555777";
    private static final String EMAIL = "ime@primer.bg";
    private static final String OTHER_PHONE = "+359888123456";
    private static final String OTHER_EMAIL = "druga@primer.bg";

    private final UUID businessId = UUID.randomUUID();
    private final UUID generatedId = UUID.randomUUID();
    private final CustomerStore store = mock(CustomerStore.class);
    private final MutableTestClock clock = new MutableTestClock(NOW);
    private final AtomicInteger idRequests = new AtomicInteger();
    private CustomerIdentificationService service;

    @BeforeEach
    void setUp() {
        service = new CustomerIdentificationService(store, clock, () -> {
            idRequests.incrementAndGet();
            return generatedId;
        });
    }

    // ---- truth table: one identifier ----------------------------------------------------

    @Test
    void row3PhoneOnlyHeldByNobodyCreatesACustomerWithTheCanonicalProfile() {
        when(store.findHolders(businessId, PHONE, null)).thenReturn(List.of());
        when(store.insertIfAbsent(any())).thenAnswer(call -> Optional.of(persisted(call.getArgument(0))));

        CustomerMatchOutcome outcome = service.findOrCreate(
                businessId, new CustomerIdentity("  Анна   Иванова ", "0895 555 777", null));

        assertThat(outcome).isEqualTo(new CreatedCustomer(generatedId));
        ArgumentCaptor<NewCustomer> inserted = ArgumentCaptor.forClass(NewCustomer.class);
        verify(store).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue()).isEqualTo(new NewCustomer(
                generatedId, businessId, new CustomerProfile(NAME, PHONE, null), NOW));
        assertThat(clock.reads()).isEqualTo(1);
        assertThat(idRequests).hasValue(1);
        verify(store, times(1)).findHolders(any(), any(), any());
    }

    @Test
    void row4PhoneOnlyHeldByACustomerReturnsThatCustomerWithoutWriting() {
        UUID holder = UUID.randomUUID();
        when(store.findHolders(businessId, PHONE, null))
                .thenReturn(List.of(customer(holder, PHONE, OTHER_EMAIL)));

        assertThat(service.findOrCreate(businessId, identity(PHONE, null)))
                .isEqualTo(new ExistingCustomer(holder));

        assertNoCreationWork();
        verify(store, times(1)).findHolders(any(), any(), any());
        verifyNoMoreInteractions(store);
    }

    @Test
    void row5EmailOnlyHeldByNobodyCreatesACustomer() {
        when(store.findHolders(businessId, null, EMAIL)).thenReturn(List.of());
        when(store.insertIfAbsent(any())).thenAnswer(call -> Optional.of(persisted(call.getArgument(0))));

        assertThat(service.findOrCreate(businessId, new CustomerIdentity(NAME, null, " Ime@Primer.BG ")))
                .isEqualTo(new CreatedCustomer(generatedId));
    }

    @Test
    void row6EmailOnlyHeldByACustomerReturnsThatCustomerWithoutWriting() {
        UUID holder = UUID.randomUUID();
        when(store.findHolders(businessId, null, EMAIL))
                .thenReturn(List.of(customer(holder, OTHER_PHONE, EMAIL)));

        assertThat(service.findOrCreate(businessId, identity(null, "IME@primer.bg")))
                .isEqualTo(new ExistingCustomer(holder));

        assertNoCreationWork();
        verify(store, times(1)).findHolders(any(), any(), any());
        verifyNoMoreInteractions(store);
    }

    // ---- truth table: both identifiers --------------------------------------------------

    @Test
    void row7BothHeldByTheSameCustomerReturnsThatCustomer() {
        UUID holder = UUID.randomUUID();
        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of(customer(holder, PHONE, EMAIL)));

        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL)))
                .isEqualTo(new ExistingCustomer(holder));

        assertNoCreationWork();
        verify(store, times(1)).findHolders(any(), any(), any());
        verifyNoMoreInteractions(store);
    }

    @Test
    void row8PhoneHeldByAAndEmailHeldByBIsAConflictWhateverTheRowOrder() {
        Customer a = customer(UUID.randomUUID(), PHONE, null);
        Customer b = customer(UUID.randomUUID(), null, EMAIL);

        when(store.findHolders(businessId, PHONE, EMAIL)).thenReturn(List.of(a, b));
        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());

        when(store.findHolders(businessId, PHONE, EMAIL)).thenReturn(List.of(b, a));
        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());

        assertNoCreationWork();
        verify(store, never()).insertIfAbsent(any());
    }

    @Test
    void row9PhoneHeldAndEmailHeldByNobodyIsAConflictEvenIfTheHolderHasNoEmail() {
        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of(customer(UUID.randomUUID(), PHONE, null)));
        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());

        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of(customer(UUID.randomUUID(), PHONE, OTHER_EMAIL)));
        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());

        assertNoCreationWork();
        verify(store, never()).insertIfAbsent(any());
    }

    @Test
    void row10EmailHeldAndPhoneHeldByNobodyIsAConflictEvenIfTheHolderHasNoPhone() {
        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of(customer(UUID.randomUUID(), null, EMAIL)));
        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());

        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of(customer(UUID.randomUUID(), OTHER_PHONE, EMAIL)));
        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());

        assertNoCreationWork();
        verify(store, never()).insertIfAbsent(any());
    }

    @Test
    void row11BothHeldByNobodyCreatesACustomerWithBothIdentifiers() {
        when(store.findHolders(businessId, PHONE, EMAIL)).thenReturn(List.of());
        when(store.insertIfAbsent(any())).thenAnswer(call -> Optional.of(persisted(call.getArgument(0))));

        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL)))
                .isEqualTo(new CreatedCustomer(generatedId));

        ArgumentCaptor<NewCustomer> inserted = ArgumentCaptor.forClass(NewCustomer.class);
        verify(store).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().profile()).isEqualTo(new CustomerProfile(NAME, PHONE, EMAIL));
        verify(store, times(1)).findHolders(any(), any(), any());
    }

    @Test
    void theDisplayNameNeverInfluencesMatching() {
        UUID holder = UUID.randomUUID();
        when(store.findHolders(businessId, PHONE, null))
                .thenReturn(List.of(customer(holder, PHONE, null)));

        assertThat(service.findOrCreate(businessId, new CustomerIdentity("Съвсем друго име", PHONE, null)))
                .isEqualTo(new ExistingCustomer(holder));
    }

    // ---- races (rows 12 to 15) ----------------------------------------------------------

    @Test
    void row12AnInsertThatLostTheRaceReReadsOnceAndReturnsTheWinner() {
        UUID winner = UUID.randomUUID();
        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of())
                .thenReturn(List.of(customer(winner, PHONE, EMAIL)));
        when(store.insertIfAbsent(any())).thenReturn(Optional.empty());

        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL)))
                .isEqualTo(new ExistingCustomer(winner));

        verify(store, times(2)).findHolders(any(), any(), any());
        verify(store, times(1)).insertIfAbsent(any());
        verifyNoMoreInteractions(store);
        assertThat(clock.reads()).isEqualTo(1);
        assertThat(idRequests).hasValue(1);
    }

    @Test
    void row13APartialOverlapRevealedByTheReReadIsEvaluatedByTheTruthTable() {
        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of())
                .thenReturn(List.of(customer(UUID.randomUUID(), PHONE, null)));
        when(store.insertIfAbsent(any())).thenReturn(Optional.empty());

        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());
    }

    @Test
    void row14ARaceRevealingPhoneAAndEmailBIsAConflict() {
        when(store.findHolders(businessId, PHONE, EMAIL))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        customer(UUID.randomUUID(), PHONE, null), customer(UUID.randomUUID(), null, EMAIL)));
        when(store.insertIfAbsent(any())).thenReturn(Optional.empty());

        assertThat(service.findOrCreate(businessId, identity(PHONE, EMAIL))).isEqualTo(new IdentityConflict());
    }

    @Test
    void row15AReReadWithNoHolderIsAConcurrentConflictAfterExactlyThreeStatements() {
        when(store.findHolders(businessId, PHONE, null)).thenReturn(List.of());
        when(store.insertIfAbsent(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOrCreate(businessId, identity(PHONE, null)))
                .isInstanceOf(CustomerConcurrentConflict.class);

        verify(store, times(2)).findHolders(any(), any(), any());
        verify(store, times(1)).insertIfAbsent(any());
        verifyNoMoreInteractions(store);
        assertThat(clock.reads()).isEqualTo(1);
        assertThat(idRequests).hasValue(1);
    }

    @Test
    void anIdCollisionNeverBecomesASuccessfulMatch() {
        // The insert returns no row because the generated ID collided, and nobody holds the
        // identifiers: the call must not report Existing or Created.
        when(store.findHolders(businessId, null, EMAIL)).thenReturn(List.of());
        when(store.insertIfAbsent(any())).thenReturn(Optional.empty());

        assertThat(catchThrowable(() -> service.findOrCreate(businessId, identity(null, EMAIL))))
                .isInstanceOf(CustomerConcurrentConflict.class);
    }

    // ---- validation ---------------------------------------------------------------------

    @Test
    void invalidInputReturnsTheFieldsWithoutAnyStoreClockOrIdWork() {
        record Case(CustomerIdentity identity, Set<IdentityField> expected) { }
        List<Case> cases = new ArrayList<>();
        String longName = "а".repeat(201);
        for (String name : new String[] {null, "", "   ", longName, NAME}) {
            for (String phone : new String[] {null, "  ", "abc", "+359 88 123 456 ext 5", PHONE}) {
                for (String email : new String[] {null, "", "not-an-email", EMAIL}) {
                    Set<IdentityField> expected = EnumSet.noneOf(IdentityField.class);
                    if (!NAME.equals(name)) {
                        expected.add(IdentityField.DISPLAY_NAME);
                    }
                    boolean phoneSupplied = phone != null && !phone.isBlank();
                    boolean emailSupplied = email != null && !email.isBlank();
                    if (phoneSupplied && !PHONE.equals(phone)) {
                        expected.add(IdentityField.PHONE);
                    }
                    if (emailSupplied && !EMAIL.equals(email)) {
                        expected.add(IdentityField.EMAIL);
                    }
                    if (!phoneSupplied && !emailSupplied) {
                        expected.add(IdentityField.CONTACT);
                    }
                    if (!expected.isEmpty()) {
                        cases.add(new Case(new CustomerIdentity(name, phone, email), expected));
                    }
                }
            }
        }
        assertThat(cases).hasSizeGreaterThan(80);

        for (Case current : cases) {
            CustomerMatchOutcome outcome = service.findOrCreate(businessId, current.identity());

            assertThat(outcome).as("%s", current.expected()).isInstanceOf(InvalidIdentity.class);
            assertThat(((InvalidIdentity) outcome).fields()).isEqualTo(current.expected());
        }
        verifyNoInteractions(store);
        assertThat(clock.reads()).isZero();
        assertThat(idRequests).hasValue(0);
    }

    @Test
    void contactAccompaniesFieldErrorsOnlyWhenNeitherContactWasSupplied() {
        CustomerMatchOutcome bothInvalid = service.findOrCreate(
                businessId, new CustomerIdentity(NAME, "abc", "not-an-email"));
        CustomerMatchOutcome neither = service.findOrCreate(businessId, new CustomerIdentity(NAME, " ", null));

        assertThat(((InvalidIdentity) bothInvalid).fields())
                .containsExactlyInAnyOrder(IdentityField.PHONE, IdentityField.EMAIL);
        assertThat(((InvalidIdentity) neither).fields()).containsExactly(IdentityField.CONTACT);
    }

    @Test
    void invalidIdentityFieldsAreDeeplyImmutableAndNonempty() {
        Set<IdentityField> source = EnumSet.of(IdentityField.PHONE);
        InvalidIdentity invalid = new InvalidIdentity(source);
        source.add(IdentityField.EMAIL);

        assertThat(invalid.fields()).containsExactly(IdentityField.PHONE);
        assertThatThrownBy(() -> invalid.fields().add(IdentityField.EMAIL))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> invalid.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new InvalidIdentity(Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvalidIdentity(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFieldMappingIsExhaustiveAndOneToOne() {
        for (CustomerField field : CustomerField.values()) {
            assertThat(CustomerIdentificationService.fields(EnumSet.of(field)))
                    .containsExactly(IdentityField.valueOf(field.name()));
        }
        assertThat(IdentityField.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        java.util.Arrays.stream(CustomerField.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void canonicalizationHappensOnceBeforeTheLookup() {
        when(store.findHolders(businessId, PHONE, EMAIL)).thenReturn(List.of());
        when(store.insertIfAbsent(any())).thenAnswer(call -> Optional.of(persisted(call.getArgument(0))));

        service.findOrCreate(businessId, new CustomerIdentity(NAME, "00359 895 555 777", "  IME@Primer.BG"));

        verify(store).findHolders(businessId, PHONE, EMAIL);
    }

    // ---- failures -----------------------------------------------------------------------

    @Test
    void serializationFailuresAndDeadlocksBecomeAConcurrentConflict() {
        for (String sqlState : List.of("40001", "40P01")) {
            doThrow(CustomerPersistenceFailures.unexpected(sqlState)).when(store).findHolders(any(), any(), any());

            assertThat(catchThrowable(() -> service.findOrCreate(businessId, identity(PHONE, EMAIL))))
                    .isInstanceOf(CustomerConcurrentConflict.class);
        }
        doReturn(List.of()).when(store).findHolders(any(), any(), any());
        doThrow(CustomerPersistenceFailures.unexpected("40001")).when(store).insertIfAbsent(any());
        assertThat(catchThrowable(() -> service.findOrCreate(businessId, identity(PHONE, EMAIL))))
                .isInstanceOf(CustomerConcurrentConflict.class);
    }

    @Test
    void everyOtherPersistenceFailureBecomesAnOperationFailure() {
        List<CustomerPersistenceException> failures = List.of(
                CustomerPersistenceFailures.unexpected(null),
                CustomerPersistenceFailures.unexpected("08006"),
                CustomerPersistenceFailures.unexpected("57014"),
                CustomerPersistenceFailures.unknownBusiness(),
                CustomerPersistenceFailures.invalidData(),
                CustomerPersistenceFailures.duplicatePhone(),
                CustomerPersistenceFailures.duplicateEmail());
        for (CustomerPersistenceException failure : failures) {
            doReturn(List.of()).when(store).findHolders(any(), any(), any());
            doThrow(failure).when(store).insertIfAbsent(any());

            Throwable thrown = catchThrowable(() -> service.findOrCreate(businessId, identity(PHONE, EMAIL)));

            assertThat(thrown).isExactlyInstanceOf(CustomerOperationFailure.class);
        }
    }

    @Test
    void neitherPublishedExceptionRetainsOrExposesTheInternalFailure() {
        doThrow(CustomerPersistenceFailures.unexpected("40P01")).when(store).findHolders(any(), any(), any());
        Throwable conflict = catchThrowable(() -> service.findOrCreate(businessId, identity(PHONE, EMAIL)));
        doThrow(CustomerPersistenceFailures.unexpected("08006")).when(store).findHolders(any(), any(), any());
        Throwable failure = catchThrowable(() -> service.findOrCreate(businessId, identity(PHONE, EMAIL)));

        assertThat(conflict).isExactlyInstanceOf(CustomerConcurrentConflict.class)
                .hasMessage("Customer operation conflicted with a concurrent change");
        assertThat(failure).isExactlyInstanceOf(CustomerOperationFailure.class)
                .hasMessage("Customer operation failed");
        for (Throwable thrown : List.of(conflict, failure)) {
            assertThat(thrown.getCause()).isNull();
            assertThat(thrown.getSuppressed()).isEmpty();
            assertThat(thrown).isNotInstanceOf(CustomerPersistenceException.class);
            assertThat(thrown.getStackTrace()).isNotEmpty();
            assertThat(thrown.getStackTrace()[0].getClassName()).startsWith("bg.spotyourslot.customer");
            assertThat(thrown.getMessage()).doesNotContain("40P01", "08006", "SQL", "customer_", PHONE, EMAIL);
        }
    }

    @Test
    void aPersistenceFailureOfTheReferenceLookupIsSanitizedToo() {
        doThrow(CustomerPersistenceFailures.unexpected("40001")).when(store).findById(any(), any());
        assertThat(catchThrowable(() -> service.find(businessId, UUID.randomUUID())))
                .isInstanceOf(CustomerConcurrentConflict.class);

        doThrow(CustomerPersistenceFailures.unexpected("08006")).when(store).findById(any(), any());
        assertThat(catchThrowable(() -> service.find(businessId, UUID.randomUUID())))
                .isInstanceOf(CustomerOperationFailure.class);
    }

    // ---- reference lookup, privacy ------------------------------------------------------

    @Test
    void theReferenceExposesOnlyTheIdAndAMissingCustomerIsEmpty() {
        UUID id = UUID.randomUUID();
        when(store.findById(businessId, id)).thenReturn(Optional.of(customer(id, PHONE, EMAIL)));

        assertThat(service.find(businessId, id)).contains(new CustomerReference(id));
        assertThat(service.find(businessId, UUID.randomUUID())).isEmpty();
        assertThat(CustomerReference.class.getRecordComponents()).hasSize(1);
        verify(store, never()).insertIfAbsent(any());
        verify(store, never()).findHolders(any(), any(), any());
    }

    @Test
    void theIdentityAndEveryOutcomeAreFreeOfSubmittedValuesInTheirTextForms() {
        String sentinelName = "Sentinel Personal Name";
        String sentinelPhone = "+359888765432";
        String sentinelEmail = "sentinel.person@primer.bg";
        CustomerIdentity identity = new CustomerIdentity(sentinelName, sentinelPhone, sentinelEmail);

        assertThat(identity.toString()).doesNotContain(sentinelName, sentinelPhone, sentinelEmail);
        assertThat(identity.displayName()).isEqualTo(sentinelName);

        when(store.findHolders(any(), any(), any()))
                .thenReturn(List.of(customer(UUID.randomUUID(), sentinelPhone, null)));
        CustomerMatchOutcome conflict = service.findOrCreate(businessId, identity);
        CustomerMatchOutcome invalid = service.findOrCreate(
                businessId, new CustomerIdentity(sentinelName, "nonsense " + sentinelPhone, null));

        for (CustomerMatchOutcome outcome : List.of(conflict, invalid)) {
            assertThat(outcome.toString()).doesNotContain(sentinelName, sentinelPhone, sentinelEmail);
        }
        assertThat(conflict).isEqualTo(new IdentityConflict());
        assertThat(IdentityConflict.class.getRecordComponents()).isEmpty();
    }

    @Test
    void successfulOutcomesExposeOnlyTheCustomerId() {
        assertThat(ExistingCustomer.class.getRecordComponents()).extracting("name").containsExactly("customerId");
        assertThat(CreatedCustomer.class.getRecordComponents()).extracting("name").containsExactly("customerId");
        assertThat(InvalidIdentity.class.getRecordComponents()).extracting("name").containsExactly("fields");
    }

    @Test
    void publishedIdRecordsRejectANullIdAndKeepAValidOneUnchanged() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new ExistingCustomer(null))
                .isInstanceOf(NullPointerException.class).hasMessage("customerId");
        assertThatThrownBy(() -> new CreatedCustomer(null))
                .isInstanceOf(NullPointerException.class).hasMessage("customerId");
        assertThatThrownBy(() -> new CustomerReference(null))
                .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThat(new ExistingCustomer(id).customerId()).isSameAs(id);
        assertThat(new CreatedCustomer(id).customerId()).isSameAs(id);
        assertThat(new CustomerReference(id).id()).isSameAs(id);
    }

    @Test
    void theIdentityStillAllowsAnAbsentPhoneOrEmail() {
        assertThat(new CustomerIdentity(NAME, null, EMAIL).phone()).isNull();
        assertThat(new CustomerIdentity(NAME, PHONE, null).email()).isNull();
    }

    @Test
    void theCapabilityNeverUsesTheOtherBusinessOrAMissingArgument() {
        assertThatThrownBy(() -> service.findOrCreate(null, identity(PHONE, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.findOrCreate(businessId, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(store);
        assertThat(clock.reads()).isZero();
    }

    // ---- helpers ------------------------------------------------------------------------

    private CustomerIdentity identity(String phone, String email) {
        return new CustomerIdentity(NAME, phone, email);
    }

    private Customer customer(UUID id, String phone, String email) {
        return new Customer(id, businessId, new CustomerProfile(NAME, phone, email), 3, NOW, NOW.plusSeconds(60));
    }

    private Customer persisted(NewCustomer created) {
        return new Customer(created.id(), created.businessId(), created.profile(), 0,
                created.createdAt(), created.createdAt());
    }

    private void assertNoCreationWork() {
        assertThat(clock.reads()).isZero();
        assertThat(idRequests).hasValue(0);
    }
}
