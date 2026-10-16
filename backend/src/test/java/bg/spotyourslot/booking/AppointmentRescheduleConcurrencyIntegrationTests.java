package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.assertNotBlocked;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.booking.BookingTestHooks.TransactionObservation;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.NoteUpdate;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reschedule and reassignment races with real PostgreSQL (ADR-0029 section 7). Every interleaving is
 * ordered by latches inside the attempt and by lock-wait evidence from {@code pg_stat_activity} and
 * {@code pg_blocking_pids}; nothing sleeps. The failures PostgreSQL raises (exclusion,
 * serialization) are produced by the database and travel through the real orchestration. The
 * neighbours' cancellation and note edits are the Phase 2 persistence primitives of
 * {@link AppointmentStore}; the orchestrated cancel and note operations are Phase 6.
 */
class AppointmentRescheduleConcurrencyIntegrationTests extends RescheduleAppointmentTest {
    @Autowired BusinessAdministration businesses;
    @Autowired ServiceAdministration services;
    @Autowired StaffMemberAdministration staffMembers;
    @Autowired AppointmentStore store;

    // ---- helpers ------------------------------------------------------------------------------

    private int pidOf(String label) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<TransactionObservation> mine = hooks.observationsOf(threadOf(label));
            if (!mine.isEmpty()) {
                return mine.get(0).backendPid();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("attempt " + label + " never reached an observed point");
    }

    private void assertDistinctTransactionsAndSnapshots(String label, int attempts) {
        List<TransactionObservation> firstPoint = hooks.observationsOf(threadOf(label)).stream()
                .filter(observation -> observation.point() == Point.BEFORE_STAFF_LOCK)
                .toList();
        assertThat(firstPoint).hasSize(attempts);
        assertThat(firstPoint.stream().map(TransactionObservation::transactionId).distinct().count())
                .as("distinct PostgreSQL transaction identifiers").isEqualTo(attempts);
        assertThat(firstPoint.stream().map(TransactionObservation::snapshot).distinct().count())
                .as("fresh snapshots").isEqualTo(attempts);
    }

    /** What the attempts of one thread observed about their own PostgreSQL transactions. */
    private record Transactions(List<Long> ids, List<String> snapshots) {
        void assertDistinct(int attempts) {
            assertThat(ids).hasSize(attempts);
            assertThat(ids.stream().distinct().count()).as("distinct PostgreSQL transaction identifiers")
                    .isEqualTo(attempts);
            assertThat(snapshots.stream().distinct().count()).as("fresh snapshots").isEqualTo(attempts);
        }
    }

    /**
     * Records the transaction identifier and snapshot at the owner lock of every attempt of one
     * thread: the first point every attempt reaches, including the ones that end at the Appointment
     * read before any StaffMember lock.
     */
    private Transactions observeAttempts(String label) {
        Transactions transactions = new Transactions(new ArrayList<>(), new ArrayList<>());
        manualHooks.on(ManualBookingHooks.Point.BEFORE_OWNER_LOCK, invocation -> {
            if (invocation.thread().equals(threadOf(label))) {
                transactions.ids().add(jdbc.sql("SELECT txid_current()").query(Long.class).single());
                transactions.snapshots().add(jdbc.sql("SELECT pg_current_snapshot()::text").query(String.class).single());
            }
        });
        return transactions;
    }

    /** Which rows another transaction cannot lock FOR UPDATE (so this attempt holds a lock on them). */
    private Set<String> lockedRows(UUID user, UUID appointment) {
        return async(() -> {
            Set<String> locked = new TreeSet<>();
            String[][] probes = {
                {"business", "SELECT id FROM business WHERE id = :business"},
                {"membership", "SELECT id FROM membership WHERE business_id = :business AND user_id = :user"},
                {"staff", "SELECT id FROM staff_member WHERE id = :staff"},
                {"target", "SELECT id FROM staff_member WHERE id = :second"},
                {"revision", "SELECT business_id FROM business_schedule_revision WHERE business_id = :business"},
                {"service", "SELECT id FROM service WHERE id = :service"},
                {"customer", "SELECT id FROM customer WHERE id = :customer"},
                {"appointment", "SELECT id FROM appointment WHERE id = :appointment"}};
            for (String[] probe : probes) {
                boolean free = !jdbc.sql(probe[1] + " FOR UPDATE SKIP LOCKED")
                        .param("business", tenant.business())
                        .param("user", user)
                        .param("staff", tenant.staff())
                        .param("second", second)
                        .param("service", tenant.service())
                        .param("customer", customerId)
                        .param("appointment", appointment)
                        .query(UUID.class).list().isEmpty();
                if (!free) {
                    locked.add(probe[0]);
                }
            }
            return locked;
        }).join();
    }

