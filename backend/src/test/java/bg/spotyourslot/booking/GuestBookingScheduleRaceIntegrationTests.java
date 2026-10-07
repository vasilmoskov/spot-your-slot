package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.booking.BookingTestHooks.TransactionObservation;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.ConcurrencyTestSupport.WaitingBackend;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.workforce.StaffWorkingScheduleAdministration;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The booking-versus-schedule races of ADR-0025 with the real booking orchestration, real
 * Appointments and Customers, the real {@code AvailabilityQuery}, and the real weekly-schedule and
 * schedule-exception mutations of the Business owner, for every audited mutation path. Each commit
 * order is produced with latches and PostgreSQL lock-wait evidence, never with sleeps.
 *
 * <p>The expected result of every path is computed by an oracle: a fresh availability calculation
 * after the mutation committed. The booked start (Thursday 10:00 Sofia) is offered by the fresh
 * view exactly when the booking is allowed to succeed. A booking that validated an older view must
 * never commit an Appointment the fresh view does not offer, and must never be rejected on a stale
 * view that the fresh one contradicts.
 */
class GuestBookingScheduleRaceIntegrationTests extends BookingIntegrationTest {
    private static final LocalDate OTHER_DAY = LocalDate.of(2026, 10, 5);

    @Autowired StaffWorkingScheduleAdministration schedules;
    @Autowired ScheduleExceptionAdministration exceptions;
    @Autowired AvailabilityQuery availability;

    /** The thirteen audited mutation paths of ADR-0025: the weekly replacement and 4 kinds x 3 operations. */
    enum Path {
        WEEKLY_REPLACE,
        CLOSURE_CREATE,
        CLOSURE_REPLACE,
        CLOSURE_DELETE,
        TIME_OFF_CREATE,
        TIME_OFF_REPLACE,
        TIME_OFF_DELETE,
        OVERRIDE_CREATE,
        OVERRIDE_REPLACE,
        OVERRIDE_DELETE,
        ADDITIONAL_CREATE,
        ADDITIONAL_REPLACE,
        ADDITIONAL_DELETE
    }

    static Stream<Path> paths() {
        return Stream.of(Path.values());
    }

    static Stream<Path> reducingPaths() {
        return Stream.of(Path.WEEKLY_REPLACE, Path.CLOSURE_CREATE);
    }

    /** One audited mutation with its setup, prepared before the booking starts. */
    private final class Mutation {
        private final Path path;
        private final AuthenticatedBusinessContext owner = owner(tenant.business());
        private UUID existingId;

        Mutation(Path path) {
            this.path = path;
            // Replace and delete operate on an exception that exists before the race begins.
            switch (path) {
                case CLOSURE_REPLACE -> existingId = create(ScheduleExceptionKind.BUSINESS_CLOSURE, null, OTHER_DAY, true, List.of());
                case CLOSURE_DELETE -> existingId = create(ScheduleExceptionKind.BUSINESS_CLOSURE, null, THURSDAY, true, List.of());
                case TIME_OFF_REPLACE -> existingId = create(ScheduleExceptionKind.STAFF_TIME_OFF, tenant.staff(), OTHER_DAY, true, List.of());
                case TIME_OFF_DELETE -> existingId = create(ScheduleExceptionKind.STAFF_TIME_OFF, tenant.staff(), THURSDAY, true, List.of());
                case OVERRIDE_REPLACE -> existingId = create(ScheduleExceptionKind.WORKING_DAY_OVERRIDE, tenant.staff(), THURSDAY, false, List.of(period(9, 12)));
                case OVERRIDE_DELETE -> existingId = create(ScheduleExceptionKind.WORKING_DAY_OVERRIDE, tenant.staff(), THURSDAY, false, List.of(period(14, 16)));
                case ADDITIONAL_REPLACE, ADDITIONAL_DELETE -> existingId = create(ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS, tenant.staff(), THURSDAY, false, List.of(period(18, 20)));
                default -> existingId = null;
            }
        }

        private UUID create(ScheduleExceptionKind kind, UUID staff, LocalDate date, boolean allDay, List<ExceptionPeriod> periods) {
            return exceptions.create(owner, new CreateScheduleExceptionCommand(
                    kind, staff, date, date, allDay, periods)).exception().id();
        }

