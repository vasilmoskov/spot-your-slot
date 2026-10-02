package bg.spotyourslot.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.architecture.customerconsumer.CustomerConsumerProbe;
import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerIdentification;
import bg.spotyourslot.customer.CustomerIdentity;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.CustomerMatchOutcome.CreatedCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.ExistingCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.IdentityConflict;
import bg.spotyourslot.customer.CustomerReferenceAccess;
import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.infrastructure.CustomerStore;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-PostgreSQL concurrency evidence for the published Customer contracts, with a test-only
 * consumer that writes an Appointment-like probe row in the caller-owned transaction.
 *
 * <p>Synchronization is deterministic: latches order the transactions and PostgreSQL lock-wait
 * evidence ({@code pg_stat_activity.wait_event_type = 'Lock'}) proves a transaction is blocked
 * before the other is released. The only timeouts are ceilings that stop a hung test; no correctness
 * claim rests on elapsed time. A real {@code 40001} comes from a REPEATABLE READ transaction whose
 * snapshot predates a committed conflicting insert, and a real {@code 40P01} from two transactions
 * that insert each other's identifiers in opposite order.
 */
@Import(CustomerSqlRecording.class)
class CustomerIdentificationConcurrencyIntegrationTests extends PostgresIntegrationTest {
    private static final Duration CEILING = Duration.ofSeconds(30);
    private static final Instant SEEDED_AT = Instant.parse("2026-10-01T08:00:00Z");
    private static final String NAME = "Анна Иванова";
    private static final String PHONE = "+359895555777";
    private static final String OTHER_PHONE = "+359888123456";
    private static final String EMAIL = "ime@primer.bg";

