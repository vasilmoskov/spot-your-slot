package bg.spotyourslot.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.architecture.customerconsumer.CustomerConsumerProbe;
import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerIdentification;
import bg.spotyourslot.customer.CustomerIdentity;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.CustomerMatchOutcome.CreatedCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.ExistingCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.IdentityConflict;
import bg.spotyourslot.customer.CustomerMatchOutcome.InvalidIdentity;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.CustomerReferenceAccess;
import bg.spotyourslot.customer.CustomerReferenceAccess.CustomerReference;
import bg.spotyourslot.customer.IdentityField;
import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.NewCustomer;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The published Customer contracts against real PostgreSQL: every truth-table row, no mutation of a
 * matched Customer, same-Business isolation, the caller-owned transaction contract, the isolation
 * levels, rollback and rollback-only behavior, and the constant Customer SQL bounds. The concurrent
 * races, the real serialization failure and deadlock are in
 * {@link CustomerIdentificationConcurrencyIntegrationTests}.
 */
@Import(CustomerSqlRecording.class)
class CustomerIdentificationIntegrationTests extends PostgresIntegrationTest {
    private static final Instant SEEDED_AT = Instant.parse("2026-10-01T08:00:00.123456Z");
    private static final String NAME = "Анна Иванова";
    private static final String PHONE = "+359895555777";
    private static final String OTHER_PHONE = "+359888123456";
    private static final String EMAIL = "ime@primer.bg";
    private static final String OTHER_EMAIL = "druga@primer.bg";

    @Autowired CustomerIdentification identification;
    @Autowired CustomerReferenceAccess references;
    @Autowired CustomerStore store;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MutableTestClock clock;

    private CustomerConsumerProbe consumer;

    @BeforeEach
    void setUp() {
        // Plain JDBC rather than @Sql: the recording DataSource below wraps the application's one.
        jdbc.sql("TRUNCATE business CASCADE").update();
        CustomerConsumerProbe.createTable(jdbc);
        consumer = new CustomerConsumerProbe(identification, references, jdbc);
        clock.set(CustomerSqlRecording.NOW);
        clock.resetReads();
        CustomerSqlRecording.clear();
    }

    @AfterEach
    void tearDown() {
        CustomerConsumerProbe.dropTable(jdbc);
    }

    // ---- truth table ---------------------------------------------------------------------

