package bg.spotyourslot.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessSuspended;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ConcurrentUpdate;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.OverlapConflict;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberInactive;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionDetails;
import bg.spotyourslot.scheduling.application.ScheduleExceptionTestSupport.Fixture;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess;
import bg.spotyourslot.workforce.infrastructure.StaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the Business, Membership, StaffMember, schedule revision, aggregate lock
 * order and the schedule-exception races. Every mutation of one Business advances
 * its schedule revision (ADR-0025) before the aggregate write, so concurrent
 * writers of one Business wait on the revision row first and meet the aggregate
 * constraints only after the earlier writer has finished. Every race is
 * coordinated with latches and PostgreSQL lock-wait observation; there are no
 * sleeps.
 */
@Import(ScheduleExceptionLockingIntegrationTests.LockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleExceptionLockingIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);

    @Autowired ScheduleExceptionAdministration exceptions;
    @Autowired StaffMemberAdministration staffMembers;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LockObservation observation;
    @Autowired ObservingStore store;
    @Autowired PausingStaffMemberStore pausingStaffStore;
    @Autowired ObservingBusinessAccess businessAccess;
    @Autowired ObservingStaffReferenceAccess staffReferenceAccess;
    @Autowired ObservingRevisionBump revisionBump;

    private ScheduleExceptionTestSupport support;

    @BeforeEach
    void setUp() {
        support = new ScheduleExceptionTestSupport(jdbc);
        store.disarmAll();
        businessAccess.disarm();
        staffReferenceAccess.disarm();
        revisionBump.disarm();
        pausingStaffStore.disarmDeactivate();
    }

    @Test
    void staffScopedMutationsLockBusinessThenMembershipThenStaffMemberThenAggregate() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);

        observation.clear();
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), timeOff(staffMemberId, DATE)).exception();
        assertOrder(List.of("BUSINESS", "MEMBERSHIP", "STAFF_MEMBER", "SCHEDULE_REVISION", "AGGREGATE"));

        observation.clear();
        exceptions.replace(
                fixture.context(),
                created.id(),
                new ReplaceScheduleExceptionCommand(0L, DATE, DATE.plusDays(1), true, List.of()));
        assertOrder(List.of("BUSINESS", "MEMBERSHIP", "STAFF_MEMBER", "SCHEDULE_REVISION", "AGGREGATE"));

        observation.clear();
        exceptions.delete(fixture.context(), created.id(), 1L);
        assertOrder(List.of("BUSINESS", "MEMBERSHIP", "STAFF_MEMBER", "SCHEDULE_REVISION", "AGGREGATE"));
    }

    @Test
    void closureMutationsNeverLockAStaffMember() {
        Fixture fixture = support.fixture("ACTIVE");

        observation.clear();
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), closure(DATE, DATE)).exception();
        assertOrder(List.of("BUSINESS", "MEMBERSHIP", "SCHEDULE_REVISION", "AGGREGATE"));

        observation.clear();
        exceptions.replace(
                fixture.context(),
                created.id(),
                new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of()));
        assertOrder(List.of("BUSINESS", "MEMBERSHIP", "SCHEDULE_REVISION", "AGGREGATE"));

        observation.clear();
        exceptions.delete(fixture.context(), created.id(), 1L);
        assertOrder(List.of("BUSINESS", "MEMBERSHIP", "SCHEDULE_REVISION", "AGGREGATE"));
    }

    @Test
    void readsAcquireNoBusinessMembershipOrStaffMemberLock() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), timeOff(staffMemberId, DATE)).exception();

        observation.clear();
        exceptions.get(fixture.context(), created.id());
        exceptions.list(fixture.context(), DATE, DATE);

        assertThat(observation.events()).isEmpty();
    }

    @Test
    void sameVersionReplacementsHaveExactlyOneWinnerAndNeverAMixedPeriodSet() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(),
                new CreateScheduleExceptionCommand(
                        ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS, staffMemberId, DATE, DATE,
                        false, List.of(period(6, 0, 7, 0)))).exception();
        ReplaceScheduleExceptionCommand first = new ReplaceScheduleExceptionCommand(
                0L, DATE, DATE, false, List.of(period(9, 0, 12, 0), period(13, 0, 14, 0)));
        ReplaceScheduleExceptionCommand second = new ReplaceScheduleExceptionCommand(
                0L, DATE, DATE, false, List.of(period(15, 0, 16, 0)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Throwable> left = CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(start, "replacement was not released");
                return captureFailure(() -> exceptions.replace(
                        fixture.context(), created.id(), first));
            }, executor);
            CompletableFuture<Throwable> right = CompletableFuture.supplyAsync(() -> {
                ready.countDown();
                await(start, "replacement was not released");
                return captureFailure(() -> exceptions.replace(
                        fixture.context(), created.id(), second));
            }, executor);
            await(ready, "concurrent replacements did not become ready");
            start.countDown();

            List<Throwable> outcomes = Arrays.asList(completed(left), completed(right));
            assertThat(outcomes).filteredOn(failure -> failure == null).hasSize(1);
            assertThat(outcomes).filteredOn(failure -> failure instanceof ConcurrentUpdate)
                    .hasSize(1);
            ScheduleExceptionDetails stored = exceptions.get(
                    fixture.context(), created.id()).exception();
            assertThat(stored.version()).isEqualTo(1);
            assertThat(stored.periods()).isIn(first.periods(), second.periods());
            // One creation and the one winning replacement; the loser's bump rolled back.
            assertThat(support.revision(fixture.businessId())).isEqualTo(2L);
            assertThat(support.periodCount(created.id())).isEqualTo(stored.periods().size());
        }
    }

    @Test
    void conflictingCreateWaitsForTheFirstAndIsRejectedWhenTheFirstCommits() {
        Fixture fixture = support.fixture("ACTIVE");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> first = holdOpen(executor, inserted, release, false, () ->
                    exceptions.create(fixture.context(), closure(DATE, DATE.plusDays(1))));
            await(inserted, "first create did not insert");

            Probe probe = revisionBump.arm();
            CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.create(
                            fixture.context(), closure(DATE.plusDays(1), DATE.plusDays(2)))),
                    executor);
            await(probe.attempted(), "second create did not reach the revision lock");
            assertLockWait(probe.backendPid());
            assertThat(second).isNotCompleted();
            release.countDown();

            completed(first);
            assertThat(completed(second)).isInstanceOf(OverlapConflict.class);
            assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(1);
            // The rejected creation, which had advanced the revision before the insert failed,
            // rolled that bump back with it.
            assertThat(support.revision(fixture.businessId())).isEqualTo(1L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void conflictingCreateSucceedsWhenTheFirstRollsBack() {
        Fixture fixture = support.fixture("ACTIVE");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> first = holdOpen(executor, inserted, release, true, () ->
                    exceptions.create(fixture.context(), closure(DATE, DATE.plusDays(1))));
            await(inserted, "first create did not insert");

            Probe probe = revisionBump.arm();
            CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.create(
                            fixture.context(), closure(DATE.plusDays(1), DATE.plusDays(2)))),
                    executor);
            await(probe.attempted(), "second create did not reach the revision lock");
            assertLockWait(probe.backendPid());
            release.countDown();

            completed(first);
            assertThat(completed(second)).isNull();
            assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(1);
            // The first creation rolled back with its bump; only the second advanced the revision.
            assertThat(support.revision(fixture.businessId())).isEqualTo(1L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void deleteBehindAnUncommittedReplaceIsAConcurrentUpdateAndTheReplaceSurvives() {
        Fixture fixture = support.fixture("ACTIVE");
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), closure(DATE, DATE)).exception();
        CountDownLatch replaced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> replacement = holdOpen(executor, replaced, release, false, () ->
                    exceptions.replace(
                            fixture.context(),
                            created.id(),
                            new ReplaceScheduleExceptionCommand(
                                    0L, DATE, DATE.plusDays(1), true, List.of())));
            await(replaced, "replacement did not write");

            Probe probe = revisionBump.arm();
            CompletableFuture<Throwable> deletion = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.delete(
                            fixture.context(), created.id(), 0L)),
                    executor);
            await(probe.attempted(), "deletion did not reach the revision lock");
            assertLockWait(probe.backendPid());
            release.countDown();

            completed(replacement);
            assertThat(completed(deletion)).isInstanceOf(ConcurrentUpdate.class);
            assertThat(support.storedVersion(created.id())).isEqualTo(1);
            // Creation and replacement advanced it; the losing deletion's bump rolled back.
            assertThat(support.revision(fixture.businessId())).isEqualTo(2L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void replaceBehindAnUncommittedDeleteIsAConcurrentUpdateNotNotFound() {
        Fixture fixture = support.fixture("ACTIVE");
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), closure(DATE, DATE)).exception();
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> deletion = holdOpen(executor, deleted, release, false, () ->
                    exceptions.delete(fixture.context(), created.id(), 0L));
            await(deleted, "deletion did not write");

            Probe probe = revisionBump.arm();
            CompletableFuture<Throwable> replacement = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.replace(
                            fixture.context(),
                            created.id(),
                            new ReplaceScheduleExceptionCommand(
                                    0L, DATE, DATE.plusDays(1), true, List.of()))),
                    executor);
            await(probe.attempted(), "replacement did not reach the revision lock");
            assertLockWait(probe.backendPid());
            release.countDown();

            completed(deletion);
            assertThat(completed(replacement)).isInstanceOf(ConcurrentUpdate.class);
            assertThat(support.exceptionCount(fixture.businessId())).isZero();
            // Creation and deletion advanced it; the losing replacement's bump rolled back.
            assertThat(support.revision(fixture.businessId())).isEqualTo(2L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void suspensionWaitsForAnInFlightMutationAndLaterMutationsAreRejected() {
        Fixture fixture = support.fixture("ACTIVE");
        CountDownLatch mutated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger suspensionPid = new AtomicInteger();
        CountDownLatch suspensionAttempted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> mutation = holdOpen(executor, mutated, release, false, () ->
                    exceptions.create(fixture.context(), closure(DATE, DATE)));
            await(mutated, "mutation did not lock the Business");

            CompletableFuture<Void> suspension = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        suspensionPid.set(backendPid());
                        suspensionAttempted.countDown();
                        support.setBusinessStatus(fixture.businessId(), "SUSPENDED");
                    }),
                    executor);
            await(suspensionAttempted, "suspension did not start");
            assertLockWait(suspensionPid);
            assertThat(suspension).isNotCompleted();
            release.countDown();

            completed(mutation);
            completed(suspension);
            assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(1);
            assertThatThrownBy(() -> exceptions.create(
                            fixture.context(), closure(DATE.plusDays(10), DATE.plusDays(10))))
                    .isInstanceOf(BusinessSuspended.class);
        } finally {
            release.countDown();
        }
    }

    @Test
    void suspensionOwningTheBusinessRowFirstBlocksAndRejectsTheMutation() {
        Fixture fixture = support.fixture("ACTIVE");
        CountDownLatch suspended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Probe probe = businessAccess.arm();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> suspension = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        support.setBusinessStatus(fixture.businessId(), "SUSPENDED");
                        suspended.countDown();
                        await(release, "suspension was not released");
                    }),
                    executor);
            await(suspended, "suspension did not write");

            CompletableFuture<Throwable> mutation = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.create(
                            fixture.context(), closure(DATE, DATE))),
                    executor);
            await(probe.attempted(), "mutation did not reach the Business lock");
            assertLockWait(probe.backendPid());
            assertThat(mutation).isNotCompleted();
            release.countDown();

            completed(suspension);
            assertThat(completed(mutation)).isInstanceOf(BusinessSuspended.class);
            assertThat(support.exceptionCount(fixture.businessId())).isZero();
        } finally {
            release.countDown();
            businessAccess.disarm();
        }
    }

    @Test
    void deactivationOwningTheStaffMemberRowFirstBlocksAndRejectsTheMutation() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Probe probe = staffReferenceAccess.arm();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> deactivation = holdOpen(executor, deactivated, release, false, () ->
                    staffMembers.deactivate(
                            fixture.context(), staffMemberId, new StaffMemberVersionCommand(0L)));
            await(deactivated, "deactivation did not write");

            CompletableFuture<Throwable> mutation = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.create(
                            fixture.context(), timeOff(staffMemberId, DATE))),
                    executor);
            await(probe.attempted(), "mutation did not reach the StaffMember lock");
            assertLockWait(probe.backendPid());
            assertThat(mutation).isNotCompleted();
            release.countDown();

            completed(deactivation);
            assertThat(completed(mutation)).isInstanceOf(StaffMemberInactive.class);
            assertThat(support.exceptionCount(fixture.businessId())).isZero();
        } finally {
            release.countDown();
            staffReferenceAccess.disarm();
        }
    }

    @Test
    void mutationOwningTheStaffMemberLockFirstBlocksDeactivationThenBothComplete() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        CountDownLatch mutated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> mutation = holdOpen(executor, mutated, release, false, () ->
                    exceptions.create(fixture.context(), timeOff(staffMemberId, DATE)));
            await(mutated, "mutation did not lock the StaffMember");

            Probe probe = pausingStaffStore.armDeactivate();
            CompletableFuture<Void> deactivation = CompletableFuture.runAsync(
                    () -> staffMembers.deactivate(
                            fixture.context(), staffMemberId, new StaffMemberVersionCommand(0L)),
                    executor);
            await(probe.attempted(), "deactivation did not attempt the StaffMember write lock");
            assertLockWait(probe.backendPid());
            assertThat(deactivation).isNotCompleted();
            release.countDown();

            completed(mutation);
            completed(deactivation);
            assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(1);
            assertThat(staffMembers.get(fixture.context(), staffMemberId).active()).isFalse();
        } finally {
            release.countDown();
            pausingStaffStore.disarmDeactivate();
        }
    }

    @Test
    void oppositeOrderReplacementsOfOneBusinessSerializeBehindTheRevisionInsteadOfDeadlocking() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails a = exceptions.create(
                fixture.context(), timeOffDays(staffMemberId, DATE, DATE)).exception();
        ScheduleExceptionDetails b = exceptions.create(
                fixture.context(), timeOffDays(staffMemberId, DATE.plusDays(1), DATE.plusDays(1)))
                .exception();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondBlocked = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> transaction().executeWithoutResult(status -> {
                        exceptions.replace(
                                fixture.context(),
                                a.id(),
                                new ReplaceScheduleExceptionCommand(
                                        0L, DATE.plusDays(2), DATE.plusDays(2), true, List.of()));
                        firstDone.countDown();
                        await(secondBlocked, "second replacement was not blocked");
                        exceptions.replace(
                                fixture.context(),
                                b.id(),
                                new ReplaceScheduleExceptionCommand(
                                        0L, DATE.plusDays(3), DATE.plusDays(3), true, List.of()));
                    })),
                    executor);
            await(firstDone, "first replacement did not write");

            Probe probe = revisionBump.arm();
            CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.replace(
                            fixture.context(),
                            b.id(),
                            new ReplaceScheduleExceptionCommand(
                                    0L, DATE, DATE, true, List.of()))),
                    executor);
            await(probe.attempted(), "second replacement did not reach the revision lock");
            assertLockWait(probe.backendPid());
            secondBlocked.countDown();

            assertThat(completed(first)).isNull();
            assertThat(completed(second)).isInstanceOf(ConcurrentUpdate.class);
            assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(2);
            assertThat(support.storedVersion(a.id())).isEqualTo(1);
            assertThat(support.storedVersion(b.id())).isEqualTo(1);
            assertThat(overlappingPairs(fixture.businessId())).isZero();
            // Two creations and the winner's two replacements; the loser's bump rolled back.
            assertThat(support.revision(fixture.businessId())).isEqualTo(4L);
        } finally {
            secondBlocked.countDown();
        }
    }

    @Test
    void aLockOrderViolatingCallerDeadlocksOnTheRevisionAndTheVictimIsAConcurrentUpdate() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        CountDownLatch bumped = new CountDownLatch(1);
        CountDownLatch secondWaiting = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            // The first caller owns the revision row and then asks for the StaffMember row, which
            // is the reverse of the approved order; the mutation itself never does this.
            CompletableFuture<Throwable> first = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> transaction().executeWithoutResult(status -> {
                        // PostgreSQL aborts whichever participant's deadlock check finds the cycle
                        // first, which depends on timer timing and not on who waited first (a
                        // pre-existing intermittent failure when the first transaction was chosen). A long
                        // deadlock_timeout for this transaction (a superuser setting) leaves the second
                        // transaction as the only one that can run the check, so the victim is fixed.
                        jdbc.sql("SET LOCAL deadlock_timeout = '60s'").update();
                        exceptions.create(fixture.context(), closure(DATE, DATE));
                        bumped.countDown();
                        await(secondWaiting, "second create was not waiting on the revision");
                        staffMembers.deactivate(
                                fixture.context(), staffMemberId, new StaffMemberVersionCommand(0L));
                    })),
                    executor);
            await(bumped, "first create did not advance the revision");

            Probe probe = revisionBump.arm();
            CompletableFuture<Throwable> second = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> exceptions.create(
                            fixture.context(), timeOff(staffMemberId, DATE.plusDays(5)))),
                    executor);
            await(probe.attempted(), "second create did not reach the revision lock");
            assertLockWait(probe.backendPid());
            secondWaiting.countDown();

            // The second transaction began waiting first, so PostgreSQL's deadlock check runs in it
            // and it is the victim; the first transaction then completes.
            assertThat(completed(second)).isInstanceOf(ConcurrentUpdate.class);
            assertThat(completed(first)).isNull();
            assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(1);
            assertThat(staffMembers.get(fixture.context(), staffMemberId).active()).isFalse();
        } finally {
            secondWaiting.countDown();
        }
    }

    private long overlappingPairs(UUID businessId) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM schedule_exception x
                        JOIN schedule_exception y
                          ON x.business_id = y.business_id
                         AND x.id < y.id
                         AND x.kind = y.kind
                         AND x.staff_member_id IS NOT DISTINCT FROM y.staff_member_id
                         AND x.date_range && y.date_range
                        WHERE x.business_id = :id
                        """)
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    private void assertOrder(List<String> expected) {
        assertThat(observation.events()).containsExactlyElementsOf(expected);
        assertThat(observation.backendPids()).hasSize(1);
    }

    /** Runs the action in an open transaction, signals, then holds it until released. */
    private CompletableFuture<Void> holdOpen(
            ExecutorService executor,
            CountDownLatch acted,
            CountDownLatch release,
            boolean rollback,
            Runnable action) {
        return CompletableFuture.runAsync(
                () -> transaction().executeWithoutResult(status -> {
                    action.run();
                    acted.countDown();
                    await(release, "transaction was not released");
                    if (rollback) {
                        status.setRollbackOnly();
                    }
                }),
                executor);
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private void assertLockWait(AtomicInteger backendPid) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            int pid = backendPid.get();
            if (pid > 0) {
                Optional<String> wait = jdbc.sql("""
                                SELECT wait_event_type
                                FROM pg_stat_activity
                                WHERE pid=:pid AND wait_event_type='Lock'
                                """)
                        .param("pid", pid)
                        .query(String.class)
                        .optional();
                if (wait.isPresent()) {
                    assertThat(wait).contains("Lock");
                    return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("PostgreSQL lock wait was not observed");
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }

    private <T> T completed(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent operation was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("concurrent operation did not complete", exception);
        }
    }

    private static CreateScheduleExceptionCommand closure(LocalDate first, LocalDate last) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, first, last, true, List.of());
    }

    private static CreateScheduleExceptionCommand timeOff(UUID staffMemberId, LocalDate date) {
        return timeOffDays(staffMemberId, date, date);
    }

    private static CreateScheduleExceptionCommand timeOffDays(
            UUID staffMemberId, LocalDate first, LocalDate last) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.STAFF_TIME_OFF, staffMemberId, first, last, true, List.of());
    }

    private static ExceptionPeriod period(int startHour, int startMinute, int endHour, int endMinute) {
        return new ExceptionPeriod(
                LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }

    record Probe(CountDownLatch attempted, AtomicInteger backendPid) {
        static Probe create() {
            return new Probe(new CountDownLatch(1), new AtomicInteger());
        }

        void hit(int pid) {
            backendPid.set(pid);
            attempted.countDown();
        }
    }

    static final class LockObservation {
        private final List<String> events = new ArrayList<>();
        private final Set<Integer> backendPids = new HashSet<>();

        synchronized void record(String event, int backendPid) {
            events.add(event);
            backendPids.add(backendPid);
        }

        synchronized void clear() {
            events.clear();
            backendPids.clear();
        }

        synchronized List<String> events() {
            return List.copyOf(events);
        }

        synchronized Set<Integer> backendPids() {
            return Collections.unmodifiableSet(new HashSet<>(backendPids));
        }
    }

    static final class ObservingBusinessAccess implements BusinessScheduleContextAccess {
        private final BusinessScheduleContextAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        ObservingBusinessAccess(
                BusinessScheduleContextAccess delegate, JdbcClient jdbc, LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        Probe arm() {
            Probe value = Probe.create();
            probe.set(value);
            return value;
        }

        void disarm() {
            probe.set(null);
        }

        @Override
        public Optional<BusinessScheduleContext> findScheduleContext(UUID businessId) {
            return delegate.findScheduleContext(businessId);
        }

        @Override
        public Optional<BusinessScheduleContext> lockScheduleContext(UUID businessId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("BUSINESS", pid);
            Probe current = probe.getAndSet(null);
            if (current != null) {
                current.hit(pid);
            }
            return delegate.lockScheduleContext(businessId);
        }
    }

    static final class ObservingOwnerAccess implements SelectedBusinessOwnerAccess {
        private final SelectedBusinessOwnerAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;

        ObservingOwnerAccess(
                SelectedBusinessOwnerAccess delegate, JdbcClient jdbc, LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        @Override
        public Authorization authorize(UUID userId, UUID businessId) {
            return delegate.authorize(userId, businessId);
        }

        @Override
        public Authorization lockAndAuthorize(UUID userId, UUID businessId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("MEMBERSHIP", pid);
            return delegate.lockAndAuthorize(userId, businessId);
        }
    }

    static final class ObservingStaffReferenceAccess implements StaffMemberReferenceAccess {
        private final StaffMemberReferenceAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        ObservingStaffReferenceAccess(
                StaffMemberReferenceAccess delegate, JdbcClient jdbc, LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        Probe arm() {
            Probe value = Probe.create();
            probe.set(value);
            return value;
        }

        void disarm() {
            probe.set(null);
        }

        @Override
        public Optional<StaffMemberReference> lockReference(UUID businessId, UUID staffMemberId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("STAFF_MEMBER", pid);
            Probe current = probe.getAndSet(null);
            if (current != null) {
                current.hit(pid);
            }
            return delegate.lockReference(businessId, staffMemberId);
        }
    }

    static final class ObservingRevisionBump implements ScheduleRevisionBump {
        private final ScheduleRevisionBump delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        ObservingRevisionBump(
                ScheduleRevisionBump delegate, JdbcClient jdbc, LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        Probe arm() {
            Probe value = Probe.create();
            probe.set(value);
            return value;
        }

        void disarm() {
            probe.set(null);
        }

        @Override
        public long advance(UUID businessId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("SCHEDULE_REVISION", pid);
            Probe current = probe.getAndSet(null);
            if (current != null) {
                current.hit(pid);
            }
            return delegate.advance(businessId);
        }
    }

    static class ObservingStore extends ScheduleExceptionStore {
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> insertProbe = new AtomicReference<>();
        private final AtomicReference<Probe> replaceProbe = new AtomicReference<>();
        private final AtomicReference<Probe> deleteProbe = new AtomicReference<>();

        ObservingStore(JdbcClient jdbc, LockObservation observation) {
            super(jdbc);
            this.jdbc = jdbc;
            this.observation = observation;
        }

        Probe armInsert() {
            Probe value = Probe.create();
            insertProbe.set(value);
            return value;
        }

        Probe armReplace() {
            Probe value = Probe.create();
            replaceProbe.set(value);
            return value;
        }

        Probe armDelete() {
            Probe value = Probe.create();
            deleteProbe.set(value);
            return value;
        }

        void disarmAll() {
            insertProbe.set(null);
            replaceProbe.set(null);
            deleteProbe.set(null);
        }

        private void observe(AtomicReference<Probe> probe) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("AGGREGATE", pid);
            Probe current = probe.getAndSet(null);
            if (current != null) {
                current.hit(pid);
            }
        }

        @Override
        public ScheduleException insert(NewScheduleException exception) {
            observe(insertProbe);
            return super.insert(exception);
        }

        @Override
        public Optional<ScheduleException> replace(
                UUID businessId,
                UUID id,
                long expectedVersion,
                ScheduleExceptionContent content,
                Instant updatedAt) {
            observe(replaceProbe);
            return super.replace(businessId, id, expectedVersion, content, updatedAt);
        }

        @Override
        public boolean delete(UUID businessId, UUID id, long expectedVersion) {
            observe(deleteProbe);
            return super.delete(businessId, id, expectedVersion);
        }
    }

    static class PausingStaffMemberStore extends StaffMemberStore {
        private final JdbcClient jdbc;
        private final AtomicReference<Probe> deactivateProbe = new AtomicReference<>();

        PausingStaffMemberStore(JdbcClient jdbc) {
            super(jdbc);
            this.jdbc = jdbc;
        }

        Probe armDeactivate() {
            Probe value = Probe.create();
            deactivateProbe.set(value);
            return value;
        }

        void disarmDeactivate() {
            deactivateProbe.set(null);
        }

        @Override
        public Optional<StaffMemberRow> deactivate(
                UUID businessId, UUID staffMemberId, long expectedVersion, Instant updatedAt) {
            Probe current = deactivateProbe.getAndSet(null);
            if (current != null) {
                current.hit(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
            }
            return super.deactivate(businessId, staffMemberId, expectedVersion, updatedAt);
        }
    }

    @TestConfiguration
    static class LockConfiguration {
        @Bean
        @Primary
        Clock fixedScheduleExceptionLockingClock() {
            return Clock.fixed(ScheduleExceptionTestSupport.NOW, ZoneOffset.UTC);
        }

        @Bean
        LockObservation scheduleExceptionLockObservation() {
            return new LockObservation();
        }

        @Bean
        @Primary
        ObservingStore observingScheduleExceptionStore(
                JdbcClient jdbc, LockObservation observation) {
            return new ObservingStore(jdbc, observation);
        }

        @Bean
        @Primary
        PausingStaffMemberStore pausingStaffMemberStore(JdbcClient jdbc) {
            return new PausingStaffMemberStore(jdbc);
        }

        @Bean
        @Primary
        ObservingBusinessAccess observingBusinessAccess(
                @Qualifier("businessAdministrationService") BusinessScheduleContextAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingBusinessAccess(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        ObservingOwnerAccess observingOwnerAccess(
                @Qualifier("businessAuthorizer") SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingOwnerAccess(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        ObservingRevisionBump observingRevisionBump(
                @Qualifier("scheduleRevisionService") ScheduleRevisionBump delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingRevisionBump(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        ObservingStaffReferenceAccess observingStaffReferenceAccess(
                @Qualifier("staffMemberReferenceAccessService") StaffMemberReferenceAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingStaffReferenceAccess(delegate, jdbc, observation);
        }
    }
}