    @Autowired CustomerIdentification identification;
    @Autowired CustomerReferenceAccess references;
    @Autowired CustomerStore store;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private CustomerConsumerProbe consumer;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE business CASCADE").update();
        CustomerConsumerProbe.createTable(jdbc);
        consumer = new CustomerConsumerProbe(identification, references, jdbc);
        CustomerSqlRecording.clear();
    }

    @AfterEach
    void tearDown() {
        CustomerConsumerProbe.dropTable(jdbc);
    }

    // ---- ordinary creation races resolve to normal outcomes -------------------------------

    @Test
    void identicalPhoneOnlySubmissionsCreateOneCustomerAndReturnItToTheOther() {
        UUID businessId = business();
        CustomerIdentity identity = new CustomerIdentity(NAME, PHONE, null);

        Race race = race(businessId, List.of(identity), identity, true);

        UUID created = ((CreatedCustomer) race.holderOutcomes().get(0)).customerId();
        assertThat(race.racerOutcome()).isEqualTo(new ExistingCustomer(created));
        assertThat(customerIds()).containsExactly(created);
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(2);
        assertThat(race.racerCustomerStatements()).hasSize(3);
        assertNoDuplicates();
    }

    @Test
    void identicalEmailOnlySubmissionsCreateOneCustomerAndReturnItToTheOther() {
        UUID businessId = business();
        CustomerIdentity identity = new CustomerIdentity(NAME, null, EMAIL);

        Race race = race(businessId, List.of(identity), identity, true);

        UUID created = ((CreatedCustomer) race.holderOutcomes().get(0)).customerId();
        assertThat(race.racerOutcome()).isEqualTo(new ExistingCustomer(created));
        assertThat(customerIds()).containsExactly(created);
        assertThat(race.racerCustomerStatements()).hasSize(3);
        assertNoDuplicates();
    }

    @Test
    void identicalPhoneAndEmailSubmissionsCreateOneCustomerAndReturnItToTheOther() {
        UUID businessId = business();
        CustomerIdentity identity = new CustomerIdentity(NAME, PHONE, EMAIL);

        Race race = race(businessId, List.of(identity), identity, true);

        UUID created = ((CreatedCustomer) race.holderOutcomes().get(0)).customerId();
        assertThat(race.racerOutcome()).isEqualTo(new ExistingCustomer(created));
        assertThat(customerIds()).containsExactly(created);
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(2);
        assertThat(race.racerCustomerStatements()).hasSize(3);
        assertNoDuplicates();
    }

    @Test
    void aRacerWithABroaderIdentityThanTheWinnerIsAConflictWithoutAttachingTheNewIdentifier() {
        UUID businessId = business();

        Race race = race(businessId, List.of(new CustomerIdentity(NAME, PHONE, null)),
                new CustomerIdentity(NAME, PHONE, EMAIL), true);

        UUID created = ((CreatedCustomer) race.holderOutcomes().get(0)).customerId();
        assertThat(race.racerOutcome()).isEqualTo(new IdentityConflict());
        assertThat(customerIds()).containsExactly(created);
        assertThat(store.findByEmail(businessId, EMAIL)).isEmpty();
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(1);
        assertThat(race.racerCustomerStatements()).hasSize(3);
        assertNoDuplicates();
    }

    @Test
    void aRacerWithAnEmailHeldByTheWinnerAndANewPhoneIsAConflict() {
        UUID businessId = business();

        Race race = race(businessId, List.of(new CustomerIdentity(NAME, null, EMAIL)),
                new CustomerIdentity(NAME, PHONE, EMAIL), true);

        assertThat(race.racerOutcome()).isEqualTo(new IdentityConflict());
        assertThat(customerIds()).hasSize(1);
        assertThat(store.findByPhone(businessId, PHONE)).isEmpty();
        assertNoDuplicates();
    }

    @Test
    void aRacerWithASubsetOfTheWinnersIdentifiersResolvesToTheWinner() {
        UUID businessId = business();
        CustomerIdentity winner = new CustomerIdentity(NAME, PHONE, EMAIL);

        Race phoneRacer = race(businessId, List.of(winner), new CustomerIdentity(NAME, PHONE, null), true);
        UUID created = ((CreatedCustomer) phoneRacer.holderOutcomes().get(0)).customerId();
        assertThat(phoneRacer.racerOutcome()).isEqualTo(new ExistingCustomer(created));

        UUID otherBusiness = business();
        Race emailRacer = race(otherBusiness, List.of(winner), new CustomerIdentity(NAME, null, EMAIL), true);
        UUID otherCreated = ((CreatedCustomer) emailRacer.holderOutcomes().get(0)).customerId();
        assertThat(emailRacer.racerOutcome()).isEqualTo(new ExistingCustomer(otherCreated));

        assertThat(customerIds()).hasSize(2);
        assertNoDuplicates();
    }

    @Test
    void aRaceRevealingPhoneAAndEmailBIsAnIdentityConflictWithNoDuplicate() {
        UUID businessId = business();

        Race race = race(businessId,
                List.of(new CustomerIdentity("А", PHONE, null), new CustomerIdentity("Б", null, EMAIL)),
                new CustomerIdentity(NAME, PHONE, EMAIL), true);

        assertThat(race.holderOutcomes()).allMatch(CreatedCustomer.class::isInstance);
        assertThat(race.racerOutcome()).isEqualTo(new IdentityConflict());
        assertThat(customerIds()).hasSize(2);
        assertThat(store.findByPhone(businessId, PHONE).orElseThrow().email()).isNull();
        assertThat(store.findByEmail(businessId, EMAIL).orElseThrow().phone()).isNull();
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(2);
        assertNoDuplicates();
    }

    @Test
    void whenTheWinnerRollsBackTheWaitingInsertCreatesTheCustomerItself() {
        UUID businessId = business();
        CustomerIdentity identity = new CustomerIdentity(NAME, PHONE, EMAIL);

        Race race = race(businessId, List.of(identity), identity, false);

        UUID created = ((CreatedCustomer) race.racerOutcome()).customerId();
        assertThat(race.holderRolledBack()).isTrue();
        assertThat(customerIds()).containsExactly(created);
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(1);
        assertThat(race.racerCustomerStatements()).hasSize(2);
        assertNoDuplicates();
    }

    // ---- a real serialization failure (40001) --------------------------------------------

    @Test
    void aSerializationFailureIsAConcurrentConflictThatRollsEverythingBackAndANewTransactionCanRetry() {
        UUID businessId = business();
        CustomerIdentity identity = new CustomerIdentity(NAME, PHONE, null);
        UUID[] winner = new UUID[1];

        Throwable failure = catchThrowable(() -> repeatableRead().executeWithoutResult(status -> {
            snapshot();
            winner[0] = ((CreatedCustomer) committedElsewhere(
                    () -> consumer.identifyAndRecord(businessId, identity))).customerId();
            consumer.identifyAndRecord(businessId, identity);
        }));

        assertThat(failure).isExactlyInstanceOf(CustomerConcurrentConflict.class);
        assertSanitized(failure);
        assertThat(customerIds()).containsExactly(winner[0]);
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(1);

        CustomerMatchOutcome retry = new TransactionTemplate(transactionManager)
                .execute(status -> consumer.identifyAndRecord(businessId, identity));
        assertThat(retry).isEqualTo(new ExistingCustomer(winner[0]));
        assertThat(customerIds()).containsExactly(winner[0]);
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(2);
    }

    @Test
    void aCallerThatCatchesTheConflictCannotContinueAnAppointmentWriteOrCommit() {
        UUID businessId = business();
        CustomerIdentity identity = new CustomerIdentity(NAME, PHONE, null);
        AtomicInteger continuedWrites = new AtomicInteger();
        UUID[] winner = new UUID[1];

        assertThatThrownBy(() -> repeatableRead().executeWithoutResult(status -> {
            snapshot();
            winner[0] = ((CreatedCustomer) committedElsewhere(
                    () -> consumer.identifyAndRecord(businessId, identity))).customerId();
            assertThatThrownBy(() -> consumer.identifyAndRecord(businessId, identity))
                    .isExactlyInstanceOf(CustomerConcurrentConflict.class);
            assertThat(status.isRollbackOnly()).isTrue();
            // PostgreSQL aborted the transaction, so the caller's Appointment-like write fails ...
            assertThatThrownBy(() -> {
                consumer.record(businessId, winner[0]);
                continuedWrites.incrementAndGet();
            }).isInstanceOf(DataAccessException.class);
            // ... and even if the caller swallows everything and returns, the commit cannot succeed.
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(continuedWrites).hasValue(0);
        assertThat(customerIds()).containsExactly(winner[0]);
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(1);
    }

    // ---- a real deadlock (40P01) ---------------------------------------------------------

    @Test
    void oppositeOrderCreationsDeadlockAndExactlyOneVictimGetsAConcurrentConflict() {
        UUID businessId = business();
        CustomerIdentity first = new CustomerIdentity(NAME, PHONE, null);
        CustomerIdentity second = new CustomerIdentity(NAME, OTHER_PHONE, null);
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondHolds = new CountDownLatch(1);
        CountDownLatch firstIsWaiting = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Object> one = CompletableFuture.supplyAsync(() -> attempt(
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        firstPid.set(backendPid());
                        consumer.identifyAndRecord(businessId, first);
                        firstHolds.countDown();
                        await(secondHolds, "the second transaction did not create its Customer");
                        return consumer.identifyAndRecord(businessId, second);
                    })), executor);
            CompletableFuture<Object> two = CompletableFuture.supplyAsync(() -> attempt(
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        await(firstHolds, "the first transaction did not create its Customer");
                        consumer.identifyAndRecord(businessId, second);
                        secondHolds.countDown();
                        await(firstIsWaiting, "the first transaction was not seen waiting");
                        return consumer.identifyAndRecord(businessId, first);
                    })), executor);

            await(secondHolds, "the second transaction did not create its Customer");
            awaitLockWait(firstPid.get(), one);
            firstIsWaiting.countDown();

            List<Object> outcomes = List.of(completed(one), completed(two));

            assertThat(outcomes.stream().filter(CustomerConcurrentConflict.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(CreatedCustomer.class::isInstance)).hasSize(1);
            outcomes.stream().filter(CustomerConcurrentConflict.class::isInstance)
                    .forEach(victim -> assertSanitized((Throwable) victim));
        } finally {
            firstIsWaiting.countDown();
            secondHolds.countDown();
        }

        // The survivor's two Customers remain; the victim's earlier Customer and probe rolled back.
        assertThat(customerRows().stream().map(Customer::phone).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(PHONE, OTHER_PHONE);
        assertThat(customerRows()).hasSize(2);
        assertThat(CustomerConsumerProbe.rows(jdbc)).isEqualTo(2);
        assertNoDuplicates();

        // A completely new outer transaction can retry the victim's work.
        CustomerMatchOutcome retry = new TransactionTemplate(transactionManager)
                .execute(status -> consumer.identifyAndRecord(businessId, first));
        assertThat(retry).isInstanceOf(ExistingCustomer.class);
    }

    // ---- harness -------------------------------------------------------------------------

    private record Race(
            List<CustomerMatchOutcome> holderOutcomes,
            CustomerMatchOutcome racerOutcome,
            boolean holderRolledBack,
            List<String> racerCustomerStatements) {
    }

    private static final class RollbackRequested extends RuntimeException {
        private RollbackRequested() {
            super("rollback requested", null, false, false);
        }
    }

    /**
     * Runs the holder identities in one open transaction, starts the racer in a second transaction,
     * proves through PostgreSQL that the racer is waiting on a lock, then releases the holder by
     * committing or rolling it back.
     */
    private Race race(UUID businessId, List<CustomerIdentity> holder, CustomerIdentity racer, boolean commit) {
        CountDownLatch holderWrote = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch racerStarted = new CountDownLatch(1);
        AtomicInteger racerPid = new AtomicInteger();
        AtomicLong racerThread = new AtomicLong();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Object> holderResult = CompletableFuture.supplyAsync(() -> attempt(
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        List<CustomerMatchOutcome> outcomes = holder.stream()
                                .map(identity -> consumer.identifyAndRecord(businessId, identity))
                                .toList();
                        holderWrote.countDown();
                        await(release, "the holder was not released");
                        if (!commit) {
                            throw new RollbackRequested();
                        }
                        return outcomes;
                    })), executor);
            await(holderWrote, "the holder did not write");

            CompletableFuture<Object> racerResult = CompletableFuture.supplyAsync(() -> attempt(
                    () -> new TransactionTemplate(transactionManager).execute(status -> {
                        racerPid.set(backendPid());
                        racerThread.set(Thread.currentThread().threadId());
                        racerStarted.countDown();
                        return consumer.identifyAndRecord(businessId, racer);
                    })), executor);
            await(racerStarted, "the racer did not start");
            awaitLockWait(racerPid.get(), racerResult);
            release.countDown();

            Object holderOutcome = completed(holderResult);
            Object racerOutcome = completed(racerResult);

            assertThat(racerOutcome).isInstanceOf(CustomerMatchOutcome.class);
            if (commit) {
                assertThat(holderOutcome).isInstanceOf(List.class);
            } else {
                assertThat(holderOutcome).isInstanceOf(RollbackRequested.class);
            }
            @SuppressWarnings("unchecked")
            List<CustomerMatchOutcome> holderOutcomes =
                    commit ? (List<CustomerMatchOutcome>) holderOutcome : List.of();
            return new Race(holderOutcomes, (CustomerMatchOutcome) racerOutcome, !commit,
                    findOrCreateStatements(
                            CustomerSqlRecording.statementsOf(racerThread.get()),
                            (CustomerMatchOutcome) racerOutcome));
        } finally {
            release.countDown();
        }
    }

    /**
     * The statements of the findOrCreate call alone. When the outcome carries a Customer ID the
     * test-only consumer then validates it with one reference lookup, a by-ID SELECT that is
     * removed here so only the capability's own bound is asserted.
     */
    private static List<String> findOrCreateStatements(List<String> recorded, CustomerMatchOutcome outcome) {
        boolean consumerLookup = outcome instanceof CreatedCustomer || outcome instanceof ExistingCustomer;
        if (!consumerLookup) {
            return recorded;
        }
        assertThat(recorded.get(recorded.size() - 1)).contains("AND id = ?");
        return recorded.subList(0, recorded.size() - 1);
    }

    /** Runs the work in its own committed transaction while the calling transaction stays open. */
    private <T> T committedElsewhere(Supplier<T> work) {
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return separate.execute(status -> work.get());
    }

    private TransactionTemplate repeatableRead() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    /** The first statement of a REPEATABLE READ transaction fixes its snapshot. */
    private void snapshot() {
        jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single();
    }

    private void awaitLockWait(int backendPid, CompletableFuture<?> operation) {
        long deadline = System.nanoTime() + CEILING.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<String> wait = jdbc.sql("""
                            SELECT wait_event_type
                            FROM pg_stat_activity
                            WHERE pid = :pid AND wait_event_type = 'Lock'
                            """)
                    .param("pid", backendPid)
                    .query(String.class)
                    .optional();
            if (wait.isPresent()) {
                assertThat(operation).isNotDone();
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("PostgreSQL lock wait was not observed");
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private Object attempt(Supplier<Object> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(CEILING.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }

    private static <T> T completed(CompletableFuture<T> future) {
        try {
            return future.get(CEILING.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent operation was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("concurrent operation did not complete", exception);
        }
    }

    private static void assertSanitized(Throwable thrown) {
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown.getSuppressed()).isEmpty();
        assertThat(thrown.getMessage()).isEqualTo("Customer operation conflicted with a concurrent change");
        assertThat(thrown.getStackTrace()).isNotEmpty();
    }

    // ---- assertions and fixtures ---------------------------------------------------------

    private void assertNoDuplicates() {
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM (
                            SELECT business_id, phone FROM customer WHERE phone IS NOT NULL
                            GROUP BY business_id, phone HAVING count(*) > 1
                            UNION ALL
                            SELECT business_id, email FROM customer WHERE email IS NOT NULL
                            GROUP BY business_id, email HAVING count(*) > 1) duplicates
                        """).query(Long.class).single()).isZero();
    }

    private Set<UUID> customerIds() {
        return customerRows().stream().map(Customer::id).collect(Collectors.toSet());
    }

    private List<Customer> customerRows() {
        return jdbc.sql("SELECT id, business_id FROM customer")
                .query((rs, row) -> store.findById(
                        rs.getObject("business_id", UUID.class), rs.getObject("id", UUID.class)).orElseThrow())
                .list();
    }

    private UUID business() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(SEEDED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Customer Concurrency Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "customer-concurrency-" + id)
                .param("now", now)
                .update();
        return id;
    }

}