        /** Commits the mutation in its own transaction, or joins the caller's if one is active. */
        void apply() {
            switch (path) {
                case WEEKLY_REPLACE -> schedules.replace(owner, tenant.staff(), new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0))), 0L));
                case CLOSURE_CREATE -> create(ScheduleExceptionKind.BUSINESS_CLOSURE, null, THURSDAY, true, List.of());
                case TIME_OFF_CREATE -> create(ScheduleExceptionKind.STAFF_TIME_OFF, tenant.staff(), THURSDAY, true, List.of());
                case OVERRIDE_CREATE -> create(ScheduleExceptionKind.WORKING_DAY_OVERRIDE, tenant.staff(), THURSDAY, false, List.of(period(14, 16)));
                case ADDITIONAL_CREATE -> create(ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS, tenant.staff(), THURSDAY, false, List.of(period(18, 20)));
                case CLOSURE_REPLACE, TIME_OFF_REPLACE -> exceptions.replace(owner, existingId,
                        new ReplaceScheduleExceptionCommand(0L, THURSDAY, THURSDAY, true, List.of()));
                case OVERRIDE_REPLACE -> exceptions.replace(owner, existingId,
                        new ReplaceScheduleExceptionCommand(0L, THURSDAY, THURSDAY, false, List.of(period(14, 16))));
                case ADDITIONAL_REPLACE -> exceptions.replace(owner, existingId,
                        new ReplaceScheduleExceptionCommand(0L, THURSDAY, THURSDAY, false, List.of(period(19, 21))));
                case CLOSURE_DELETE, TIME_OFF_DELETE, OVERRIDE_DELETE, ADDITIONAL_DELETE ->
                        exceptions.delete(owner, existingId, 0L);
            }
        }
    }

    private static ExceptionPeriod period(int from, int to) {
        return new ExceptionPeriod(LocalTime.of(from, 0), LocalTime.of(to, 0));
    }

    /** Whether the fresh, committed view offers Thursday 10:00 Sofia for the Service. */
    private boolean offered() {
        return async(() -> availability.calculate(tenant.business(), tenant.service(), null).slots().stream()
                .anyMatch(slot -> slot.start().equals(at("10:00")))).join();
    }

    private long revision() {
        return jdbc.sql("SELECT revision FROM business_schedule_revision WHERE business_id = :id")
                .param("id", tenant.business()).query(Long.class).single();
    }

    private void assertBookedExactlyWhenTheFreshViewOffersTheSlot(BookingResult result, boolean offeredByFreshView) {
        if (offeredByFreshView) {
            assertThat(result).isInstanceOf(Created.class);
            assertThat(appointmentCount(tenant.business())).isEqualTo(1);
            assertThat(customerCount(tenant.business())).isEqualTo(1);
        } else {
            assertThat(result).isEqualTo(new BookingResult.SlotUnavailable());
            assertThat(appointmentCount(tenant.business())).isZero();
            assertThat(customerCount(tenant.business())).isZero();
        }
    }

    // ---- order 1: the change commits before the booking's snapshot -----------------------------------

    @ParameterizedTest
    @MethodSource("paths")
    void aChangeCommittedBeforeTheSnapshotIsSeenAndTheBookingNeedsOneAttempt(Path path) {
        new Mutation(path).apply();
        boolean fresh = offered();

        BookingResult result = result(submit("a", req().build()));

        assertBookedExactlyWhenTheFreshViewOffersTheSlot(result, fresh);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- order 2: the change commits after the snapshot and before the guard ---------------------------

    @ParameterizedTest
    @MethodSource("paths")
    void aChangeCommittedAfterTheSnapshotMakesTheStaleBookingFailAndRetryOnTheNewSchedule(Path path) {
        Mutation mutation = new Mutation(path);
        hooks.observeTransactions(jdbc);
        CountDownLatch atGuard = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        long revisionBefore = revision();
        hooks.on(Point.BEFORE_GUARD, invocation -> {
            if (invocation.ordinal() == 1) {
                atGuard.countDown();
                await(releaseFirst, "booking was never released at the guard");
            }
        });
        hooks.on(Point.BEFORE_STAFF_LOCK, invocation -> {
            if (invocation.ordinal() == 2) {
                // The retry has its new snapshot; hold it until the oracle has looked at the schedule.
                secondStarted.countDown();
                await(releaseSecond, "the retry was never released");
            }
        });
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(atGuard, "booking never reached the guard");

        // The booking has its snapshot and holds the Business and StaffMember locks, but not the
        // revision: the mutation commits without waiting.
        mutation.apply();
        assertThat(revision()).isEqualTo(revisionBefore + 1);
        boolean fresh = offered();
        releaseFirst.countDown();
        await(secondStarted, "the booking did not retry after the schedule change");
        releaseSecond.countDown();

        BookingResult result = completed(booking);

        assertBookedExactlyWhenTheFreshViewOffersTheSlot(result, fresh);
        // Attempt one failed at the guard (40001); attempt two ran on the new schedule.
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a"))).first().isEqualTo(threadOf("a") + ":rollback");
        List<TransactionObservation> starts = hooks.observationsOf(threadOf("a")).stream()
                .filter(observation -> observation.point() == Point.BEFORE_STAFF_LOCK).toList();
        assertThat(starts).hasSize(2);
        assertThat(starts.get(0).transactionId()).isLessThan(starts.get(1).transactionId());
        assertThat(starts.get(0).snapshot()).isNotEqualTo(starts.get(1).snapshot());
    }

    @ParameterizedTest
    @MethodSource("reducingPaths")
    void controlRunWithoutTheGuardAnAvailabilityViewThatPredatesTheChangeCommitsAStaleAppointment(Path path) {
        // CONTROL: pause the booking after its availability was calculated (so the view predates the
        // change) and only then commit the change. Without the guard nothing notices.
        hooks.bypassScheduleGuard(true);
        Mutation mutation = new Mutation(path);
        CountDownLatch afterAvailability = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.on(Point.BEFORE_CUSTOMER, invocation -> {
            afterAvailability.countDown();
            await(release, "booking was never released after availability");
        });
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(afterAvailability, "booking never finished its availability");
        mutation.apply();
        boolean fresh = offered();
        release.countDown();

        BookingResult result = completed(booking);

        assertThat(fresh).isFalse();
        assertThat(result).isInstanceOf(Created.class);
        assertThat(appointmentCount(tenant.business())).as("a stale Appointment was committed").isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("reducingPaths")
    void withTheGuardThePausedAfterAvailabilityBookingBlocksTheChangeSoNoStaleAppointmentCanCommit(Path path) {
        // The same interleaving as the control, with the real guard: the booking already holds the
        // revision (it is taken before availability), so the change must wait for the booking.
        Mutation mutation = new Mutation(path);
        hooks.observeTransactions(jdbc);
        CountDownLatch afterAvailability = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.on(Point.BEFORE_CUSTOMER, invocation -> {
            afterAvailability.countDown();
            await(release, "booking was never released after availability");
        });
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(afterAvailability, "booking never finished its availability");
        boolean offeredAtBooking = offered();
        CompletableFuture<Void> change = async(() -> {
            mutation.apply();
            return null;
        });
        WaitingBackend waiting = awaitWaiterBlockedBy(jdbc, hooks.observationsOf(threadOf("a")).get(0).backendPid());

        assertThat(waiting.query()).containsIgnoringCase("business_schedule_revision");
        assertThat(change).isNotDone();
        release.countDown();

        assertThat(offeredAtBooking).isTrue();
        assertThat(completed(booking)).isInstanceOf(Created.class);
        completed(change);
        // The booking committed first on the schedule it validated; the change then took effect.
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(offered()).isFalse();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- order 3: an uncommitted change is in flight ----------------------------------------------------

    @ParameterizedTest
    @MethodSource("paths")
    void anUncommittedChangeMakesTheBookingWaitAndAfterItsCommitTheBookingRetries(Path path) throws Exception {
        Mutation mutation = new Mutation(path);
        hooks.observeTransactions(jdbc);
        CountDownLatch atGuard = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        hooks.on(Point.BEFORE_GUARD, invocation -> {
            if (invocation.ordinal() == 1) {
                atGuard.countDown();
                await(releaseFirst, "booking was never released at the guard");
            }
        });
        hooks.on(Point.BEFORE_STAFF_LOCK, invocation -> {
            if (invocation.ordinal() == 2) {
                secondStarted.countDown();
                await(releaseSecond, "the retry was never released");
            }
        });
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(atGuard, "booking never reached the guard");

        CountDownLatch bumped = new CountDownLatch(1);
        CountDownLatch commitChange = new CountDownLatch(1);
        int[] changePid = new int[1];
        CompletableFuture<Void> change = async(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                mutation.apply();
                changePid[0] = backendPid(jdbc);
                bumped.countDown();
                await(commitChange, "the change was never released");
            });
            return null;
        });
        await(bumped, "the change never reached its commit");
        releaseFirst.countDown();
        // The guard waits for the bump that is not yet committed.
        awaitBlockedBy(jdbc, hooks.observationsOf(threadOf("a")).get(0).backendPid(), changePid[0]);
        assertThat(booking).isNotDone();
        commitChange.countDown();
        completed(change);
        boolean fresh = offered();
        await(secondStarted, "the booking did not retry after the change committed");
        releaseSecond.countDown();

        BookingResult result = completed(booking);

        assertBookedExactlyWhenTheFreshViewOffersTheSlot(result, fresh);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = Path.class, names = {"WEEKLY_REPLACE", "CLOSURE_CREATE", "OVERRIDE_DELETE"})
    void anUncommittedChangeThatRollsBackLetsTheWaitingBookingProceedOnTheOldScheduleInOneAttempt(Path path)
            throws Exception {
        Mutation mutation = new Mutation(path);
        hooks.observeTransactions(jdbc);
        boolean before = offered();
        CountDownLatch atGuard = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        hooks.on(Point.BEFORE_GUARD, invocation -> {
            atGuard.countDown();
            await(releaseFirst, "booking was never released at the guard");
        });
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(atGuard, "booking never reached the guard");
        CountDownLatch bumped = new CountDownLatch(1);
        CountDownLatch rollbackChange = new CountDownLatch(1);
        int[] changePid = new int[1];
        CompletableFuture<Void> change = async(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                mutation.apply();
                changePid[0] = backendPid(jdbc);
                bumped.countDown();
                await(rollbackChange, "the change was never released");
                status.setRollbackOnly();
            });
            return null;
        });
        await(bumped, "the change never reached its end");
        releaseFirst.countDown();
        awaitBlockedBy(jdbc, hooks.observationsOf(threadOf("a")).get(0).backendPid(), changePid[0]);
        rollbackChange.countDown();
        completed(change);

        BookingResult result = completed(booking);

        // The rolled-back change never happened, so the booking succeeds on the schedule it saw.
        assertBookedExactlyWhenTheFreshViewOffersTheSlot(result, before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- order 4: the booking holds the guard and the change waits --------------------------------------

    @ParameterizedTest
    @MethodSource("paths")
    void aBookingThatHoldsTheGuardMakesTheChangeWaitAndCommitsBeforeIt(Path path) {
        Mutation mutation = new Mutation(path);
        hooks.observeTransactions(jdbc);
        boolean offeredBefore = offered();
        long revisionBefore = revision();
        CountDownLatch holdingGuard = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.on(Point.BEFORE_SERVICE_LOCK, invocation -> {
            holdingGuard.countDown();
            await(release, "booking was never released while holding the guard");
        });
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(holdingGuard, "booking never took the guard");

        CompletableFuture<Void> change = async(() -> {
            mutation.apply();
            return null;
        });
        WaitingBackend waiting = awaitWaiterBlockedBy(jdbc, hooks.observationsOf(threadOf("a")).get(0).backendPid());

        assertThat(waiting.query()).containsIgnoringCase("business_schedule_revision");
        assertThat(change).isNotDone();
        assertThat(revision()).isEqualTo(revisionBefore);
        release.countDown();

        BookingResult result = completed(booking);
        completed(change);

        // The booking validated and committed against the schedule that existed when it held the
        // guard; the change then applied exactly once.
        assertBookedExactlyWhenTheFreshViewOffersTheSlotBeforeTheChange(result, offeredBefore);
        assertThat(revision()).isEqualTo(revisionBefore + 1);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    private void assertBookedExactlyWhenTheFreshViewOffersTheSlotBeforeTheChange(
            BookingResult result, boolean offeredBefore) {
        if (offeredBefore) {
            assertThat(result).isInstanceOf(Created.class);
            assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        } else {
            assertThat(result).isEqualTo(new BookingResult.SlotUnavailable());
            assertThat(appointmentCount(tenant.business())).isZero();
        }
    }

    @Test
    void theOrderDoesNotMakeAnyPathFlakyTheSameInterleavingTwiceGivesTheSameOutcome() {
        for (int round = 0; round < 2; round++) {
            Mutation mutation = new Mutation(Path.CLOSURE_CREATE);
            CountDownLatch atGuard = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            hooks.reset();
            hooks.on(Point.BEFORE_GUARD, invocation -> {
                if (invocation.ordinal() == 1) {
                    atGuard.countDown();
                    await(release, "booking was never released at the guard");
                }
            });
            String label = "round" + round;
            CompletableFuture<BookingResult> booking = submit(label, req().build());
            await(atGuard, "booking never reached the guard");
            mutation.apply();
            release.countDown();

            assertThat(completed(booking)).isEqualTo(new BookingResult.SlotUnavailable());
            assertThat(TransactionLog.begins(threadOf(label))).hasSize(2);
            // Reopen the day for the next round so the same interleaving is repeatable.
            jdbc.sql("DELETE FROM schedule_exception WHERE business_id = :id")
                    .param("id", tenant.business()).update();
        }
    }
}
