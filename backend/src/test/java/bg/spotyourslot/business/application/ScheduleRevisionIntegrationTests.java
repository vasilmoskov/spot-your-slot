package bg.spotyourslot.business.application;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The schedule revision bump and the booking-facing guard against real PostgreSQL (ADR-0025,
 * Issue #18 Phase 3). The coordinated transactions here use the contracts directly, with a
 * transaction standing in for a booking; the real booking orchestration races belong to Phase 4.
 * Every race is ordered with latches and PostgreSQL's own report of which backend blocks which;
 * there are no sleeps.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleRevisionIntegrationTests extends PostgresIntegrationTest {
    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-09-01T08:00:00Z");

    @Autowired ScheduleRevisionBump bump;
    @Autowired ScheduleRevisionGuard guard;
    @Autowired BusinessAdministration businesses;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    // ---- initialization ------------------------------------------------------

    @Test
    void aBusinessCreatedThroughTheApplicationHasItsRevisionRowAtZero() {
        var created = businesses.create(new CreateBusinessCommand(
                "revision-created",
                "Revision Created",
                BusinessType.OTHER,
                "Europe/Sofia",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        assertThat(revisionOf(created.id())).isZero();
        assertThat(jdbc.sql("SELECT updated_at = :createdAt FROM business_schedule_revision "
                                + "WHERE business_id = :id")
                        .param("createdAt", OffsetDateTime.ofInstant(
                                created.createdAt(), ZoneOffset.UTC))
                        .param("id", created.id())
                        .query(Boolean.class).single()).isTrue();
    }

    // ---- bump ----------------------------------------------------------------

    @Test
    void everyBumpAddsExactlyOneAndReturnsTheNewRevision() {
        UUID business = business();

        List<Long> returned = transaction().execute(status ->
                List.of(bump.advance(business), bump.advance(business), bump.advance(business)));

        assertThat(returned).containsExactly(1L, 2L, 3L);
        assertThat(revisionOf(business)).isEqualTo(3L);
    }

    @Test
    void aBumpOfOneBusinessNeverChangesAnotherBusinessOrItsTimestamp() {
        UUID first = business();
        UUID second = business();
        OffsetDateTime secondStamp = updatedAtOf(second);

        transaction().executeWithoutResult(status -> bump.advance(first));

        assertThat(revisionOf(first)).isEqualTo(1L);
        assertThat(revisionOf(second)).isZero();
        assertThat(updatedAtOf(second)).isEqualTo(secondStamp);
        assertThat(updatedAtOf(first)).isAfter(CREATED);
    }

    @Test
    void aRolledBackTransactionLeavesTheRevisionUnchanged() {
        UUID business = business();

        transaction().executeWithoutResult(status -> {
            assertThat(bump.advance(business)).isEqualTo(1L);
            status.setRollbackOnly();
        });

        assertThat(revisionOf(business)).isZero();
    }

    @Test
    void aMissingRevisionRowOrBusinessIsASanitizedFailureNeverASilentSuccess() {
        UUID withoutRow = business();
        jdbc.sql("DELETE FROM business_schedule_revision WHERE business_id = :id")
                .param("id", withoutRow)
                .update();
        UUID unknown = UUID.randomUUID();

        for (UUID id : List.of(withoutRow, unknown)) {
            Throwable advance = captureFailure(() ->
                    transaction().executeWithoutResult(status -> bump.advance(id)));
            Throwable lock = captureFailure(() ->
                    snapshotTransaction().executeWithoutResult(status -> guard.lockShared(id)));
            for (Throwable failure : List.of(advance, lock)) {
                assertThat(failure).isExactlyInstanceOf(ScheduleRevisionFailure.class);
                assertThat(failure.getMessage()).doesNotContain(id.toString());
                assertThat(failure.getCause()).isNull();
                assertThat(failure.getSuppressed()).isEmpty();
            }
        }
        assertThat(count("business_schedule_revision")).isZero();
    }

    @Test
    void bothOperationsRequireACallerOwnedTransactionAndNeverOpenOne() {
        UUID business = business();

        assertThatThrownBy(() -> bump.advance(business))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> guard.lockShared(business))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(revisionOf(business)).isZero();
    }

    // ---- guard preconditions -------------------------------------------------

    @Test
    void theGuardRequiresARepeatableReadOrSerializableTransaction() {
        UUID business = business();

        for (int weak : List.of(
                TransactionDefinition.ISOLATION_DEFAULT,
                TransactionDefinition.ISOLATION_READ_COMMITTED)) {
            TransactionTemplate template = transaction();
            template.setIsolationLevel(weak);
            assertThat(captureFailure(() -> template.executeWithoutResult(
                            status -> guard.lockShared(business))))
                    .as("isolation %d", weak)
                    .isExactlyInstanceOf(ScheduleRevisionFailure.class);
        }
        for (int snapshot : List.of(
                TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            TransactionTemplate template = transaction();
            template.setIsolationLevel(snapshot);
            Long revision = template.execute(status -> guard.lockShared(business));
            assertThat(revision).isZero();
        }
    }

    @Test
    void theBumpWorksInTheDefaultIsolationWhereMutationsRun() {
        UUID business = business();
        TransactionTemplate template = transaction();
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);

        Long revision = template.execute(status -> bump.advance(business));
        assertThat(revision).isEqualTo(1L);
    }

    // ---- lock conflicts and the ADR-0025 snapshot guarantee -------------------

    @Test
    void sharedGuardsNeverBlockEachOther() {
        UUID business = business();
        CountDownLatch firstHolding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Long> first = CompletableFuture.supplyAsync(
                    () -> snapshotTransaction().execute(status -> {
                        long revision = guard.lockShared(business);
                        firstHolding.countDown();
                        await(release, "first guard was not released");
                        return revision;
                    }),
                    executor);
            await(firstHolding, "first guard did not lock");

            // The second guard finishes while the first still holds its lock: no wait occurred.
            CompletableFuture<Long> second = CompletableFuture.supplyAsync(
                    () -> snapshotTransaction().execute(status -> guard.lockShared(business)),
                    executor);
            long secondRevision = completed(second);
            assertThat(secondRevision).isZero();
            assertThat(first).isNotDone();
            release.countDown();
            long firstRevision = completed(first);
            assertThat(firstRevision).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void aHeldGuardBlocksALaterBumpUntilItCommitsSoTheBumpCommitsSecond() {
        UUID business = business();
        List<String> commits = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger guardPid = new AtomicInteger();
        AtomicInteger bumpPid = new AtomicInteger();
        CountDownLatch bumpAttempted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Long> holder = CompletableFuture.supplyAsync(
                    () -> snapshotTransaction().execute(status -> {
                        recordCommit(commits, "GUARD");
                        guardPid.set(backendPid(jdbc));
                        long revision = guard.lockShared(business);
                        holding.countDown();
                        await(release, "guard was not released");
                        return revision;
                    }),
                    executor);
            await(holding, "guard did not lock");

            CompletableFuture<Long> bumper = CompletableFuture.supplyAsync(
                    () -> transaction().execute(status -> {
                        recordCommit(commits, "BUMP");
                        bumpPid.set(backendPid(jdbc));
                        bumpAttempted.countDown();
                        return bump.advance(business);
                    }),
                    executor);
            await(bumpAttempted, "bump did not start");
            awaitBlockedBy(jdbc, bumpPid.get(), guardPid.get());
            assertThat(bumper).isNotDone();
            assertThat(revisionOf(business)).isZero();
            release.countDown();

            long holderRevision = completed(holder);
            long bumperRevision = completed(bumper);
            assertThat(holderRevision).isZero();
            assertThat(bumperRevision).isEqualTo(1L);
            assertThat(commits).containsExactly("GUARD", "BUMP");
            assertThat(revisionOf(business)).isEqualTo(1L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void anUncommittedBumpBlocksTheGuardAndACommitThenFailsItAgainstItsOlderSnapshot() {
        UUID business = business();
        Race race = raceGuardAgainstInFlightBump(business, false);

        assertThat(race.guardOutcome()).isExactlyInstanceOf(ScheduleRevisionConcurrentConflict.class);
        assertThat(race.guardOutcome().getCause()).isNull();
        assertThat(race.guardOutcome().getMessage()).doesNotContain(business.toString());
        assertThat(revisionOf(business)).isEqualTo(1L);
    }

    @Test
    void anUncommittedBumpThatRollsBackLetsTheWaitingGuardSucceedAtTheOldRevision() {
        UUID business = business();
        Race race = raceGuardAgainstInFlightBump(business, true);

        assertThat(race.guardOutcome()).isNull();
        assertThat(race.guardRevision()).isZero();
        assertThat(revisionOf(business)).isZero();
    }

    @Test
    void aGuardWhoseSnapshotPredatesACommittedBumpFailsWithoutWaiting() {
        UUID business = business();
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch bumpCommitted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            CompletableFuture<Throwable> booking = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> snapshotTransaction().executeWithoutResult(status -> {
                        // The first statement fixes the repeatable-read snapshot.
                        assertThat(revisionOf(business)).isZero();
                        snapshotTaken.countDown();
                        await(bumpCommitted, "the bump did not commit");
                        guard.lockShared(business);
                    })),
                    executor);
            await(snapshotTaken, "the snapshot was not taken");

            transaction().executeWithoutResult(status -> bump.advance(business));
            bumpCommitted.countDown();

            assertThat(completed(booking)).isExactlyInstanceOf(ScheduleRevisionConcurrentConflict.class);
        } finally {
            bumpCommitted.countDown();
        }
    }

    @Test
    void aGuardWhoseSnapshotFollowsTheCommittedBumpSeesItAndSucceeds() {
        UUID business = business();
        transaction().executeWithoutResult(status -> bump.advance(business));

        Long revision = snapshotTransaction().execute(status -> guard.lockShared(business));
        assertThat(revision).isEqualTo(1L);
    }

    @Test
    void aBumpOfAnotherBusinessNeverFailsOrBlocksTheGuard() {
        UUID guarded = business();
        UUID other = business();
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            CompletableFuture<Long> booking = CompletableFuture.supplyAsync(
                    () -> snapshotTransaction().execute(status -> {
                        assertThat(revisionOf(guarded)).isZero();
                        snapshotTaken.countDown();
                        await(otherCommitted, "the other Business was not bumped");
                        return guard.lockShared(guarded);
                    }),
                    executor);
            await(snapshotTaken, "the snapshot was not taken");
            transaction().executeWithoutResult(status -> bump.advance(other));
            otherCommitted.countDown();

            long bookingRevision = completed(booking);
            assertThat(bookingRevision).isZero();
            assertThat(revisionOf(other)).isEqualTo(1L);
        } finally {
            otherCommitted.countDown();
        }
    }

    @Test
    void concurrentBumpsOfOneBusinessSerializeAndBothSucceedInTheDefaultIsolation() {
        UUID business = business();
        CountDownLatch firstBumped = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Long> first = CompletableFuture.supplyAsync(
                    () -> transaction().execute(status -> {
                        firstPid.set(backendPid(jdbc));
                        long revision = bump.advance(business);
                        firstBumped.countDown();
                        await(release, "first bump was not released");
                        return revision;
                    }),
                    executor);
            await(firstBumped, "first bump did not run");

            CompletableFuture<Long> second = CompletableFuture.supplyAsync(
                    () -> transaction().execute(status -> {
                        secondPid.set(backendPid(jdbc));
                        secondStarted.countDown();
                        return bump.advance(business);
                    }),
                    executor);
            await(secondStarted, "second bump did not start");
            awaitBlockedBy(jdbc, secondPid.get(), firstPid.get());
            release.countDown();

            long firstRevision = completed(first);
            long secondRevision = completed(second);
            assertThat(firstRevision).isEqualTo(1L);
            assertThat(secondRevision).isEqualTo(2L);
            assertThat(revisionOf(business)).isEqualTo(2L);
        } finally {
            release.countDown();
        }
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * A repeatable-read stand-in for a booking takes its snapshot, a mutation then holds an
     * uncommitted bump, and the stand-in asks for the guard and is observed blocked by it before
     * the mutation commits or rolls back.
     */
    private Race raceGuardAgainstInFlightBump(UUID business, boolean rollbackBump) {
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch bumped = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch guardAttempting = new CountDownLatch(1);
        AtomicInteger guardPid = new AtomicInteger();
        AtomicInteger bumpPid = new AtomicInteger();
        AtomicLong guardRevision = new AtomicLong(-1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Throwable> booking = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> snapshotTransaction().executeWithoutResult(status -> {
                        guardPid.set(backendPid(jdbc));
                        snapshotTaken.countDown();
                        await(bumped, "the bump did not run");
                        guardAttempting.countDown();
                        guardRevision.set(guard.lockShared(business));
                    })),
                    executor);
            await(snapshotTaken, "the snapshot was not taken");

            CompletableFuture<Void> mutation = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        bumpPid.set(backendPid(jdbc));
                        bump.advance(business);
                        bumped.countDown();
                        await(release, "the bump was not released");
                        if (rollbackBump) {
                            status.setRollbackOnly();
                        }
                    }),
                    executor);
            await(guardAttempting, "the guard did not start");
            awaitBlockedBy(jdbc, guardPid.get(), bumpPid.get());
            assertThat(booking).isNotDone();
            release.countDown();

            completed(mutation);
            return new Race(completed(booking), guardRevision.get());
        } finally {
            release.countDown();
        }
    }

    private record Race(Throwable guardOutcome, long guardRevision) {
    }

    private static void recordCommit(List<String> commits, String name) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                commits.add(name);
            }
        });
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate snapshotTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        return template;
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private UUID business() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (:id, :slug, 'Revision Business', 'OTHER', 'ACTIVE', 'Europe/Sofia',
                                :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("slug", "revision-" + id)
                .param("createdAt", CREATED)
                .update();
        return id;
    }

    private long revisionOf(UUID businessId) {
        return jdbc.sql("SELECT revision FROM business_schedule_revision WHERE business_id = :id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    private OffsetDateTime updatedAtOf(UUID businessId) {
        return jdbc.sql("SELECT updated_at FROM business_schedule_revision WHERE business_id = :id")
                .param("id", businessId)
                .query(OffsetDateTime.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
