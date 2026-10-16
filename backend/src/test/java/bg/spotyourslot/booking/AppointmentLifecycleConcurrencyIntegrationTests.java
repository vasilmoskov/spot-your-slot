package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.assertNotBlocked;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cancellation and internal-note races with real PostgreSQL (ADR-0029 section 7, Phase 6). Every
 * interleaving is ordered by latches inside the attempt and by lock-wait evidence from
 * {@code pg_stat_activity} and {@code pg_blocking_pids}; nothing sleeps and every helper-thread wait is
 * bounded. The cancellation and note edit run one READ COMMITTED transaction each and are never
 * retried, so a lost race is the typed stale-version result of that single attempt; the move it races
 * runs the repeatable-read runner. Nothing in this class is injected: every lock wait, row re-check
 * after a wait, and rollback is produced by PostgreSQL.
 */
class AppointmentLifecycleConcurrencyIntegrationTests extends LifecycleAppointmentTest {
    private final Map<String, Integer> pids = new ConcurrentHashMap<>();
    private final Map<String, Runnable> atClock = new ConcurrentHashMap<>();
    private final Map<String, Runnable> atAvailability = new ConcurrentHashMap<>();

    @BeforeEach
    void routeHooks() {
        manualHooks.on(ManualBookingHooks.Point.BEFORE_OWNER_LOCK,
                invocation -> pids.put(invocation.thread(), backendPid(jdbc)));
        manualHooks.on(ManualBookingHooks.Point.AFTER_CLOCK_READ, invocation -> {
            Runnable action = atClock.get(invocation.thread());
            if (action != null) {
                action.run();
            }
        });
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            Runnable action = atAvailability.get(invocation.thread());
            if (action != null) {
                action.run();
            }
        });
    }

    // ---- helpers ------------------------------------------------------------------------------

    private int pidOf(String label) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Integer pid = pids.get(threadOf(label));
            if (pid != null) {
                return pid;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("attempt " + label + " never reached its first statement");
    }

    /** Holds the attempt at its clock read, which is after the plain read and before the UPDATE. */
    private void holdBeforeUpdate(String label, CountDownLatch reached, CountDownLatch release) {
        atClock.put(threadOf(label), () -> {
            reached.countDown();
            await(release, "attempt " + label + " was never released before its update");
        });
    }

    /** Lets the attempt UPDATE and then holds it just before COMMIT, with its row lock held. */
    private void holdBeforeCommitOfLifecycle(String label, CountDownLatch committing, CountDownLatch release) {
        atClock.put(threadOf(label), () -> holdBeforeCommit(committing, release));
    }

    private void holdBeforeCommitOfMove(String label, CountDownLatch committing, CountDownLatch release) {
        atAvailability.put(threadOf(label), () -> holdBeforeCommit(committing, release));
    }

    private Instant stored(UUID id, String column) {
        return instantOf(fullRow(id), column);
    }

    // ---- lock order and isolation ---------------------------------------------------------------------------

    private Set<String> lockedRows(UUID user, UUID appointment) {
        return async(() -> {
            Set<String> locked = new TreeSet<>();
            String[][] probes = {
                {"business", "SELECT id FROM business WHERE id = :business"},
                {"membership", "SELECT id FROM membership WHERE business_id = :business AND user_id = :user"},
                {"staff", "SELECT id FROM staff_member WHERE id = :staff"},
                {"revision", "SELECT business_id FROM business_schedule_revision WHERE business_id = :business"},
                {"service", "SELECT id FROM service WHERE id = :service"},
                {"customer", "SELECT id FROM customer WHERE id = :customer"},
                {"appointment", "SELECT id FROM appointment WHERE id = :appointment"}};
            for (String[] probe : probes) {
                boolean obtainable = jdbc.sql(probe[1] + " FOR UPDATE SKIP LOCKED")
                        .param("business", tenant.business())
                        .param("user", user)
                        .param("staff", tenant.staff())
                        .param("service", tenant.service())
                        .param("customer", customerId)
                        .param("appointment", appointment)
                        .query(UUID.class).list().isEmpty() == false;
                if (!obtainable) {
                    locked.add(probe[0]);
                }
            }
            return locked;
        }).join();
    }

    private record Observed(List<String> points, List<Set<String>> held) {
    }

    private Observed observeLocks(UUID appointment) {
        List<String> points = new ArrayList<>();
        List<Set<String>> held = new ArrayList<>();
        UUID user = ownerContext.userId();
        manualHooks.on(ManualBookingHooks.Point.BEFORE_OWNER_LOCK, invocation -> {
            points.add(invocation.point().name());
            held.add(lockedRows(user, appointment));
        });
        manualHooks.on(ManualBookingHooks.Point.AFTER_CLOCK_READ, invocation -> {
            points.add(invocation.point().name());
            held.add(lockedRows(user, appointment));
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            points.add("BEFORE_COMMIT");
                            held.add(lockedRows(user, appointment));
                        }
                    });
        });
        return new Observed(points, held);
    }

    @Test
    void aCancellationLocksTheBusinessThenTheMembershipThenUpdatesTheAppointmentLastAndTouchesNothingElse() {
        CreatedAppointment created = create(cmd());
        Observed observed = observeLocks(created.id());

        cancel(created);

        assertThat(observed.points()).containsExactly("BEFORE_OWNER_LOCK", "AFTER_CLOCK_READ", "BEFORE_COMMIT");
        assertThat(observed.held()).containsExactly(
                Set.of("business"),
                Set.of("business", "membership"),
                Set.of("appointment", "business", "membership"));
        // No StaffMember, schedule revision, Service, or Customer row is ever locked.
        assertThat(observed.held()).noneMatch(rows -> rows.contains("staff") || rows.contains("revision")
                || rows.contains("service") || rows.contains("customer"));
    }

    @Test
    void aNoteEditLocksTheSameThreeLevelsInTheSameOrder() {
        CreatedAppointment created = create(cmd());
        Observed observed = observeLocks(created.id());

        editNote(created.id(), 0L, "СЕНТИНЕЛ");

        assertThat(observed.points()).containsExactly("BEFORE_OWNER_LOCK", "AFTER_CLOCK_READ", "BEFORE_COMMIT");
        assertThat(observed.held()).containsExactly(
                Set.of("business"),
                Set.of("business", "membership"),
                Set.of("appointment", "business", "membership"));
    }

    @Test
    void anUnchangedNoteLocksOnlyTheBusinessAndTheMembershipAndNeverTheAppointment() {
        CreatedAppointment created = create(cmd().note("СЕНТИНЕЛ"));
        Observed observed = observeLocks(created.id());

        editNote(created.id(), 0L, "СЕНТИНЕЛ");

        assertThat(observed.held()).containsExactly(
                Set.of("business"),
                Set.of("business", "membership"),
                Set.of("business", "membership"));
    }

    // ---- different Appointments ----------------------------------------------------------------------------------

    @Test
    void cancellationsOfDifferentAppointmentsNeverBlockEachOther() {
        CreatedAppointment first = existing("09:00");
        CreatedAppointment other = existing("11:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("a", committing, release);
        CompletableFuture<UpdatedAppointment> held = submitCancel("a", first);
        await(committing, "cancellation a never reached its commit");

        UpdatedAppointment done = completed(submitCancel("b", other));

        assertThat(done.status()).isEqualTo("CANCELLED");
        assertThat(held).isNotDone();
        assertNotBlocked(jdbc, pidOf("a"));
        release.countDown();
        assertThat(completed(held).status()).isEqualTo("CANCELLED");
    }

    // ---- cancel versus cancel ---------------------------------------------------------------------------------------

    @Test
    void twoCancellationsReadingTheSameVersionLeaveExactlyOneWinnerWhoseAttributionIsKept() {
        CreatedAppointment created = create(cmd());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeUpdate("a", reached, release);
        CompletableFuture<UpdatedAppointment> loser = submitCancel("a", created);
        await(reached, "cancellation a never reached its update");
        Instant winnerTime = BookingHookConfiguration.NOW.plusSeconds(600);
        clock.set(winnerTime);

        // B reads the same version, updates, and commits while A waits just before its update.
        UpdatedAppointment winner = completed(submitCancel("b", created));
        release.countDown();

        assertThat(winner.version()).isEqualTo(1L);
        assertThat(failureOf(loser)).isInstanceOf(ConcurrentUpdate.class);
        Map<String, Object> row = fullRow(created.id());
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(instantOf(row, "cancelled_at")).isEqualTo(winnerTime);
        assertThat(row.get("cancelled_by")).isEqualTo("BUSINESS");
        // One transaction each; the loser's single attempt rolled back and was not retried.
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(threadOf("a") + ":rollback");
    }

    @Test
    void aCancellationWaitingOnTheRowLockOfAnUncommittedCancellationFailsStaleAfterTheWinnerCommits() {
        CreatedAppointment created = create(cmd());
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("a", committing, release);
        CompletableFuture<UpdatedAppointment> winner = submitCancel("a", created);
        await(committing, "cancellation a never reached its commit");

        CompletableFuture<UpdatedAppointment> loser = submitCancel("b", created);
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));
        assertThat(loser).isNotDone();
        release.countDown();

        assertThat(completed(winner).version()).isEqualTo(1L);
        assertThat(failureOf(loser)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(1L);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("b"))).containsExactly(threadOf("b") + ":rollback");
    }

    @Test
    void aLoserThatKnowsTheNewVersionLearnsTheAppointmentIsAlreadyCancelled() {
        CreatedAppointment created = create(cmd());
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("a", committing, release);
        CompletableFuture<UpdatedAppointment> winner = submitCancel("a", created);
        await(committing, "cancellation a never reached its commit");
        release.countDown();
        completed(winner);

        assertThat(failureOf(submitCancel("b", created.id(), 1L)))
                .isInstanceOf(AppointmentAdministrationException.StateConflict.class);
    }

    @Test
    void simultaneousCancellationsAtABarrierLeaveExactlyOneWinner() {
        CreatedAppointment created = create(cmd());
        CountDownLatch bothArrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (String label : List.of("a", "b")) {
            atClock.put(threadOf(label), () -> {
                bothArrived.countDown();
                await(release, "the barrier was never released");
            });
        }
        CompletableFuture<UpdatedAppointment> a = submitCancel("a", created);
        CompletableFuture<UpdatedAppointment> b = submitCancel("b", created);
        await(bothArrived, "both cancellations never reached the barrier");
        release.countDown();

        int cancelled = 0;
        for (CompletableFuture<UpdatedAppointment> future : List.of(a, b)) {
            try {
                completed(future);
                cancelled++;
            } catch (AssertionError failed) {
                assertThat(failed.getCause().getCause()).isInstanceOf(ConcurrentUpdate.class);
            }
        }

        assertThat(cancelled).isEqualTo(1);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(1L);
    }

    // ---- cancel versus reschedule and reassign ------------------------------------------------------------------------

    @Test
    void aMoveWaitingOnTheRowLockOfAnUncommittedCancellationEndsStaleAndTheCancellationStands() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("c", committing, release);
        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", created);
        await(committing, "cancellation c never reached its commit");

        CompletableFuture<RescheduledAppointment> move = submitMove("m", move(created).start("11:00"));
        awaitBlockedBy(jdbc, pidOf("m"), pidOf("c"));
        release.countDown();

        assertThat(completed(cancellation).status()).isEqualTo("CANCELLED");
        assertThat(failureOf(move)).isInstanceOf(ConcurrentUpdate.class);
        Map<String, Object> row = fullRow(created.id());
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(instantOf(row, "start_at")).isEqualTo(at("10:00"));
        assertThat(row.get("version")).isEqualTo(1L);
    }

    @Test
    void aCancellationWaitingOnTheRowLockOfAnUncommittedMoveEndsStaleAndTheMoveStands() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfMove("m", committing, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("m", move(created).start("11:00"));
        await(committing, "move m never reached its commit");

        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", created);
        awaitBlockedBy(jdbc, pidOf("c"), pidOf("m"));
        release.countDown();

        assertThat(completed(move).version()).isEqualTo(1L);
        assertThat(failureOf(cancellation)).isInstanceOf(ConcurrentUpdate.class);
        Map<String, Object> row = fullRow(created.id());
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(instantOf(row, "start_at")).isEqualTo(at("11:00"));
        // The owner who reloads cancels the moved Appointment at its new version.
        assertThat(cancel(created.id(), 1L).status()).isEqualTo("CANCELLED");
    }

    @Test
    void aCancellationRacingAReassignmentLeavesExactlyOneWinnerInBothOrders() {
        // The reassignment commits first; the cancellation with the old version is stale.
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfMove("r", committing, release);
        CompletableFuture<RescheduledAppointment> reassignment = submitMove("r", move(created).staff(second));
        await(committing, "reassignment r never reached its commit");
        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", created);
        awaitBlockedBy(jdbc, pidOf("c"), pidOf("r"));
        release.countDown();

        assertThat(completed(reassignment).staffMemberId()).isEqualTo(second);
        assertThat(failureOf(cancellation)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(created.id()).get("status")).isEqualTo("CONFIRMED");

        // The cancellation commits first; the reassignment with the old version is stale.
        CreatedAppointment other = existing("11:00");
        CountDownLatch cancelling = new CountDownLatch(1);
        CountDownLatch cancelRelease = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("c2", cancelling, cancelRelease);
        CompletableFuture<UpdatedAppointment> first = submitCancel("c2", other);
        await(cancelling, "cancellation c2 never reached its commit");
        CompletableFuture<RescheduledAppointment> second = submitMove("r2", move(other).staff(this.second));
        awaitBlockedBy(jdbc, pidOf("r2"), pidOf("c2"));
        cancelRelease.countDown();

        assertThat(completed(first).status()).isEqualTo("CANCELLED");
        assertThat(failureOf(second)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(other.id()).get("staff_member_id")).isEqualTo(tenant.staff());
    }

    @Test
    void aCancellationHeldUncommittedStillBlocksAMoveIntoTheFreedIntervalAndOnlyItsCommitFreesIt() {
        CreatedAppointment held = existing("10:00");
        CreatedAppointment mover = existing("09:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("c", committing, release);
        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", held);
        await(committing, "cancellation c never reached its commit");

        // The cancellation is invisible before its commit: the interval is still busy for another snapshot.
        assertThat(failureOf(submitMove("m", move(mover).start("10:00")))).isInstanceOf(SlotUnavailable.class);
        release.countDown();
        completed(cancellation);

        assertThat(completed(submitMove("m2", move(mover).start("10:00"))).start()).isEqualTo(at("10:00"));
    }

    // ---- cancel versus note ----------------------------------------------------------------------------------------------

    @Test
    void aNoteEditWaitingOnAnUncommittedCancellationEndsStaleAndTheCancellationKeepsItsFacts() {
        CreatedAppointment created = create(cmd());
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("c", committing, release);
        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", created);
        await(committing, "cancellation c never reached its commit");
        CompletableFuture<UpdatedAppointment> note = submitNote("n", created.id(), 0L, "СЕНТИНЕЛ-БЕЛЕЖКА");
        awaitBlockedBy(jdbc, pidOf("n"), pidOf("c"));
        release.countDown();

        assertThat(completed(cancellation).status()).isEqualTo("CANCELLED");
        assertThat(failureOf(note)).isInstanceOf(ConcurrentUpdate.class);
        Map<String, Object> row = fullRow(created.id());
        assertThat(row.get("internal_note")).isNull();
        assertThat(row.get("version")).isEqualTo(1L);
    }

    @Test
    void aCancellationWaitingOnAnUncommittedNoteEditEndsStaleAndTheNoteStands() {
        CreatedAppointment created = create(cmd());
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("n", committing, release);
        CompletableFuture<UpdatedAppointment> note = submitNote("n", created.id(), 0L, "СЕНТИНЕЛ-БЕЛЕЖКА");
        await(committing, "note edit n never reached its commit");
        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", created);
        awaitBlockedBy(jdbc, pidOf("c"), pidOf("n"));
        release.countDown();

        assertThat(completed(note).version()).isEqualTo(1L);
        assertThat(failureOf(cancellation)).isInstanceOf(ConcurrentUpdate.class);
        Map<String, Object> row = fullRow(created.id());
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("internal_note")).isEqualTo("СЕНТИНЕЛ-БЕЛЕЖКА");
    }

    // ---- note versus note and move -----------------------------------------------------------------------------------------

    @Test
    void twoNoteEditsReadingTheSameVersionLeaveExactlyOneWinner() {
        CreatedAppointment created = create(cmd());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeUpdate("a", reached, release);
        CompletableFuture<UpdatedAppointment> loser = submitNote("a", created.id(), 0L, "ПЪРВА");
        await(reached, "note edit a never reached its update");

        UpdatedAppointment winner = completed(submitNote("b", created.id(), 0L, "ВТОРА"));
        release.countDown();

        assertThat(winner.version()).isEqualTo(1L);
        assertThat(failureOf(loser)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(created.id()).get("internal_note")).isEqualTo("ВТОРА");
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @Test
    void aNoteEditWaitingOnAnUncommittedMoveEndsStaleAndTheMoveStandsWithoutTheNote() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfMove("m", committing, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("m", move(created).start("11:00"));
        await(committing, "move m never reached its commit");
        CompletableFuture<UpdatedAppointment> note = submitNote("n", created.id(), 0L, "СЕНТИНЕЛ-БЕЛЕЖКА");
        awaitBlockedBy(jdbc, pidOf("n"), pidOf("m"));
        release.countDown();

        assertThat(completed(move).version()).isEqualTo(1L);
        assertThat(failureOf(note)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(created.id()).get("internal_note")).isNull();
    }

    @Test
    void aMoveWaitingOnAnUncommittedNoteEditEndsStaleAndTheNoteStandsAtTheOldTime() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("n", committing, release);
        CompletableFuture<UpdatedAppointment> note = submitNote("n", created.id(), 0L, "СЕНТИНЕЛ-БЕЛЕЖКА");
        await(committing, "note edit n never reached its commit");
        CompletableFuture<RescheduledAppointment> move = submitMove("m", move(created).start("11:00"));
        awaitBlockedBy(jdbc, pidOf("m"), pidOf("n"));
        release.countDown();

        assertThat(completed(note).version()).isEqualTo(1L);
        assertThat(failureOf(move)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(stored(created.id(), "start_at")).isEqualTo(at("10:00"));
    }

    // ---- cancellation versus creation and guest booking ----------------------------------------------------------------------

    @Test
    void aCancellationIsInvisibleToCreationAndGuestBookingUntilItCommitsAndFreesTheTimeAfterwards() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("c", committing, release);
        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", created);
        await(committing, "cancellation c never reached its commit");

        // Neither waits nor succeeds while the cancellation is uncommitted.
        assertThat(failureOf(submitCreate("m", cmd().start("10:00")))).isInstanceOf(SlotUnavailable.class);
        assertThat(book(req().staff(tenant.staff()).start("10:00").build()))
                .isInstanceOf(BookingResult.SlotUnavailable.class);
        assertThat(cancellation).isNotDone();
        release.countDown();
        completed(cancellation);

        assertThat(completed(submitCreate("m2", cmd().start("10:00"))).start()).isEqualTo(at("10:00"));
    }

    @Test
    void aCancellationRacingACreationIntoItsOwnIntervalNeverOverlapsTwoConfirmedAppointments() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdBeforeCommitOfLifecycle("c", committing, release);
        CompletableFuture<UpdatedAppointment> cancellation = submitCancel("c", created);
        await(committing, "cancellation c never reached its commit");
        release.countDown();
        completed(cancellation);

        CreatedAppointment replacement = completed(submitCreate("m", cmd().start("10:00")));

        assertThat(replacement.id()).isNotEqualTo(created.id());
        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE staff_member_id = :s AND status = 'CONFIRMED'")
                .param("s", tenant.staff()).query(Long.class).single()).isEqualTo(1L);
    }
}