    /** An open transaction on another connection that changed a row and has not committed. */
    private final class OpenChange {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch commit = new CountDownLatch(1);
        final int[] pid = new int[1];
        final CompletableFuture<Void> done;

        OpenChange(Runnable change) {
            done = async(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    change.run();
                    pid[0] = backendPid(jdbc);
                    held.countDown();
                    await(commit, "the open change was never committed");
                });
                return null;
            });
            await(held, "the open change never started");
        }

        void commit() {
            commit.countDown();
            completed(done);
        }
    }

    private UUID neighbourFor(UUID staff, String time) {
        return insertRow(staff, at(time));
    }

    private UUID insertRow(UUID staff, Instant start) {
        Map<String, Object> row = new LinkedHashMap<>(AppointmentFixtures.row(tenant, start, 30));
        row.put("customer_id", customerId);
        row.put("staff_member_id", staff);
        row.put("created_at", AppointmentFixtures.utc(Instant.parse("2026-09-01T00:00:00Z")));
        row.put("updated_at", AppointmentFixtures.utc(Instant.parse("2026-09-01T00:00:00Z")));
        fixtures.insertRow(row);
        return (UUID) row.get("id");
    }

    // ---- lock order ------------------------------------------------------------------------------

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
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            points.add(invocation.point().name());
            held.add(lockedRows(user, appointment));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    points.add("BEFORE_COMMIT");
                    held.add(lockedRows(user, appointment));
                }
            });
        });
        for (Point point : List.of(Point.BEFORE_STAFF_LOCK, Point.BEFORE_GUARD, Point.BEFORE_SERVICE_LOCK,
                Point.BEFORE_AVAILABILITY, Point.BEFORE_CUSTOMER, Point.AFTER_CUSTOMER)) {
            hooks.on(point, invocation -> {
                points.add(invocation.point().name());
                held.add(lockedRows(user, appointment));
            });
        }
        return new Observed(points, held);
    }

    @Test
    void aTimeOnlyMoveLocksBusinessMembershipTheCurrentStaffMemberAndTheRevisionInOrderThenUpdatesTheAppointmentLast() {
        CreatedAppointment created = existing("10:00");
        Observed observed = observeLocks(created.id());

        reschedule(move(created).start("11:00"));

        assertThat(observed.points()).containsExactly(
                "BEFORE_OWNER_LOCK", "BEFORE_STAFF_LOCK", "BEFORE_GUARD", "BEFORE_AVAILABILITY",
                "AFTER_BUSINESS_AVAILABILITY", "BEFORE_COMMIT");
        assertThat(observed.held()).containsExactly(
                Set.of("business"),
                Set.of("business", "membership"),
                Set.of("business", "membership", "staff"),
                Set.of("business", "membership", "revision", "staff"),
                Set.of("business", "membership", "revision", "staff"),
                Set.of("appointment", "business", "membership", "revision", "staff"));
        // No Service row and no Customer row is ever locked, and no Service hook fires.
        assertThat(observed.held()).noneMatch(rows -> rows.contains("service") || rows.contains("customer"));
        assertThat(observed.points()).doesNotContain("BEFORE_SERVICE_LOCK", "BEFORE_CUSTOMER", "AFTER_CUSTOMER");
    }

    @Test
    void aReassignmentLocksOnlyTheTargetStaffMemberAndNotTheCurrentOne() {
        CreatedAppointment created = existing("10:00");
        Observed observed = observeLocks(created.id());

        reschedule(move(created).staff(second));

        assertThat(observed.points()).containsExactly(
                "BEFORE_OWNER_LOCK", "BEFORE_STAFF_LOCK", "BEFORE_GUARD", "BEFORE_AVAILABILITY",
                "AFTER_BUSINESS_AVAILABILITY", "BEFORE_COMMIT");
        assertThat(observed.held()).containsExactly(
                Set.of("business"),
                Set.of("business", "membership"),
                Set.of("business", "membership", "target"),
                Set.of("business", "membership", "revision", "target"),
                Set.of("business", "membership", "revision", "target"),
                Set.of("appointment", "business", "membership", "revision", "target"));
        assertThat(observed.held()).noneMatch(rows -> rows.contains("service") || rows.contains("customer"));
    }

    @Test
    void movesOfDifferentAppointmentsToDifferentTimesNeverBlockEachOther() {
        CreatedAppointment first = existing("09:00");
        CreatedAppointment other = existing("10:30");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, "a", reached, release);
        CompletableFuture<RescheduledAppointment> held = submitMove("a", move(first).start("09:30"));
        await(reached, "move a never reached its update");

        RescheduledAppointment done = completed(submitMove("b", move(other).start("11:00")));

        assertThat(done.start()).isEqualTo(at("11:00"));
        assertThat(held).isNotDone();
        assertNotBlocked(jdbc, pidOf("a"));
        release.countDown();
        assertThat(completed(held).start()).isEqualTo(at("09:30"));
    }

    // ---- two moves of one Appointment ------------------------------------------------------------------

    @Test
    void twoMovesOfOneAppointmentWithTheSameVersionLeaveExactlyOneWinnerAndTheLoserIsTheStaleVersionResult() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        Transactions attempts = observeAttempts("a");
        holdAt(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, "a", reached, release);
        CompletableFuture<RescheduledAppointment> loser = submitMove("a", move(created).start("09:00"));
        await(reached, "move a never reached its update");

        // B reads the same version, validates, and commits while A waits just before its update.
        RescheduledAppointment winner = completed(submitMove("b", move(created).start("11:00")));
        release.countDown();

        assertThat(winner.version()).isEqualTo(1L);
        assertThat(failureOf(loser)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(1L);
        assertThat(instantOf(fullRow(created.id()), "start_at")).isEqualTo(at("11:00"));
        // The loser failed with 40001 on its conditional UPDATE, then its retry re-read version 1.
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        attempts.assertDistinct(2);
    }

    @Test
    void aMoveWaitingOnTheRowLockOfAnUncommittedMoveFailsWithTheStaleVersionResultAfterTheWinnerCommits() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        Transactions attempts = observeAttempts("b");
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<RescheduledAppointment> winner = submitMove("a", move(created).start("11:00"));
        await(committing, "move a never reached its commit");
        // A holds its updated, uncommitted row. B validated against a snapshot that cannot see it.
        CompletableFuture<RescheduledAppointment> loser = submitMove("b", move(created).start("09:00"));
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));

        assertThat(loser).isNotDone();
        release.countDown();

        assertThat(completed(winner).version()).isEqualTo(1L);
        assertThat(failureOf(loser)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(instantOf(fullRow(created.id()), "start_at")).isEqualTo(at("11:00"));
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("b")))
                .containsExactly(threadOf("b") + ":rollback", threadOf("b") + ":rollback");
        attempts.assertDistinct(2);
    }

    // ---- move versus create / guest booking ---------------------------------------------------------------

    @Test
    void aMoveRacingACreationIntoTheSameIntervalLeavesOneWinnerWhicheverCommitsFirst() {
        // Creation first, the move loses.
        CreatedAppointment mover = existing("09:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            if (invocation.thread().equals(threadOf("c"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<CreatedAppointment> creation = submitCreate("c", cmd().start("10:30"));
        await(committing, "creation c never reached its commit");
        Map<String, Object> moverBefore = fullRow(mover.id());
        CompletableFuture<RescheduledAppointment> move = submitMove("m", move(mover).start("10:30"));
        awaitBlockedBy(jdbc, pidOf("m"), pidOf("c"));
        release.countDown();

        assertThat(completed(creation).start()).isEqualTo(at("10:30"));
        assertThat(failureOf(move)).isInstanceOf(SlotUnavailable.class);
        assertThat(fullRow(mover.id())).isEqualTo(moverBefore);
        assertThat(TransactionLog.begins(threadOf("m"))).hasSize(2);
        assertDistinctTransactionsAndSnapshots("m", 2);

        // Move first, the creation loses.
        hooks.reset();
        manualHooks.reset();
        CountDownLatch moveCommitting = new CountDownLatch(1);
        CountDownLatch moveRelease = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            if (invocation.thread().equals(threadOf("m2"))) {
                holdBeforeCommit(moveCommitting, moveRelease);
            }
        });
        CompletableFuture<RescheduledAppointment> moveFirst = submitMove("m2", move(mover).start("11:00"));
        await(moveCommitting, "move m2 never reached its commit");
        CompletableFuture<CreatedAppointment> creationSecond = submitCreate("c2", cmd().start("11:00"));
        awaitBlockedBy(jdbc, pidOf("c2"), pidOf("m2"));
        moveRelease.countDown();

        assertThat(completed(moveFirst).start()).isEqualTo(at("11:00"));
        assertThat(failureOf(creationSecond)).isInstanceOf(SlotUnavailable.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
    }

    @Test
    void aMoveRacingAGuestBookingForTheSameIntervalLeavesOneWinnerWhicheverOrder() {
        // Guest first, the move loses.
        CreatedAppointment mover = existing("09:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("g"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<BookingResult> guest = submit("g", req().staff(tenant.staff()).start("10:30").build());
        await(committing, "the guest booking never reached its commit");
        CompletableFuture<RescheduledAppointment> move = submitMove("m", move(mover).start("10:30"));
        awaitBlockedBy(jdbc, pidOf("m"), pidOf("g"));
        release.countDown();

        assertThat(completed(guest)).isInstanceOf(BookingResult.Created.class);
        assertThat(failureOf(move)).isInstanceOf(SlotUnavailable.class);
        assertThat(instantOf(fullRow(mover.id()), "start_at")).isEqualTo(at("09:00"));

        // Move first, the guest loses.
        hooks.reset();
        manualHooks.reset();
        CountDownLatch moveCommitting = new CountDownLatch(1);
        CountDownLatch moveRelease = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            if (invocation.thread().equals(threadOf("m2"))) {
                holdBeforeCommit(moveCommitting, moveRelease);
            }
        });
        CompletableFuture<RescheduledAppointment> moveFirst = submitMove("m2", move(mover).start("11:00"));
        await(moveCommitting, "move m2 never reached its commit");
        CompletableFuture<BookingResult> guestSecond = submit(
                "g2", req().staff(tenant.staff()).start("11:00").build());
        awaitBlockedBy(jdbc, pidOf("g2"), pidOf("m2"));
        moveRelease.countDown();

        assertThat(completed(moveFirst).start()).isEqualTo(at("11:00"));
        assertThat(completed(guestSecond)).isEqualTo(new BookingResult.SlotUnavailable());
    }

    // ---- reassignment in both directions ---------------------------------------------------------------------

    @Test
    void reassigningAToBAndBToAAtIndependentTimesBlocksNoOneAndBothCommit() {
        CreatedAppointment x = existingFor(tenant.staff(), "09:00");
        CreatedAppointment y = existingFor(second, "11:00");
        CountDownLatch bothArrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            bothArrived.countDown();
            await(release, "the barrier was never released");
        });
        CompletableFuture<RescheduledAppointment> toSecond = submitMove("x", move(x).staff(second));
        CompletableFuture<RescheduledAppointment> toFirst = submitMove("y", move(y).staff(tenant.staff()));
        await(bothArrived, "both reassignments never reached the barrier");

        // Each holds a shared lock on its own target only; no exclusive lock is held yet, so nothing waits.
        assertNotBlocked(jdbc, pidOf("x"));
        assertNotBlocked(jdbc, pidOf("y"));
        release.countDown();

        assertThat(completed(toSecond).staffMemberId()).isEqualTo(second);
        assertThat(completed(toFirst).staffMemberId()).isEqualTo(tenant.staff());
        assertThat(TransactionLog.begins(threadOf("x"))).hasSize(1);
        assertThat(TransactionLog.begins(threadOf("y"))).hasSize(1);
    }

    @Test
    void swappingTwoAppointmentsOfTheSameTimeBetweenTwoStaffMembersIsRefusedForBothWithoutPartialData() {
        CreatedAppointment x = existingFor(tenant.staff(), "10:00");
        CreatedAppointment y = existingFor(second, "10:00");
        Map<String, Object> xBefore = fullRow(x.id());
        Map<String, Object> yBefore = fullRow(y.id());
        CompletableFuture<RescheduledAppointment> toSecond = submitMove("x", move(x).staff(second));
        CompletableFuture<RescheduledAppointment> toFirst = submitMove("y", move(y).staff(tenant.staff()));

        // Each availability sees the other's still-committed interval as busy.
        assertThat(failureOf(toSecond)).isInstanceOf(SlotUnavailable.class);
        assertThat(failureOf(toFirst)).isInstanceOf(SlotUnavailable.class);

        assertThat(fullRow(x.id())).isEqualTo(xBefore);
        assertThat(fullRow(y.id())).isEqualTo(yBefore);
    }

    @Test
    void twoReassignmentsIntoOneTargetIntervalLeaveExactlyOneWinner() {
        CreatedAppointment x = existingFor(tenant.staff(), "10:00");
        UUID third = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), third, "09:00", "12:00");
        CreatedAppointment y = existingFor(third, "10:00");
        CountDownLatch bothArrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            if (invocation.ordinal() == 1) {
                bothArrived.countDown();
                await(release, "the barrier was never released");
            }
        });
        CompletableFuture<RescheduledAppointment> a = submitMove("x", move(x).staff(second));
        CompletableFuture<RescheduledAppointment> b = submitMove("y", move(y).staff(second));
        await(bothArrived, "both reassignments never reached the barrier");
        release.countDown();

        int moved = 0;
        for (CompletableFuture<RescheduledAppointment> future : List.of(a, b)) {
            try {
                completed(future);
                moved++;
            } catch (AssertionError failed) {
                assertThat(failed.getCause().getCause()).isInstanceOf(SlotUnavailable.class);
            }
        }

        assertThat(moved).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE staff_member_id = :s AND status = 'CONFIRMED'")
                .param("s", second).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void simultaneousMovesOfDifferentAppointmentsIntoOneIntervalLeaveOneWinnerAndTheLoserRetriesToSlotUnavailable() {
        CreatedAppointment x = existing("09:00");
        CreatedAppointment y = existing("11:00");
        CountDownLatch bothArrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            if (invocation.ordinal() == 1) {
                bothArrived.countDown();
                await(release, "the barrier was never released");
            }
        });
        CompletableFuture<RescheduledAppointment> a = submitMove("a", move(x).start("10:00"));
        CompletableFuture<RescheduledAppointment> b = submitMove("b", move(y).start("10:00"));
        await(bothArrived, "both moves never reached the barrier");
        // Both validated the same free slot and now update into the exclusion index together. PostgreSQL
        // resolves the cycle itself (a deadlock victim or a plain overlap) and either may win.
        release.countDown();

        int moved = 0;
        for (CompletableFuture<RescheduledAppointment> future : List.of(a, b)) {
            try {
                completed(future);
                moved++;
            } catch (AssertionError failed) {
                assertThat(failed.getCause().getCause()).isInstanceOf(SlotUnavailable.class);
            }
        }

        assertThat(moved).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE start_at = :t AND status = 'CONFIRMED'")
                .param("t", AppointmentFixtures.utc(at("10:00"))).query(Long.class).single()).isEqualTo(1L);
        int loserBegins = Math.max(
                TransactionLog.begins(threadOf("a")).size(), TransactionLog.begins(threadOf("b")).size());
        assertThat(loserBegins).as("the loser retried in completely new transactions").isBetween(2, 3);
    }

    @Test
    void manyMovesIntoTheSameTimeLeaveExactlyOneAppointmentThere() {
        List<CreatedAppointment> movers = List.of(existing("09:00"), existing("09:30"), existing("11:00"));
        List<CompletableFuture<RescheduledAppointment>> futures = new ArrayList<>();
        for (int index = 0; index < movers.size(); index++) {
            futures.add(submitMove("s" + index, move(movers.get(index)).start("10:00")));
        }

        int moved = 0;
        for (CompletableFuture<RescheduledAppointment> future : futures) {
            try {
                completed(future);
                moved++;
            } catch (AssertionError failed) {
                assertThat(failed.getCause().getCause()).isInstanceOfAny(
                        SlotUnavailable.class, TemporarilyUnavailable.class);
            }
        }

        assertThat(moved).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE start_at = :t AND status = 'CONFIRMED'")
                .param("t", AppointmentFixtures.utc(at("10:00"))).query(Long.class).single()).isEqualTo(1L);
        assertThat(appointmentCount(tenant.business())).isEqualTo(3);
    }

    // ---- cancel and note versus move (persistence primitives) ----------------------------------------------

    @Test
    void aCancellationCommittedAfterTheSnapshotMakesTheMoveFailAndItsRetryReportTheStaleVersion() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        Transactions attempts = observeAttempts("a");
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached the staff lock");
        assertThat(async(() -> store.cancel(
                new CancelUpdate(tenant.business(), created.id(), 0L, BookingHookConfiguration.NOW))).join())
                .isPresent();
        release.countDown();

        assertThat(failureOf(move)).isInstanceOf(ConcurrentUpdate.class);

        assertThat(fullRow(created.id()).get("status")).isEqualTo("CANCELLED");
        assertThat(instantOf(fullRow(created.id()), "start_at")).isEqualTo(at("10:00"));
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        attempts.assertDistinct(2);
    }

    @Test
    void aCancellationInFlightMakesTheMoveWaitOnTheRowThenFailWithTheStaleVersionResult() {
        CreatedAppointment created = existing("10:00");
        OpenChange cancellation = new OpenChange(() -> store.cancel(
                new CancelUpdate(tenant.business(), created.id(), 0L, BookingHookConfiguration.NOW)));
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        awaitWaiterBlockedBy(jdbc, cancellation.pid[0]);
        assertThat(move).isNotDone();

        cancellation.commit();

        assertThat(failureOf(move)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(created.id()).get("status")).isEqualTo("CANCELLED");
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
    }

    @Test
    void aCancellationThatWonThereforeGivesAMoveWithTheNewVersionTheStateConflict() {
        CreatedAppointment created = existing("10:00");
        assertThat(store.cancel(new CancelUpdate(
                tenant.business(), created.id(), 0L, BookingHookConfiguration.NOW))).isPresent();

        assertThat(rejectionOf(move(created.id(), 1L).start("11:00"))).isInstanceOf(StateConflict.class);
        assertThat(rejectionOf(move(created.id(), 0L).start("11:00"))).isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void aNoteEditCommittedAfterTheSnapshotMakesTheMoveFailAndItsRetryReportTheStaleVersion() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached the staff lock");
        assertThat(async(() -> store.updateInternalNote(new NoteUpdate(
                tenant.business(), created.id(), 0L, "Бележка", BookingHookConfiguration.NOW))).join())
                .isPresent();
        release.countDown();

        assertThat(failureOf(move)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(fullRow(created.id()).get("internal_note")).isEqualTo("Бележка");
        assertThat(instantOf(fullRow(created.id()), "start_at")).isEqualTo(at("10:00"));
    }

    // ---- committed changes after the snapshot (real 40001 through the real orchestration) ------------------

    @Test
    void aStaffMemberDeactivatedAfterTheSnapshotIsRejectedByTheRetryOfATimeOnlyMove() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached the staff lock");
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);
        release.countDown();

        assertThat(failureOf(move)).isInstanceOf(StaffUnavailable.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertDistinctTransactionsAndSnapshots("a", 2);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void anAssignmentRemovedAfterTheSnapshotThroughTheRealAdministrationDoesNotBlockTheRetriedTimeOnlyMove() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached the staff lock");
        staffMembers.replaceServiceAssignments(
                ownerContext, tenant.staff(), new ReplaceServiceAssignmentsCommand(List.of(), 0L));
        release.countDown();

        // The administration changed the StaffMember row after the snapshot, so the first attempt's lock
        // failed; the retry no longer needs an assignment for a time-only move and succeeds.
        assertThat(completed(move).start()).isEqualTo(at("11:00"));
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertDistinctTransactionsAndSnapshots("a", 2);
    }

    @Test
    void anAssignmentRemovedAfterTheSnapshotIsRejectedByTheRetryOfAReassignment() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).staff(second));
        await(reached, "move a never reached the staff lock");
        staffMembers.replaceServiceAssignments(
                ownerContext, second, new ReplaceServiceAssignmentsCommand(List.of(), 0L));
        release.countDown();

        assertThat(failureOf(move)).isInstanceOf(StaffUnavailable.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(fullRow(created.id()).get("staff_member_id")).isEqualTo(tenant.staff());
    }

    @Test
    void aServiceDeactivationAfterTheSnapshotAndInFlightNeitherBlocksNorChangesATimeOnlyMove() {
        CreatedAppointment created = existing("10:00");
        // An uncommitted Service deactivation holds the Service row; the move takes no Service lock.
        OpenChange deactivation = new OpenChange(() ->
                jdbc.sql("UPDATE service SET active = false WHERE id = :id")
                        .param("id", tenant.service()).update());

        RescheduledAppointment moved = completed(submitMove("a", move(created).start("11:00")));

        assertThat(moved.start()).isEqualTo(at("11:00"));
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        deactivation.commit();
    }

    @Test
    void aServiceDeactivationThroughTheRealAdministrationDoesNotWaitForAMoveThatHoldsItsLocks() {
        CreatedAppointment created = existing("10:00");
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached its update");

        services.deactivate(ownerContext, tenant.service(), new ServiceVersionCommand(0L));

        assertNotBlocked(jdbc, pidOf("a"));
        release.countDown();
        assertThat(completed(move).start()).isEqualTo(at("11:00"));
    }

    @Test
    void aStaffMemberDeactivationAndAnAssignmentReplacementWaitForTheMoveThatHoldsTheStaffLock() {
        CreatedAppointment created = existing("10:00");
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_GUARD, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached the guard");

        CompletableFuture<Void> deactivation = async(() -> {
            staffMembers.deactivate(ownerContext, tenant.staff(), new StaffMemberVersionCommand(0L));
            return null;
        });
        awaitBlockedBy(jdbc, awaitWaiterBlockedBy(jdbc, pidOf("a")).pid(), pidOf("a"));
        assertThat(deactivation).isNotDone();
        release.countDown();

        assertThat(completed(move).start()).isEqualTo(at("11:00"));
        completed(deactivation);
        assertThat(failureOf(submitMove("next", move(created.id(), 1L).start("11:30"))))
                .isInstanceOf(StaffUnavailable.class);
    }

    @Test
    void aBusinessSuspensionInFlightMakesTheFirstStatementWaitThenFailAndTheRetryIsBusinessSuspended() {
        CreatedAppointment created = existing("10:00");
        OpenChange suspension = new OpenChange(() ->
                jdbc.sql("UPDATE business SET status = 'SUSPENDED' WHERE id = :id")
                        .param("id", tenant.business()).update());
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        awaitWaiterBlockedBy(jdbc, suspension.pid[0]);
        assertThat(move).isNotDone();

        suspension.commit();

        assertThat(failureOf(move)).isInstanceOf(BusinessSuspended.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void aBusinessSuspensionWaitsForAnOpenMoveWhichThenCommitsAndTheNextMoveIsRefused() {
        CreatedAppointment created = existing("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached its update");

        CompletableFuture<Void> suspension = async(() -> {
            businesses.suspendActive(tenant.business(), 0L);
            return null;
        });
        awaitBlockedBy(jdbc, awaitWaiterBlockedBy(jdbc, pidOf("a")).pid(), pidOf("a"));
        assertThat(suspension).isNotDone();
        release.countDown();

        assertThat(completed(move).version()).isEqualTo(1L);
        completed(suspension);
        assertThat(failureOf(submitMove("next", move(created.id(), 1L).start("11:30"))))
                .isInstanceOf(BusinessSuspended.class);
    }

    @Test
    void aMembershipDeactivatedAfterTheSnapshotFailsTheMembershipLockAndTheRetryIsDenied() {
        CreatedAppointment created = existing("10:00");
        manualHooks.on(ManualBookingHooks.Point.BEFORE_OWNER_LOCK, invocation -> {
            if (invocation.ordinal() == 1) {
                async(() -> jdbc.sql("UPDATE membership SET active = false WHERE business_id = :b AND user_id = :u")
                        .param("b", tenant.business()).param("u", ownerContext.userId()).update()).join();
            }
        });

        assertThat(failureOf(submitMove("a", move(created).start("11:00")))).isInstanceOf(AccessDenied.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void aCustomerContactUpdateNeitherWaitsForNorChangesAnOpenMove() {
        CreatedAppointment created = existing("10:00");
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(created).start("11:00"));
        await(reached, "move a never reached its update");

        CompletableFuture<Integer> update = async(() -> jdbc.sql(
                "UPDATE customer SET display_name = 'Преименуван Клиент', version = version + 1 WHERE id = :id")
                .param("id", customerId).update());
        assertThat(completed(update)).isEqualTo(1);
        assertNotBlocked(jdbc, pidOf("a"));
        release.countDown();

        RescheduledAppointment moved = completed(move);
        assertThat(moved.customerId()).isEqualTo(customerId);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- neighbours that change after the snapshot ----------------------------------------------------------------

    @Test
    void aNeighbourCancelledAfterTheSnapshotStillBlocksTheMoveWhichMayRepeat() {
        CreatedAppointment mover = existing("09:00");
        UUID blocker = neighbourFor(tenant.staff(), "10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(mover).start("10:00"));
        await(reached, "move a never reached the staff lock");
        assertThat(async(() -> store.cancel(new CancelUpdate(
                tenant.business(), blocker, 0L, BookingHookConfiguration.NOW))).join()).isPresent();
        release.countDown();

        assertThat(failureOf(move)).isInstanceOf(SlotUnavailable.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(reschedule(move(mover).start("10:00")).start()).isEqualTo(at("10:00"));
    }

    @Test
    void aNeighbourMovedIntoTheIntervalAfterAvailabilityMakesTheUpdateLoseTheExclusionAndTheRetryIsToldItIsTaken() {
        CreatedAppointment mover = existing("09:00");
        UUID neighbour = neighbourFor(tenant.staff(), "11:00");
        Map<String, Object> before = fullRow(mover.id());
        hooks.observeTransactions(jdbc);
        AtomicInteger moves = new AtomicInteger();
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            if (moves.incrementAndGet() == 1) {
                async(() -> store.reschedule(new bg.spotyourslot.booking.domain.AppointmentTransitions.RescheduleUpdate(
                        tenant.business(), neighbour, 0L, tenant.staff(), at("11:00"),
                        at("10:00"), at("10:30"), at("10:30"), tenant.staff(), "Мария",
                        BookingHookConfiguration.NOW))).join();
            }
        });

        assertThat(failureOf(submitMove("a", move(mover).start("10:00")))).isInstanceOf(SlotUnavailable.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertThat(fullRow(mover.id())).isEqualTo(before);
    }

    // ---- retry exhaustion ----------------------------------------------------------------------------------------------

    @Test
    void anOverlapOnEveryAttemptEndsSlotUnavailableAfterExactlyThreeCompleteTransactionsAndLeavesTheRowUnchanged() {
        CreatedAppointment mover = existing("09:00");
        Map<String, Object> before = fullRow(mover.id());
        hooks.observeTransactions(jdbc);
        List<UUID> blockers = new ArrayList<>();
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> {
            // After availability offered the slot, another connection commits a visit on it ...
            UUID blocker = async(() -> insertRow(tenant.staff(), at("10:00"))).join();
            blockers.add(blocker);
            // ... and frees the time again as soon as this attempt has rolled back, before the next snapshot.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    async(() -> fixtures.cancel(blocker)).join();
                }
            });
        });

        assertThat(failureOf(submitMove("a", move(mover).start("10:00")))).isInstanceOf(SlotUnavailable.class);

        assertThat(blockers).hasSize(3);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(3);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(
                threadOf("a") + ":rollback", threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertDistinctTransactionsAndSnapshots("a", 3);
        assertThat(fullRow(mover.id())).isEqualTo(before);
    }

    @Test
    void aConcurrencyFailureOnEveryAttemptEndsTemporarilyUnavailableAfterExactlyThreeAttemptsAndNoChange() {
        CreatedAppointment mover = existing("09:00");
        Map<String, Object> before = fullRow(mover.id());
        hooks.observeTransactions(jdbc);
        AtomicInteger changes = new AtomicInteger();
        hooks.on(Point.BEFORE_STAFF_LOCK, invocation -> {
            changes.incrementAndGet();
            async(() -> jdbc.sql("UPDATE staff_member SET updated_at = updated_at WHERE id = :id")
                    .param("id", tenant.staff()).update()).join();
        });

        assertThat(failureOf(submitMove("a", move(mover).start("10:00")))).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(changes.get()).isEqualTo(3);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(3);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(
                threadOf("a") + ":rollback", threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertDistinctTransactionsAndSnapshots("a", 3);
        assertThat(fullRow(mover.id())).isEqualTo(before);
        hooks.reset();
        assertThat(completed(submitMove("again", move(mover).start("10:00"))).version()).isEqualTo(1L);
    }

    // ---- tenant isolation ---------------------------------------------------------------------------------------------------

    @Test
    void anotherBusinessIsUnaffectedByTheTenantsLocksAndMovesNoForeignData() {
        CreatedAppointment mine = existing("10:00");
        AppointmentFixtures.Tenant other = fixtures.tenant();
        availabilityFixtures.everyDay(other.business(), other.staff(), "09:00", "12:00");
        UUID otherCustomer = fixtures.customer(other.business());
        AuthenticatedBusinessContext otherOwner = owner(other.business());
        CreatedAppointment theirs = completed(submitCreate("o", otherOwner,
                new AppointmentAdministrationRecords.CreateAppointmentCommand(
                        otherCustomer, other.staff(), at("10:00"),
                        List.of(reviewed(other.service())), null)));
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, "a", reached, release);
        CompletableFuture<RescheduledAppointment> move = submitMove("a", move(mine).start("11:00"));
        await(reached, "move a never reached its update");

        RescheduledAppointment foreign = completed(submitMove("b", otherOwner,
                new AppointmentAdministrationRecords.RescheduleAppointmentCommand(
                        theirs.id(), theirs.version(), at("11:00"), null)));
        release.countDown();

        assertThat(foreign.staffMemberId()).isEqualTo(other.staff());
        assertThat(completed(move).staffMemberId()).isEqualTo(tenant.staff());
        assertThat(instantOf(fullRow(theirs.id()), "start_at")).isEqualTo(at("11:00"));
        assertThat(instantOf(fullRow(mine.id()), "start_at")).isEqualTo(at("11:00"));
    }
}
