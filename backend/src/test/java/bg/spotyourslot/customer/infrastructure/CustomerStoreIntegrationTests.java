package bg.spotyourslot.customer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.NewCustomer;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicateEmail;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicatePhone;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnexpectedFailure;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnknownBusiness;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T08:00:00.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-10-01T09:30:00.654321Z");

    private static final String PHONE = "+359895555777";
    private static final String OTHER_PHONE = "+359888123456";
    private static final String EMAIL = "ime@primer.bg";
    private static final String OTHER_EMAIL = "druga@primer.bg";

    @Autowired
    CustomerStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    // ---- insert --------------------------------------------------------------

    @Test
    void insertReturnsADomainCustomerAtVersionZeroWithEqualAuditInstants() {
        UUID businessId = createBusiness();
        NewCustomer creation = newCustomer(businessId, "Анна Иванова", PHONE, EMAIL);

        Customer created = store.insert(creation);

        assertThat(created).isEqualTo(new Customer(
                creation.id(),
                businessId,
                new CustomerProfile("Анна Иванова", PHONE, EMAIL),
                0,
                CREATED_AT,
                CREATED_AT));
    }

    @Test
    void insertAcceptsAPhoneOnlyAndAnEmailOnlyCustomer() {
        UUID businessId = createBusiness();

        Customer phoneOnly = store.insert(newCustomer(businessId, "Само телефон", PHONE, null));
        Customer emailOnly = store.insert(newCustomer(businessId, "Само имейл", null, EMAIL));

        assertThat(phoneOnly.email()).isNull();
        assertThat(emailOnly.phone()).isNull();
    }

    @Test
    void insertReportsADuplicatePhoneOrEmailWithinOneBusinessAndStoresNothing() {
        UUID businessId = createBusiness();
        store.insert(newCustomer(businessId, "Първи", PHONE, EMAIL));

        assertThatThrownBy(() -> store.insert(newCustomer(businessId, "Втори", PHONE, null)))
                .isInstanceOf(DuplicatePhone.class);
        assertThatThrownBy(() -> store.insert(newCustomer(businessId, "Трети", null, EMAIL)))
                .isInstanceOf(DuplicateEmail.class);

        assertThat(count(businessId)).isEqualTo(1L);
    }

    @Test
    void insertAllowsTheSamePhoneAndEmailAtAnotherBusiness() {
        UUID first = createBusiness();
        UUID second = createBusiness();
        store.insert(newCustomer(first, "Първи", PHONE, EMAIL));

        Customer other = store.insert(newCustomer(second, "Същият човек", PHONE, EMAIL));

        assertThat(other.businessId()).isEqualTo(second);
        assertThat(store.findByPhone(first, PHONE)).get().extracting(Customer::displayName)
                .isEqualTo("Първи");
        assertThat(store.findByPhone(second, PHONE)).get().extracting(Customer::displayName)
                .isEqualTo("Същият човек");
    }

    @Test
    void insertDoesNotMatchMergeOrReuseAnExistingCustomer() {
        UUID businessId = createBusiness();
        Customer first = store.insert(newCustomer(businessId, "Анна Иванова", PHONE, null));

        Customer sameNameDifferentContact =
                store.insert(newCustomer(businessId, "Анна Иванова", OTHER_PHONE, null));

        assertThat(sameNameDifferentContact.id()).isNotEqualTo(first.id());
        assertThat(count(businessId)).isEqualTo(2L);
    }

    @Test
    void insertReportsAnUnknownBusiness() {
        assertThatThrownBy(() -> store.insert(newCustomer(UUID.randomUUID(), "Сирак", PHONE, null)))
                .isInstanceOf(UnknownBusiness.class);
    }

    // ---- lookups -------------------------------------------------------------

    @Test
    void findsByIdPhoneAndEmailWithinTheBusiness() {
        UUID businessId = createBusiness();
        Customer customer = store.insert(newCustomer(businessId, "Анна", PHONE, EMAIL));

        assertThat(store.findById(businessId, customer.id())).contains(customer);
        assertThat(store.findByPhone(businessId, PHONE)).contains(customer);
        assertThat(store.findByEmail(businessId, EMAIL)).contains(customer);
        assertThat(store.findByPhone(businessId, OTHER_PHONE)).isEmpty();
        assertThat(store.findByEmail(businessId, OTHER_EMAIL)).isEmpty();
    }

    @Test
    void aCrossBusinessLookupBehavesExactlyLikeAMissingCustomer() {
        UUID owner = createBusiness();
        UUID other = createBusiness();
        Customer customer = store.insert(newCustomer(owner, "Анна", PHONE, EMAIL));
        UUID guessed = customer.id();
        UUID missing = UUID.randomUUID();

        assertThat(store.findById(other, guessed)).isEqualTo(store.findById(other, missing));
        assertThat(store.findById(other, guessed)).isEmpty();
        assertThat(store.findByPhone(other, PHONE)).isEmpty();
        assertThat(store.findByEmail(other, EMAIL)).isEmpty();
    }

    // ---- update --------------------------------------------------------------

    @Test
    void updateReplacesTheProfileAndIncrementsTheVersionExactlyOnce() {
        UUID businessId = createBusiness();
        Customer created = store.insert(newCustomer(businessId, "Анна", PHONE, null));
        CustomerProfile edited = new CustomerProfile("Анна Петрова", OTHER_PHONE, EMAIL);

        Optional<Customer> updated = store.update(businessId, created.id(), edited, 0, UPDATED_AT);

        assertThat(updated).contains(new Customer(
                created.id(), businessId, edited, 1, CREATED_AT, UPDATED_AT));
        assertThat(store.findById(businessId, created.id())).isEqualTo(updated);
        assertThat(store.update(businessId, created.id(), edited, 1, UPDATED_AT).orElseThrow().version())
                .isEqualTo(2);
    }

    @Test
    void anIdenticalUpdateStillIncrementsTheVersionAndKeepsTheContact() {
        UUID businessId = createBusiness();
        Customer created = store.insert(newCustomer(businessId, "Анна", PHONE, EMAIL));

        Customer updated = store.update(businessId, created.id(), created.profile(), 0, UPDATED_AT)
                .orElseThrow();

        assertThat(updated.profile()).isEqualTo(created.profile());
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void updateMayRemoveOneContactOnlyBecauseTheOtherRemains() {
        UUID businessId = createBusiness();
        Customer created = store.insert(newCustomer(businessId, "Анна", PHONE, EMAIL));

        Customer withoutPhone = store.update(
                        businessId, created.id(), new CustomerProfile("Анна", null, EMAIL), 0, UPDATED_AT)
                .orElseThrow();
        Customer withoutEmail = store.update(
                        businessId, created.id(), new CustomerProfile("Анна", PHONE, null), 1, UPDATED_AT)
                .orElseThrow();

        assertThat(withoutPhone.phone()).isNull();
        assertThat(withoutPhone.email()).isEqualTo(EMAIL);
        assertThat(withoutEmail.phone()).isEqualTo(PHONE);
        assertThat(withoutEmail.email()).isNull();
        // A profile without any contact cannot even be constructed, so it cannot reach the store.
        assertThatThrownBy(() -> new CustomerProfile("Анна", null, null))
                .isInstanceOf(bg.spotyourslot.customer.domain.InvalidCustomerData.class);
    }

    @Test
    void anUpdateMayKeepItsOwnPhoneAndEmailWithoutConflicting() {
        UUID businessId = createBusiness();
        Customer created = store.insert(newCustomer(businessId, "Анна", PHONE, EMAIL));

        Optional<Customer> updated = store.update(
                businessId, created.id(), new CustomerProfile("Ана", PHONE, EMAIL), 0, UPDATED_AT);

        assertThat(updated).isPresent();
    }

    @Test
    void staleMissingAndCrossBusinessUpdatesAreIndistinguishableAndChangeNothing() {
        UUID owner = createBusiness();
        UUID other = createBusiness();
        Customer created = store.insert(newCustomer(owner, "Анна", PHONE, EMAIL));
        CustomerProfile edited = new CustomerProfile("Променено", OTHER_PHONE, OTHER_EMAIL);

        Optional<Customer> stale = store.update(owner, created.id(), edited, 9, UPDATED_AT);
        Optional<Customer> missing = store.update(owner, UUID.randomUUID(), edited, 0, UPDATED_AT);
        Optional<Customer> crossBusiness = store.update(other, created.id(), edited, 0, UPDATED_AT);

        assertThat(stale).isEmpty();
        assertThat(missing).isEqualTo(stale);
        assertThat(crossBusiness).isEqualTo(stale);
        assertThat(store.findById(owner, created.id())).contains(created);
        assertThat(count(other)).isZero();
    }

    @Test
    void anUpdateNeverChangesTheBusinessOfACustomer() {
        UUID owner = createBusiness();
        UUID other = createBusiness();
        Customer created = store.insert(newCustomer(owner, "Анна", PHONE, null));

        store.update(other, created.id(), new CustomerProfile("Анна", PHONE, null), 0, UPDATED_AT);
        store.update(owner, created.id(), new CustomerProfile("Анна", PHONE, null), 0, UPDATED_AT);

        assertThat(store.findById(owner, created.id())).isPresent();
        assertThat(store.findById(other, created.id())).isEmpty();
        assertThat(jdbc.sql("SELECT business_id FROM customer WHERE id = :id")
                        .param("id", created.id())
                        .query(UUID.class)
                        .single())
                .isEqualTo(owner);
    }

    @Test
    void anUpdateOntoAnotherCustomersPhoneOrEmailIsRejectedAndPreservesTheState() {
        UUID businessId = createBusiness();
        Customer holder = store.insert(newCustomer(businessId, "Притежател", PHONE, EMAIL));
        Customer target = store.insert(newCustomer(businessId, "Цел", OTHER_PHONE, OTHER_EMAIL));

        assertThatThrownBy(() -> store.update(
                        businessId, target.id(), new CustomerProfile("Цел", PHONE, OTHER_EMAIL), 0, UPDATED_AT))
                .isInstanceOf(DuplicatePhone.class);
        assertThatThrownBy(() -> store.update(
                        businessId, target.id(), new CustomerProfile("Цел", OTHER_PHONE, EMAIL), 0, UPDATED_AT))
                .isInstanceOf(DuplicateEmail.class);

        assertThat(store.findById(businessId, target.id())).contains(target);
        assertThat(store.findById(businessId, holder.id())).contains(holder);
        assertThat(store.findByPhone(businessId, PHONE)).contains(holder);
    }

    @Test
    void identifiersAreNotMovedOrTransferredByAnImplicitOperation() {
        UUID businessId = createBusiness();
        Customer holder = store.insert(newCustomer(businessId, "Притежател", PHONE, null));
        Customer other = store.insert(newCustomer(businessId, "Друг", null, EMAIL));

        assertThatThrownBy(() -> store.update(
                        businessId, other.id(), new CustomerProfile("Друг", PHONE, EMAIL), 0, UPDATED_AT))
                .isInstanceOf(DuplicatePhone.class);
        // The holder first lets the phone go explicitly; only then can another Customer claim it.
        store.update(businessId, holder.id(), new CustomerProfile("Притежател", null, OTHER_EMAIL), 0, UPDATED_AT);
        Customer claimed = store.update(
                        businessId, other.id(), new CustomerProfile("Друг", PHONE, EMAIL), 0, UPDATED_AT)
                .orElseThrow();

        assertThat(claimed.phone()).isEqualTo(PHONE);
        assertThat(store.findByPhone(businessId, PHONE)).contains(claimed);
    }

    @Test
    void theUpdateInstantNeverMovesBackwardsAndNeverPrecedesCreation() {
        UUID businessId = createBusiness();
        Customer created = store.insert(newCustomer(businessId, "Анна", PHONE, null));
        Customer first = store.update(businessId, created.id(), created.profile(), 0, UPDATED_AT).orElseThrow();

        Customer second = store.update(
                        businessId, created.id(), created.profile(), 1, CREATED_AT.minusSeconds(3600))
                .orElseThrow();

        assertThat(first.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(second.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(second.createdAt()).isEqualTo(CREATED_AT);
        assertThat(second.version()).isEqualTo(2);
    }

    // ---- transaction semantics ------------------------------------------------

    @Test
    void anOuterRollbackRestoresThePreviousCustomerAndRemovesAnInsert() {
        UUID businessId = createBusiness();
        Customer created = store.insert(newCustomer(businessId, "Анна", PHONE, null));
        UUID rolledBackId = UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            store.update(businessId, created.id(),
                    new CustomerProfile("Променено", OTHER_PHONE, EMAIL), 0, UPDATED_AT);
            store.insert(new NewCustomer(
                    rolledBackId, businessId, new CustomerProfile("Нов", null, OTHER_EMAIL), CREATED_AT));
            assertThat(store.findById(businessId, created.id()).orElseThrow().version()).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(store.findById(businessId, created.id())).contains(created);
        assertThat(store.findById(businessId, rolledBackId)).isEmpty();
    }

    @Test
    void aFailedWriteInsideATransactionLeavesNoPartialChange() {
        UUID businessId = createBusiness();
        Customer holder = store.insert(newCustomer(businessId, "Притежател", PHONE, null));
        Customer target = store.insert(newCustomer(businessId, "Цел", OTHER_PHONE, null));
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                store.update(businessId, target.id(),
                        new CustomerProfile("Нова цел", PHONE, EMAIL), 0, UPDATED_AT)))
                .isInstanceOf(DuplicatePhone.class);

        assertThat(store.findById(businessId, target.id())).contains(target);
        assertThat(store.findById(businessId, holder.id())).contains(holder);
        assertThat(store.findByEmail(businessId, EMAIL)).isEmpty();
    }

    // ---- statements and locks --------------------------------------------------

    @Test
    void everyOperationIsExactlyOneBusinessScopedStatementWithoutLocks() {
        UUID businessId = createBusiness();
        JdbcClient spied = spy(jdbc);
        CustomerStore counted = new CustomerStore(spied);
        UUID id = UUID.randomUUID();

        counted.insert(new NewCustomer(id, businessId, new CustomerProfile("Анна", PHONE, EMAIL), CREATED_AT));
        counted.findById(businessId, id);
        counted.findByPhone(businessId, PHONE);
        counted.findByEmail(businessId, EMAIL);
        counted.update(businessId, id, new CustomerProfile("Анна", PHONE, EMAIL), 0, UPDATED_AT);

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(spied, times(5)).sql(statements.capture());
        List<String> sql = statements.getAllValues();
        assertThat(sql).allSatisfy(statement ->
                assertThat(statement)
                        .contains(":businessId")
                        .doesNotContain("SELECT *")
                        .doesNotContainIgnoringCase("for update")
                        .doesNotContainIgnoringCase("for share")
                        .doesNotContainIgnoringCase("for no key"));
        // Every lookup and the update filter by business_id; the insert supplies it explicitly.
        assertThat(sql.subList(1, 5)).allSatisfy(statement ->
                assertThat(statement).containsIgnoringWhitespaces("business_id=:businessId"));
        String update = sql.get(4);
        assertThat(update.substring(0, update.indexOf("WHERE"))).doesNotContain("business_id");
        assertThat(update).contains("version = :expectedVersion");
        // The reads run no join and no subquery, so there is no per-row follow-up statement.
        assertThat(sql.subList(1, 4)).allSatisfy(statement -> {
            assertThat(statement).doesNotContainIgnoringCase("join");
            assertThat(statement.toUpperCase().split("SELECT", -1)).hasSize(2);
        });
    }

    // ---- sanitized failures ----------------------------------------------------

    @Test
    void expectedFailuresCarryFixedMessagesNoCauseAndNoSubmittedValues() {
        UUID businessId = createBusiness();
        String phone = "+359897000111";
        String email = "secret.person@primer.bg";
        store.insert(newCustomer(businessId, "Тайно Име", phone, email));

        List<CustomerPersistenceException> failures = List.of(
                catchPersistence(() -> store.insert(newCustomer(businessId, "Друго", phone, null))),
                catchPersistence(() -> store.insert(newCustomer(businessId, "Друго", null, email))),
                catchPersistence(() -> store.insert(newCustomer(UUID.randomUUID(), "Друго", OTHER_PHONE, null))));

        for (CustomerPersistenceException failure : failures) {
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.toString())
                    .doesNotContain(phone)
                    .doesNotContain(email)
                    .doesNotContain("Тайно")
                    .doesNotContain(businessId.toString())
                    .doesNotContainIgnoringCase("customer_business")
                    .doesNotContainIgnoringCase("insert")
                    .doesNotContainIgnoringCase("duplicate key");
        }
        assertThat(failures.get(0)).isInstanceOf(DuplicatePhone.class)
                .hasMessage("Customer phone is already in use");
        assertThat(failures.get(1)).isInstanceOf(DuplicateEmail.class)
                .hasMessage("Customer email is already in use");
        assertThat(failures.get(2)).isInstanceOf(UnknownBusiness.class)
                .hasMessage("Customer Business does not exist");
    }

    @Test
    void anUnclassifiedDatabaseErrorIsASanitizedUnexpectedFailureKeepingOnlyTheSqlState() {
        UUID businessId = createBusiness();
        // A NUL character is a valid canonical name for the domain but invalid text for PostgreSQL.
        NewCustomer invalidText = newCustomer(businessId, "Анна\u0000", PHONE, null);

        CustomerPersistenceException failure = catchPersistence(() -> store.insert(invalidText));

        assertThat(failure).isInstanceOf(UnexpectedFailure.class)
                .hasMessage("Customer persistence operation failed");
        assertThat(((UnexpectedFailure) failure).sqlState()).isEqualTo("22021");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        // An unexpected failure keeps its own stack trace (application frames only) for diagnosis.
        assertThat(failure.getStackTrace()).isNotEmpty();
        assertThat(java.util.Arrays.stream(failure.getStackTrace()).map(StackTraceElement::toString))
                .noneMatch(frame -> frame.contains("Анна") || frame.contains(PHONE));
        assertThat(failure.toString()).doesNotContain("Анна").doesNotContain(PHONE);
    }

    @Test
    void aMissingBusinessDoesNotLeakThroughAnyReadOrUpdate() {
        UUID missing = UUID.randomUUID();

        assertThat(store.findById(missing, UUID.randomUUID())).isEmpty();
        assertThat(store.findByPhone(missing, PHONE)).isEmpty();
        assertThat(store.findByEmail(missing, EMAIL)).isEmpty();
        assertThat(store.update(missing, UUID.randomUUID(), new CustomerProfile("Анна", PHONE, null), 0, UPDATED_AT))
                .isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------

    private CustomerPersistenceException catchPersistence(Runnable operation) {
        try {
            operation.run();
        } catch (CustomerPersistenceException failure) {
            return failure;
        }
        throw new AssertionError("Expected a CustomerPersistenceException");
    }

    private static NewCustomer newCustomer(UUID businessId, String name, String phone, String email) {
        return new NewCustomer(
                UUID.randomUUID(), businessId, new CustomerProfile(name, phone, email), CREATED_AT);
    }

    private long count(UUID businessId) {
        return jdbc.sql("SELECT count(*) FROM customer WHERE business_id = :id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Customer Store Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "customer-store-" + id)
                .param("now", now)
                .update();
        return id;
    }
}