    @Test
    void row3PhoneOnlyCreatesACustomerWithTheCanonicalProfileAtTheClockInstant() {
        UUID businessId = business();

        CustomerMatchOutcome outcome = inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(" Анна  Иванова", "0895 555 777", null)));

        UUID id = ((CreatedCustomer) outcome).customerId();
        assertThat(store.findById(businessId, id)).contains(new Customer(
                id, businessId, new CustomerProfile(NAME, PHONE, null), 0,
                CustomerSqlRecording.NOW, CustomerSqlRecording.NOW));
        assertThat(customerCount()).isEqualTo(1);
    }

    @Test
    void row4PhoneOnlyReturnsTheHolderWithoutChangingIt() {
        UUID businessId = business();
        Customer holder = seed(businessId, "Друго име", PHONE, OTHER_EMAIL);

        CustomerMatchOutcome outcome = inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, null)));

        assertThat(outcome).isEqualTo(new ExistingCustomer(holder.id()));
        assertUnchanged(businessId, holder);
    }

    @Test
    void row5EmailOnlyCreatesACustomer() {
        UUID businessId = business();

        CustomerMatchOutcome outcome = inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, null, " IME@Primer.bg ")));

        assertThat(outcome).isInstanceOf(CreatedCustomer.class);
        assertThat(store.findByEmail(businessId, EMAIL)).isPresent();
        assertThat(customerCount()).isEqualTo(1);
    }

    @Test
    void row6EmailOnlyReturnsTheHolderWithoutChangingIt() {
        UUID businessId = business();
        Customer holder = seed(businessId, "Друго име", OTHER_PHONE, EMAIL);

        CustomerMatchOutcome outcome = inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, null, EMAIL)));

        assertThat(outcome).isEqualTo(new ExistingCustomer(holder.id()));
        assertUnchanged(businessId, holder);
    }

    @Test
    void row7BothHeldByTheSameCustomerReturnsThatCustomerWithoutChangingIt() {
        UUID businessId = business();
        Customer holder = seed(businessId, "Друго име", PHONE, EMAIL);

        CustomerMatchOutcome outcome = inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL)));

        assertThat(outcome).isEqualTo(new ExistingCustomer(holder.id()));
        assertUnchanged(businessId, holder);
    }

    @Test
    void row8PhoneHeldByAAndEmailHeldByBIsAConflictAndWritesNothing() {
        UUID businessId = business();
        Customer a = seed(businessId, "А", PHONE, null);
        Customer b = seed(businessId, "Б", null, EMAIL);

        CustomerMatchOutcome outcome = inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL)));

        assertThat(outcome).isEqualTo(new IdentityConflict());
        assertUnchanged(businessId, a);
        assertUnchanged(businessId, b);
        assertThat(customerCount()).isEqualTo(2);
    }

    @Test
    void row9PhoneHeldAndEmailFreeIsAConflictEvenIfTheHolderHasNoEmail() {
        UUID businessId = business();
        Customer noEmail = seed(businessId, "А", PHONE, null);

        assertThat(inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL))))
                .isEqualTo(new IdentityConflict());

        assertUnchanged(businessId, noEmail);
        assertThat(store.findByEmail(businessId, EMAIL)).isEmpty();
        assertThat(customerCount()).isEqualTo(1);
    }

    @Test
    void row9PhoneHeldAndEmailFreeIsAConflictWhenTheHolderHasADifferentEmail() {
        UUID businessId = business();
        Customer other = seed(businessId, "А", PHONE, OTHER_EMAIL);

        assertThat(inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL))))
                .isEqualTo(new IdentityConflict());

        assertUnchanged(businessId, other);
        assertThat(customerCount()).isEqualTo(1);
    }

    @Test
    void row10EmailHeldAndPhoneFreeIsAConflictWhetherTheHolderHasNoPhoneOrADifferentPhone() {
        UUID businessId = business();
        Customer noPhone = seed(businessId, "А", null, EMAIL);

        assertThat(inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL))))
                .isEqualTo(new IdentityConflict());
        assertUnchanged(businessId, noPhone);

        UUID otherBusiness = business();
        Customer differentPhone = seed(otherBusiness, "Б", OTHER_PHONE, EMAIL);
        assertThat(inTransaction(
                () -> identification.findOrCreate(otherBusiness, new CustomerIdentity(NAME, PHONE, EMAIL))))
                .isEqualTo(new IdentityConflict());
        assertUnchanged(otherBusiness, differentPhone);
        assertThat(customerCount()).isEqualTo(2);
    }

    @Test
    void row11BothFreeCreatesACustomerWithBothIdentifiers() {
        UUID businessId = business();

        UUID id = ((CreatedCustomer) inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL))))
                .customerId();

        assertThat(store.findById(businessId, id).orElseThrow().profile())
                .isEqualTo(new CustomerProfile(NAME, PHONE, EMAIL));
    }

    @Test
    void aSingleIdentifierSubmissionNeverAttachesTheMissingOneNorRenamesTheCustomer() {
        UUID businessId = business();
        Customer holder = seed(businessId, "Старо име", PHONE, null);

        inTransaction(() -> identification.findOrCreate(businessId, new CustomerIdentity("Ново име", PHONE, null)));
        inTransaction(() -> identification.findOrCreate(businessId, new CustomerIdentity("Ново име", PHONE, EMAIL)));

        assertUnchanged(businessId, holder);
        assertThat(store.findByEmail(businessId, EMAIL)).isEmpty();
    }

    @Test
    void invalidIdentitiesReturnTheFieldsAndTouchNoCustomerSqlClockOrRow() {
        UUID businessId = business();
        CustomerSqlRecording.clear();
        clock.resetReads();

        CustomerMatchOutcome outcome = inTransaction(
                () -> identification.findOrCreate(businessId, new CustomerIdentity(" ", "abc", null)));

        assertThat(outcome).isEqualTo(new InvalidIdentity(java.util.EnumSet.of(
                IdentityField.DISPLAY_NAME, IdentityField.PHONE)));
        assertThat(CustomerSqlRecording.statementsOfCurrentThread()).isEmpty();
        assertThat(clock.reads()).isZero();
        assertThat(customerCount()).isZero();
    }

    // ---- same-Business isolation ---------------------------------------------------------

    @Test
    void aForeignBusinessHolderIsTreatedAsNobodyAndTheTwoBusinessesStayIndependent() {
        UUID businessA = business();
        UUID businessB = business();
        Customer foreign = seed(businessB, "Чужд", PHONE, EMAIL);

        UUID created = ((CreatedCustomer) inTransaction(
                () -> identification.findOrCreate(businessA, new CustomerIdentity(NAME, PHONE, EMAIL))))
                .customerId();

        assertThat(created).isNotEqualTo(foreign.id());
        assertThat(store.findById(businessA, created)).isPresent();
        assertUnchanged(businessB, foreign);
        assertThat(customerCount()).isEqualTo(2);
    }

    @Test
    void theReferenceLookupIsSameBusinessAndAForeignOrGuessedIdIsEmpty() {
        UUID businessA = business();
        UUID businessB = business();
        Customer own = seed(businessA, "А", PHONE, null);
        Customer foreign = seed(businessB, "Б", OTHER_PHONE, null);

        assertThat(inTransaction(() -> references.find(businessA, own.id())))
                .contains(new CustomerReference(own.id()));
        assertThat(inTransaction(() -> references.find(businessA, foreign.id()))).isEmpty();
        assertThat(inTransaction(() -> references.find(businessA, UUID.randomUUID()))).isEmpty();
        assertThat(inTransaction(() -> references.find(UUID.randomUUID(), own.id()))).isEmpty();
    }

    @Test
    void theReferenceLookupWritesNothingAndTakesNoLock() {
        UUID businessId = business();
        Customer own = seed(businessId, "А", PHONE, null);
        CustomerSqlRecording.clear();

        inTransaction(() -> {
            references.find(businessId, own.id());
            Integer locks = jdbc.sql("""
                            SELECT count(*) FROM pg_locks
                            WHERE pid = pg_backend_pid()
                              AND locktype IN ('tuple', 'transactionid') AND mode <> 'ExclusiveLock'
                            """)
                    .query(Integer.class).single();
            assertThat(locks).isZero();
            return null;
        });

        assertThat(CustomerSqlRecording.statementsOfCurrentThread()).hasSize(1).allMatch(sql -> sql.contains("SELECT"));
        assertUnchanged(businessId, own);
    }

    // ---- caller-owned transaction and isolation ------------------------------------------

    @Test
    void bothOperationsFailWithoutATransactionBeforeAnyClockIdOrSqlWork() {
        UUID businessId = business();
        CustomerSqlRecording.clear();
        clock.resetReads();

        assertThatThrownBy(() -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> references.find(businessId, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(CustomerSqlRecording.statementsOfCurrentThread()).isEmpty();
        assertThat(clock.reads()).isZero();
        assertThat(customerCount()).isZero();
    }

    @Test
    void anInvalidIdentityStillNeedsATransaction() {
        assertThatThrownBy(() -> identification.findOrCreate(UUID.randomUUID(), new CustomerIdentity(" ", null, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void readCommittedIsSupportedAndStrongerIsolationIsAccepted() {
        UUID businessId = business();
        record Level(int isolation, String postgres) { }
        List<Level> levels = List.of(
                new Level(TransactionDefinition.ISOLATION_READ_COMMITTED, "read committed"),
                new Level(TransactionDefinition.ISOLATION_REPEATABLE_READ, "repeatable read"),
                new Level(TransactionDefinition.ISOLATION_SERIALIZABLE, "serializable"));
        int index = 0;
        for (Level level : levels) {
            String phone = "+35988812345" + index;
            index++;
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setIsolationLevel(level.isolation());

            CustomerMatchOutcome first = template.execute(status -> {
                assertThat(jdbc.sql("SHOW transaction_isolation").query(String.class).single())
                        .isEqualTo(level.postgres());
                return identification.findOrCreate(businessId, new CustomerIdentity(NAME, phone, null));
            });
            CustomerMatchOutcome second = template.execute(
                    status -> identification.findOrCreate(businessId, new CustomerIdentity(NAME, phone, null)));

            assertThat(first).as(level.postgres()).isInstanceOf(CreatedCustomer.class);
            assertThat(second).as(level.postgres()).isEqualTo(
                    new ExistingCustomer(((CreatedCustomer) first).customerId()));
        }
        assertThat(customerCount()).isEqualTo(3);
    }

    @Test
    void theOperationsJoinTheCallersTransactionAndNeverOpenOne() {
        UUID businessId = business();

        inTransaction(() -> {
            long transactionId = currentTransactionId();
            identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, null));
            references.find(businessId, UUID.randomUUID());
            assertThat(currentTransactionId()).isEqualTo(transactionId);
            return null;
        });
    }

    // ---- rollback and rollback-only ------------------------------------------------------

    @Test
    void aCustomerCreatedInAnOuterTransactionDisappearsWhenTheOuterTransactionRollsBack() {
        UUID businessId = business();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            consumer.identifyAndRecord(businessId, new CustomerIdentity(NAME, PHONE, EMAIL));
            assertThat(customerCountIn(status)).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(customerCount()).isZero();
        assertThat(CustomerConsumerProbe.rows(jdbc)).isZero();
    }

    @Test
    void normalOutcomesDoNotMarkTheTransactionRollbackOnlyAndTheCreationCommits() {
        UUID businessId = business();
        seed(businessId, "А", OTHER_PHONE, null);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL)); // created
            identification.findOrCreate(businessId, new CustomerIdentity(NAME, PHONE, EMAIL)); // existing
            identification.findOrCreate(businessId, new CustomerIdentity(NAME, OTHER_PHONE, EMAIL)); // conflict
            identification.findOrCreate(businessId, new CustomerIdentity(" ", null, null)); // invalid
            references.find(businessId, UUID.randomUUID());
            assertThat(status.isRollbackOnly()).isFalse();
        });

        assertThat(store.findByPhone(businessId, PHONE)).isPresent();
    }

    @Test
    void anOperationFailureMarksTheCallerTransactionRollbackOnlyEvenWhenTheCallerCatchesIt() {
        UUID unknownBusiness = UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            assertThatThrownBy(() -> identification.findOrCreate(
                    unknownBusiness, new CustomerIdentity(NAME, PHONE, null)))
                    .isExactlyInstanceOf(CustomerOperationFailure.class);
            // The caller swallows the failure and tries to carry on; the commit still cannot succeed.
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(customerCount()).isZero();
    }

    // ---- constant Customer SQL bounds ----------------------------------------------------

    @Test
    void invalidInputRunsZeroCustomerStatements() {
        UUID businessId = business();

        assertThat(statementsFor(businessId, new CustomerIdentity(NAME, "abc", "nope"))).isEmpty();
    }

    @Test
    void anExistingMatchRunsOneStatement() {
        UUID businessId = business();
        seed(businessId, "А", PHONE, EMAIL);

        assertThat(statementsFor(businessId, new CustomerIdentity(NAME, PHONE, EMAIL))).hasSize(1);
        assertThat(statementsFor(businessId, new CustomerIdentity(NAME, PHONE, null))).hasSize(1);
        assertThat(statementsFor(businessId, new CustomerIdentity(NAME, null, EMAIL))).hasSize(1);
    }

    @Test
    void anImmediateConflictRunsOneStatement() {
        UUID businessId = business();
        seed(businessId, "А", PHONE, null);

        assertThat(statementsFor(businessId, new CustomerIdentity(NAME, PHONE, EMAIL))).hasSize(1);
    }

    @Test
    void aCreationRunsExactlyTwoStatementsASelectThenAnInsertOnConflictDoNothing() {
        UUID businessId = business();

        List<String> statements = statementsFor(businessId, new CustomerIdentity(NAME, PHONE, EMAIL));

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).containsIgnoringCase("SELECT");
        assertThat(statements.get(1)).containsIgnoringCase("INSERT").containsIgnoringCase("ON CONFLICT DO NOTHING");
        assertThat(clock.reads()).isEqualTo(1);
    }

    @Test
    void aReferenceLookupRunsOneStatement() {
        UUID businessId = business();
        Customer own = seed(businessId, "А", PHONE, null);
        CustomerSqlRecording.clear();

        inTransaction(() -> references.find(businessId, own.id()));

        assertThat(CustomerSqlRecording.statementsOfCurrentThread()).hasSize(1);
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Customer SQL statements one findOrCreate call ran, from the instrumented DataSource. */
    private List<String> statementsFor(UUID businessId, CustomerIdentity identity) {
        CustomerSqlRecording.clear();
        clock.resetReads();
        inTransaction(() -> identification.findOrCreate(businessId, identity));
        return CustomerSqlRecording.statementsOfCurrentThread();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private long currentTransactionId() {
        return jdbc.sql("SELECT txid_current()").query(Long.class).single();
    }

    private long customerCountIn(Object unused) {
        return customerCount();
    }

    private long customerCount() {
        return jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single();
    }

    private void assertUnchanged(UUID businessId, Customer expected) {
        assertThat(store.findById(businessId, expected.id())).contains(expected);
    }

    private Customer seed(UUID businessId, String name, String phone, String email) {
        return store.insert(new NewCustomer(
                UUID.randomUUID(), businessId, new CustomerProfile(name, phone, email), SEEDED_AT));
    }

    private UUID business() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(SEEDED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Customer Identification Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "customer-identification-" + id)
                .param("now", now)
                .update();
        return id;
    }
}
