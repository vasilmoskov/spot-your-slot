package bg.spotyourslot.scheduling.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException.OverlapConflict;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-PostgreSQL race evidence. A first transaction holds its write open on a
 * latch; the second transaction is proven to be waiting on a PostgreSQL lock
 * through {@code pg_stat_activity} before the first is released. No sleeps.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleExceptionStoreConcurrencyIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-29T09:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired
    ScheduleExceptionStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void conflictingInsertsSerializeAndTheSecondFailsWhenTheFirstCommits() {
        UUID businessId = createBusiness();
        NewScheduleException first = newClosure(businessId, DAY, DAY.plusDays(2));
        NewScheduleException second = newClosure(businessId, DAY.plusDays(1), DAY.plusDays(3));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.insert(first));
            race.startSecond(() -> store.insert(second));
            race.assertSecondWaitsOnALock();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(ScheduleException.class);
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(store.findByBusinessIdAndId(businessId, first.id())).isPresent();
        assertThat(store.findByBusinessIdAndId(businessId, second.id())).isEmpty();
        assertThat(exceptionRows()).isEqualTo(1);
    }

    @Test
    void conflictingInsertSucceedsWhenTheFirstTransactionRollsBack() {
        UUID businessId = createBusiness();
        NewScheduleException first = newClosure(businessId, DAY, DAY.plusDays(2));
        NewScheduleException second = newClosure(businessId, DAY.plusDays(1), DAY.plusDays(3));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.insert(first));
            race.startSecond(() -> store.insert(second));
            race.assertSecondWaitsOnALock();

            race.rollBackFirst();

            assertThat(race.firstOutcome()).isInstanceOf(RollbackRequested.class);
            assertThat(race.secondOutcome()).isInstanceOf(ScheduleException.class);
        }

        assertThat(store.findByBusinessIdAndId(businessId, first.id())).isEmpty();
        assertThat(store.findByBusinessIdAndId(businessId, second.id())).isPresent();
        assertThat(exceptionRows()).isEqualTo(1);
    }

    @Test
    void sameVersionReplacementsHaveExactlyOneWinnerAndNeverAMixedPeriodSet() {
        UUID businessId = createBusiness();
        NewScheduleException created = insert(businessId, partial(
                DAY, List.of(period("09:00", "10:00"), period("11:00", "12:00"))));
        ScheduleExceptionContent winnerContent = partial(
                DAY.plusDays(1), List.of(period("13:00", "14:00")));
        ScheduleExceptionContent loserContent = partial(
                DAY.plusDays(2), List.of(period("15:00", "16:00"), period("16:00", "17:00")));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.replace(
                    businessId, created.id(), 0, winnerContent, UPDATED_AT));
            race.startSecond(() -> store.replace(
                    businessId, created.id(), 0, loserContent, UPDATED_AT.plusSeconds(1)));
            race.assertSecondWaitsOnALock();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOfSatisfying(
                    Optional.class, result -> assertThat(result).isPresent());
            assertThat(race.secondOutcome()).isEqualTo(Optional.empty());
        }

        ScheduleException stored = store.findByBusinessIdAndId(businessId, created.id())
                .orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.content()).isEqualTo(winnerContent);
        assertThat(stored.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(periodRows(created.id())).isEqualTo(1);
    }

    @Test
    void deleteHoldingTheRowFirstMakesAConcurrentReplacementMatchNothing() {
        UUID businessId = createBusiness();
        NewScheduleException created = insert(businessId, partial(
                DAY, List.of(period("09:00", "10:00"))));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.delete(businessId, created.id(), 0));
            race.startSecond(() -> store.replace(
                    businessId,
                    created.id(),
                    0,
                    partial(DAY.plusDays(1), List.of(period("12:00", "13:00"))),
                    UPDATED_AT));
            race.assertSecondWaitsOnALock();

            race.commitFirst();

            assertThat(race.firstOutcome()).isEqualTo(true);
            assertThat(race.secondOutcome()).isEqualTo(Optional.empty());
        }

        assertThat(store.findByBusinessIdAndId(businessId, created.id())).isEmpty();
        assertThat(periodRows(created.id())).isZero();
    }

    @Test
    void replacementHoldingTheRowFirstMakesAConcurrentDeleteOfTheOldVersionMatchNothing() {
        UUID businessId = createBusiness();
        NewScheduleException created = insert(businessId, partial(
                DAY, List.of(period("09:00", "10:00"))));
        ScheduleExceptionContent replacement = partial(
                DAY.plusDays(1), List.of(period("12:00", "13:00")));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.replace(
                    businessId, created.id(), 0, replacement, UPDATED_AT));
            race.startSecond(() -> store.delete(businessId, created.id(), 0));
            race.assertSecondWaitsOnALock();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOfSatisfying(
                    Optional.class, result -> assertThat(result).isPresent());
            assertThat(race.secondOutcome()).isEqualTo(false);
        }

        ScheduleException stored = store.findByBusinessIdAndId(businessId, created.id())
                .orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.content()).isEqualTo(replacement);
    }

    @Test
    void concurrentReplacementsIntoTheSameDateConflictAndThePriorAggregateIsPreserved() {
        UUID businessId = createBusiness();
        ScheduleExceptionContent movedContent = partial(
                DAY.plusDays(5), List.of(period("09:00", "10:00")));
        ScheduleExceptionContent blockedOriginal = partial(
                DAY.plusDays(10), List.of(period("11:00", "12:00"), period("12:00", "13:00")));
        NewScheduleException mover = insert(businessId, partial(
                DAY, List.of(period("08:00", "09:00"))));
        NewScheduleException blocked = insert(businessId, blockedOriginal);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.replace(
                    businessId, mover.id(), 0, movedContent, UPDATED_AT));
            race.startSecond(() -> store.replace(
                    businessId,
                    blocked.id(),
                    0,
                    partial(DAY.plusDays(5), List.of(period("14:00", "15:00"))),
                    UPDATED_AT));
            race.assertSecondWaitsOnALock();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOfSatisfying(
                    Optional.class, result -> assertThat(result).isPresent());
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(store.findByBusinessIdAndId(businessId, mover.id()).orElseThrow().content())
                .isEqualTo(movedContent);
        assertThat(store.findByBusinessIdAndId(businessId, blocked.id()))
                .contains(new ScheduleException(
                        blocked.id(), businessId, blockedOriginal, 0, CREATED_AT, CREATED_AT));
        assertThat(periodRows(blocked.id())).isEqualTo(2);
    }

    @Test
    void oppositeOrderReplacementsDeadlockAndPostgresqlAbortsExactlyOneAsAConcurrentWriteConflict() {
        UUID businessId = createBusiness();
        NewScheduleException left = insert(businessId, partial(
                DAY, List.of(period("08:00", "09:00"))));
        NewScheduleException right = insert(businessId, partial(
                DAY.plusDays(10), List.of(period("08:00", "09:00"))));
        CountDownLatch firstHoldsLeft = new CountDownLatch(1);
        CountDownLatch secondHoldsRight = new CountDownLatch(1);
        CountDownLatch firstIsWaiting = new CountDownLatch(1);
        AtomicInteger firstBackendPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Object> first = CompletableFuture.supplyAsync(
                    () -> attempt(() -> transaction().execute(status -> {
                        firstBackendPid.set(backendPid());
                        store.replace(businessId, left.id(), 0,
                                partial(DAY.plusDays(1), List.of(period("08:00", "09:00"))),
                                UPDATED_AT);
                        firstHoldsLeft.countDown();
                        await(secondHoldsRight, "the second transaction did not lock its row");
                        return store.replace(businessId, right.id(), 0,
                                partial(DAY.plusDays(12), List.of(period("08:00", "09:00"))),
                                UPDATED_AT);
                    })), executor);
            CompletableFuture<Object> second = CompletableFuture.supplyAsync(
                    () -> attempt(() -> transaction().execute(status -> {
                        await(firstHoldsLeft, "the first transaction did not lock its row");
                        store.replace(businessId, right.id(), 0,
                                partial(DAY.plusDays(11), List.of(period("08:00", "09:00"))),
                                UPDATED_AT);
                        secondHoldsRight.countDown();
                        await(firstIsWaiting, "the first transaction was not seen waiting");
                        return store.replace(businessId, left.id(), 0,
                                partial(DAY.plusDays(13), List.of(period("08:00", "09:00"))),
                                UPDATED_AT);
                    })), executor);
            await(secondHoldsRight, "the second transaction did not lock its row");
            assertBackendWaitsOnALock(firstBackendPid, first);
            firstIsWaiting.countDown();

            List<Object> outcomes = List.of(completed(first), completed(second));

            assertThat(outcomes.stream().filter(
                            ScheduleExceptionPersistenceException.ConcurrentWriteConflict.class
                                    ::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream().filter(Optional.class::isInstance)).hasSize(1);
        }

        assertThat(store.findByBusinessIdAndId(businessId, left.id()).orElseThrow().version())
                .isIn(0L, 1L);
        assertThat(exceptionRows()).isEqualTo(2);
    }

    // ---- race harness --------------------------------------------------------

    /** A transaction that was rolled back on purpose. */
    private static final class RollbackRequested extends RuntimeException {
        private RollbackRequested() {
            super("rollback requested");
        }
    }

    private final class Race {
        private final ExecutorService executor;
        private final CountDownLatch firstWrote = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicInteger secondBackendPid = new AtomicInteger();
        private volatile boolean commit = true;
        private CompletableFuture<Object> first;
        private CompletableFuture<Object> second;

        private Race(ExecutorService executor) {
            this.executor = executor;
        }

        private void holdFirst(Supplier<Object> operation) {
            first = CompletableFuture.supplyAsync(() -> attempt(() -> transaction().execute(
                    status -> {
                        Object result = operation.get();
                        firstWrote.countDown();
                        await(releaseFirst, "the first transaction was not released");
                        if (!commit) {
                            throw new RollbackRequested();
                        }
                        return result;
                    })), executor);
            await(firstWrote, "the first transaction did not perform its write");
        }

        private void startSecond(Supplier<Object> operation) {
            second = CompletableFuture.supplyAsync(() -> attempt(() -> transaction().execute(
                    status -> {
                        secondBackendPid.set(backendPid());
                        secondStarted.countDown();
                        return operation.get();
                    })), executor);
            await(secondStarted, "the second transaction did not start");
        }

        private void assertSecondWaitsOnALock() {
            assertBackendWaitsOnALock(secondBackendPid, second);
        }

        private void commitFirst() {
            releaseFirst.countDown();
        }

        private void rollBackFirst() {
            commit = false;
            releaseFirst.countDown();
        }

        private Object firstOutcome() {
            return completed(first);
        }

        private Object secondOutcome() {
            return completed(second);
        }
    }

    private void assertBackendWaitsOnALock(AtomicInteger backendPid, CompletableFuture<?> operation) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<String> wait = jdbc.sql("""
                            SELECT wait_event_type
                            FROM pg_stat_activity
                            WHERE pid = :pid AND wait_event_type = 'Lock'
                            """)
                    .param("pid", backendPid.get())
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

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }

    private static <T> T completed(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent operation was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("concurrent operation did not complete", exception);
        }
    }

    // ---- fixtures ------------------------------------------------------------

    private NewScheduleException insert(UUID businessId, ScheduleExceptionContent content) {
        NewScheduleException created =
                new NewScheduleException(UUID.randomUUID(), businessId, content, CREATED_AT);
        store.insert(created);
        return created;
    }

    private static NewScheduleException newClosure(
            UUID businessId, LocalDate first, LocalDate last) {
        return new NewScheduleException(
                UUID.randomUUID(),
                businessId,
                ScheduleExceptionContent.businessClosureDays(first, last),
                CREATED_AT);
    }

    private static ScheduleExceptionContent partial(LocalDate date, List<LocalPeriod> periods) {
        return ScheduleExceptionContent.businessClosurePartial(date, periods);
    }

    private static LocalPeriod period(String start, String end) {
        return new LocalPeriod(LocalTime.parse(start), LocalTime.parse(end));
    }

    private long exceptionRows() {
        return jdbc.sql("SELECT count(*) FROM schedule_exception").query(Long.class).single();
    }

    private long periodRows(UUID exceptionId) {
        return jdbc.sql("SELECT count(*) FROM schedule_exception_period WHERE exception_id = :id")
                .param("id", exceptionId)
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
                            :id, :slug, 'Schedule Exception Race Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "exception-race-" + id)
                .param("now", now)
                .update();
        return id;
    }
}
