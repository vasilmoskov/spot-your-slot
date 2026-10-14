package bg.spotyourslot.booking.infrastructure;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentStatus;
import bg.spotyourslot.booking.domain.AppointmentTransitions;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Applied;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.NoteUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.RescheduleUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.StaffTarget;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Transition;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.ConcurrentFailure;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.OverlapConflict;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-PostgreSQL races of the Appointment updates (ADR-0029 sections 5 and 7). A first transaction
 * holds its write open on a latch; the second is proven by {@code pg_stat_activity} and
 * {@code pg_blocking_pids} to be waiting on exactly that transaction before the first commits or
 * rolls back. Snapshot-based cases order the transactions with latches alone. No sleeps and no timing
 * assumptions. Everything here is behavior PostgreSQL produces; nothing is injected.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentUpdateConcurrencyIntegrationTests extends PostgresIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");
    private static final int REPEATABLE_READ = Connection.TRANSACTION_REPEATABLE_READ;

    @Autowired
    AppointmentStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
    }

    private Appointment committed(Tenant forTenant, Instant start, int minutes) {
        return new TransactionTemplate(transactionManager).execute(status ->
                store.insert(AppointmentFixtures.manual(forTenant, start, minutes)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T applied(Transition<T> transition) {
        assertThat(transition).isInstanceOf(Applied.class);
        return ((Applied<T>) transition).update();
    }

    private RescheduleUpdate move(Appointment current, Instant start, StaffTarget target) {
        return applied(AppointmentTransitions.reschedule(current, current.version(), start, target, NOW));
    }

    private Tenant withStaff(UUID staff) {
        return new Tenant(tenant.business(), tenant.service(), staff, tenant.customer());
    }

    private String snapshotOf(UUID id) {
        return jdbc.sql("SELECT to_jsonb(a)::text || ' xmin=' || a.xmin::text FROM appointment a WHERE a.id = :id")
                .param("id", id).query(String.class).single();
    }

    private long blockingRows(UUID staff) {
        return jdbc.sql("SELECT count(*) FROM appointment WHERE staff_member_id = :staff AND status = 'CONFIRMED'")
                .param("staff", staff).query(Long.class).single();
    }

    // ---- time-changing UPDATE races ------------------------------------------

    @Test
    void twoMovesIntoTheSameIntervalHaveOneWinnerAndTheLoserWaitsThenIsRefusedUnchanged() {
        Appointment first = committed(tenant, START, 60);
        Appointment second = committed(tenant, START.plusSeconds(4 * 3600L), 60);
        Instant target = START.plusSeconds(2 * 3600L);
        RescheduleUpdate firstMove = move(first, target, null);
        RescheduleUpdate secondMove = move(second, target.plusSeconds(1800), null);
        String secondBefore = snapshotOf(second.id());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.reschedule(firstMove));
            race.startSecond(() -> store.reschedule(secondMove));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Optional.class);
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(store.find(tenant.business(), first.id()).orElseThrow().startAt()).isEqualTo(target);
        assertThat(snapshotOf(second.id())).isEqualTo(secondBefore);
        assertThat(blockingRows(tenant.staff())).isEqualTo(2L);
    }

    @Test
    void theSecondMoveSucceedsWhenTheFirstRollsBack() {
        Appointment first = committed(tenant, START, 60);
        Appointment second = committed(tenant, START.plusSeconds(4 * 3600L), 60);
        Instant target = START.plusSeconds(2 * 3600L);
        String firstBefore = snapshotOf(first.id());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.reschedule(move(first, target, null)));
            race.startSecond(() -> store.reschedule(move(second, target.plusSeconds(1800), null)));
            race.assertSecondWaitsOnTheFirst();

            race.rollBackFirst();

            assertThat(race.firstOutcome()).isInstanceOf(AppointmentRace.RollbackRequested.class);
            assertThat(race.secondOutcome()).isInstanceOf(Optional.class);
        }

        assertThat(snapshotOf(first.id())).isEqualTo(firstBefore);
        assertThat(store.find(tenant.business(), second.id()).orElseThrow().startAt())
                .isEqualTo(target.plusSeconds(1800));
    }

    @Test
    void aMoveWaitsForAnUncommittedInsertOfTheSameIntervalAndIsRefusedWhenItCommits() {
        Appointment moving = committed(tenant, START.plusSeconds(4 * 3600L), 60);
        String before = snapshotOf(moving.id());
        Instant contested = START.plusSeconds(2 * 3600L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.insert(AppointmentFixtures.online(tenant, contested, 60)));
            race.startSecond(() -> store.reschedule(move(moving, contested.plusSeconds(900), null)));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(snapshotOf(moving.id())).isEqualTo(before);
        assertThat(blockingRows(tenant.staff())).isEqualTo(2L);
    }

    @Test
    void anInsertWaitsForAnUncommittedMoveIntoItsIntervalAndIsRefusedWhenTheMoveCommits() {
        Appointment moving = committed(tenant, START.plusSeconds(4 * 3600L), 60);
        Instant contested = START.plusSeconds(2 * 3600L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.reschedule(move(moving, contested, null)));
            race.startSecond(() -> store.insert(
                    AppointmentFixtures.online(tenant, contested.plusSeconds(900), 60)));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Optional.class);
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(blockingRows(tenant.staff())).isEqualTo(1L);
    }

    // ---- StaffMember-changing UPDATE races -----------------------------------

    @Test
    void twoReassignmentsIntoTheSameStaffMembersIntervalHaveOneWinner() {
        UUID target = fixtures.staffMember(tenant.business(), tenant.service());
        Appointment first = committed(tenant, START, 60);
        Appointment second = committed(tenant, START.plusSeconds(4 * 3600L), 60);
        String secondBefore = snapshotOf(second.id());
        StaffTarget staff = new StaffTarget(target, "Иван Тестов");
        // Both appointments keep their own start; the second is moved to the first's time and then reassigned.
        Instant contested = START.plusSeconds(1800);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.reschedule(move(first, START, staff)));
            race.startSecond(() -> store.reschedule(move(second, contested, staff)));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Optional.class);
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(store.find(tenant.business(), first.id()).orElseThrow().staffMemberId()).isEqualTo(target);
        assertThat(snapshotOf(second.id())).isEqualTo(secondBefore);
        assertThat(blockingRows(target)).isEqualTo(1L);
        assertThat(blockingRows(tenant.staff())).isEqualTo(1L);
    }

    @Test
    void aReassignmentWaitsForAnUncommittedInsertForTheTargetAndIsRefusedWhenItCommits() {
        UUID target = fixtures.staffMember(tenant.business(), tenant.service());
        Appointment moving = committed(tenant, START, 60);
        String before = snapshotOf(moving.id());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.insert(AppointmentFixtures.online(withStaff(target), START, 60)));
            race.startSecond(() -> store.reschedule(
                    move(moving, START.plusSeconds(900), new StaffTarget(target, "Иван Тестов"))));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
            assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
        }

        assertThat(snapshotOf(moving.id())).isEqualTo(before);
    }

    @Test
    void aReassignmentIsNotBlockedByATransactionHoldingAnotherStaffMembersTime() {
        UUID target = fixtures.staffMember(tenant.business(), tenant.service());
        UUID unrelated = fixtures.staffMember(tenant.business(), tenant.service());
        Appointment moving = committed(tenant, START, 60);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.insert(AppointmentFixtures.online(withStaff(unrelated), START, 60)));
            race.startSecond(() -> store.reschedule(
                    move(moving, START, new StaffTarget(target, "Иван Тестов"))));

            // The second finishes while the first is still open: no lock wait exists.
            assertThat(race.secondOutcomeWhileFirstIsOpen()).isInstanceOf(Optional.class);
            race.commitFirst();
            assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
        }

        assertThat(store.find(tenant.business(), moving.id()).orElseThrow().staffMemberId())
                .isEqualTo(target);
    }

    // ---- optimistic version under snapshot isolation -------------------------

    @Test
    void aConditionalUpdateOfARowCommittedByAnotherTransactionAfterTheSnapshotFailsWith40001() {
        Appointment current = committed(tenant, START, 30);
        RescheduleUpdate stale = move(current, START.plusSeconds(900), null);
        NoteUpdate concurrent = applied(AppointmentTransitions.editInternalNote(
                current, current.version(), "паралелна бележка", NOW));
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);
        TransactionTemplate repeatableRead = new TransactionTemplate(transactionManager);
        repeatableRead.setIsolationLevel(REPEATABLE_READ);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CompletableFuture<Object> loser = CompletableFuture.supplyAsync(() -> {
                try {
                    return repeatableRead.execute(status -> {
                        // The first statement fixes the snapshot, which still shows version 0.
                        assertThat(store.find(current.businessId(), current.id()).orElseThrow().version())
                                .isZero();
                        snapshotTaken.countDown();
                        await(otherCommitted, "the other transaction did not commit");
                        return store.reschedule(stale);
                    });
                } catch (RuntimeException failure) {
                    return failure;
                }
            }, executor);
            await(snapshotTaken, "the snapshot was not taken");

            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> store.updateInternalNote(concurrent));
            otherCommitted.countDown();

            assertThat(completed(loser)).isInstanceOf(ConcurrentFailure.class);
        }

        Appointment now = store.find(current.businessId(), current.id()).orElseThrow();
        assertThat(now.version()).isEqualTo(1);
        assertThat(now.internalNote()).isEqualTo("паралелна бележка");
        assertThat(now.startAt()).isEqualTo(START);
    }

    @Test
    void aSnapshotUpdateWaitingOnTheRowLockFailsWith40001WhenTheHolderCommits() {
        Appointment current = committed(tenant, START, 30);
        NoteUpdate holder = applied(AppointmentTransitions.editInternalNote(
                current, current.version(), "държа реда", NOW));
        RescheduleUpdate stale = move(current, START.plusSeconds(900), null);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(
                    executor, transactionManager, jdbc,
                    TransactionTemplate.ISOLATION_DEFAULT, REPEATABLE_READ);
            race.holdFirst(() -> store.updateInternalNote(holder));
            race.startSecond(() -> store.reschedule(stale));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Optional.class);
            assertThat(race.secondOutcome()).isInstanceOf(ConcurrentFailure.class);
        }

        assertThat(store.find(current.businessId(), current.id()).orElseThrow().startAt()).isEqualTo(START);
    }

    @Test
    void theSameRaceAtReadCommittedMatchesNoRowInsteadOfFailing() {
        Appointment current = committed(tenant, START, 30);
        NoteUpdate holder = applied(AppointmentTransitions.editInternalNote(
                current, current.version(), "държа реда", NOW));
        RescheduleUpdate stale = move(current, START.plusSeconds(900), null);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.updateInternalNote(holder));
            race.startSecond(() -> store.reschedule(stale));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Optional.class);
            // PostgreSQL re-evaluates the version predicate against the committed row: no match.
            assertThat(race.secondOutcome()).isEqualTo(Optional.empty());
        }

        assertThat(store.find(current.businessId(), current.id()).orElseThrow().startAt()).isEqualTo(START);
    }

    // ---- cancellation races --------------------------------------------------

    @Test
    void twoCancellationsOfOneAppointmentHaveExactlyOneWinnerWhoseAttributionIsKept() {
        Appointment current = committed(tenant, START, 30);
        Instant firstInstant = NOW;
        Instant secondInstant = NOW.plusSeconds(5);
        CancelUpdate winner = new CancelUpdate(current.businessId(), current.id(), 0, firstInstant);
        CancelUpdate loser = new CancelUpdate(current.businessId(), current.id(), 0, secondInstant);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.cancel(winner));
            race.startSecond(() -> store.cancel(loser));
            race.assertSecondWaitsOnTheFirst();

            race.commitFirst();

            assertThat(((Optional<?>) race.firstOutcome())).isPresent();
            assertThat(race.secondOutcome()).isEqualTo(Optional.empty());
        }

        Appointment stored = store.find(current.businessId(), current.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(stored.cancelledAt()).isEqualTo(firstInstant);
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void theSecondCancellationSucceedsWhenTheFirstRollsBack() {
        Appointment current = committed(tenant, START, 30);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            AppointmentRace race = new AppointmentRace(executor, transactionManager, jdbc);
            race.holdFirst(() -> store.cancel(new CancelUpdate(current.businessId(), current.id(), 0, NOW)));
            race.startSecond(() -> store.cancel(
                    new CancelUpdate(current.businessId(), current.id(), 0, NOW.plusSeconds(5))));
            race.assertSecondWaitsOnTheFirst();

            race.rollBackFirst();

            assertThat(race.firstOutcome()).isInstanceOf(AppointmentRace.RollbackRequested.class);
            assertThat(((Optional<?>) race.secondOutcome())).isPresent();
        }

        assertThat(store.find(current.businessId(), current.id()).orElseThrow().cancelledAt())
                .isEqualTo(NOW.plusSeconds(5));
    }
}
