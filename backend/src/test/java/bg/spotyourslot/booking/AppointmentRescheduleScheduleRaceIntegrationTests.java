package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.BookingTestHooks.Point;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reschedule-versus-schedule races of ADR-0025 and ADR-0029 with the real owner reschedule
 * orchestration, the real Business-side {@code AvailabilityQuery}, and the real weekly-schedule and
 * schedule-exception mutations of the owner, for every audited mutation path and three commit
 * orders: before the snapshot, after the snapshot and before the revision lock, and a mutation that
 * is still in flight when the move reaches the revision lock. The expected result is computed by
 * an oracle: a fresh Business-side availability calculation after the mutation committed. Nothing
 * sleeps.
 */
class AppointmentRescheduleScheduleRaceIntegrationTests extends RescheduleAppointmentTest {
    private static final LocalDate OTHER_DAY = LocalDate.of(2026, 10, 5);

    @Autowired StaffWorkingScheduleAdministration schedules;
    @Autowired ScheduleExceptionAdministration exceptions;
    @Autowired AvailabilityQuery availability;

    /** The Appointment that is moved from 09:00 to 10:00 in every test. */
    private CreatedAppointment mover;

    @BeforeEach
    void createTheMover() {
        mover = existing("09:00");
    }

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

    private final class Mutation {
        private final Path path;
        private UUID existingId;

        Mutation(Path path) {
            this.path = path;
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
            return exceptions.create(ownerContext, new CreateScheduleExceptionCommand(
                    kind, staff, date, date, allDay, periods)).exception().id();
        }

        /** Commits the mutation in its own transaction, or joins the caller's if one is active. */
        void apply() {
            switch (path) {
                case WEEKLY_REPLACE -> schedules.replace(ownerContext, tenant.staff(), new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0))), 0L));
                case CLOSURE_CREATE -> create(ScheduleExceptionKind.BUSINESS_CLOSURE, null, THURSDAY, true, List.of());
                case TIME_OFF_CREATE -> create(ScheduleExceptionKind.STAFF_TIME_OFF, tenant.staff(), THURSDAY, true, List.of());
                case OVERRIDE_CREATE -> create(ScheduleExceptionKind.WORKING_DAY_OVERRIDE, tenant.staff(), THURSDAY, false, List.of(period(14, 16)));
                case ADDITIONAL_CREATE -> create(ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS, tenant.staff(), THURSDAY, false, List.of(period(18, 20)));
                case CLOSURE_REPLACE, TIME_OFF_REPLACE -> exceptions.replace(ownerContext, existingId,
                        new ReplaceScheduleExceptionCommand(0L, THURSDAY, THURSDAY, true, List.of()));
                case OVERRIDE_REPLACE -> exceptions.replace(ownerContext, existingId,
                        new ReplaceScheduleExceptionCommand(0L, THURSDAY, THURSDAY, false, List.of(period(14, 16))));
                case ADDITIONAL_REPLACE -> exceptions.replace(ownerContext, existingId,
                        new ReplaceScheduleExceptionCommand(0L, THURSDAY, THURSDAY, false, List.of(period(19, 21))));
                case CLOSURE_DELETE, TIME_OFF_DELETE, OVERRIDE_DELETE, ADDITIONAL_DELETE ->
                        exceptions.delete(ownerContext, existingId, 0L);
            }
        }
    }

    private static ExceptionPeriod period(int from, int to) {
        return new ExceptionPeriod(LocalTime.of(from, 0), LocalTime.of(to, 0));
    }

    /**
     * Whether the fresh, committed Business-side view offers Thursday 10:00 Sofia for the StaffMember
     * while ignoring only the moved Appointment: the oracle of the expected result.
     */
    private boolean offered() {
        return snapshotTransaction().execute(status -> availability.calculateForBusiness(
                        tenant.business(), Duration.ofMinutes(30), tenant.staff(), THURSDAY, THURSDAY,
                        BookingHookConfiguration.NOW, mover.id())
                .slots().stream().anyMatch(slot -> slot.start().equals(at("10:00"))));
    }

    private void assertMovedExactlyWhenTheFreshViewOffersTheSlot(
            CompletableFuture<RescheduledAppointment> move, boolean fresh) {
        if (fresh) {
            assertThat(completed(move).version()).isEqualTo(1L);
            assertThat(instantOf(fullRow(mover.id()), "start_at")).isEqualTo(at("10:00"));
        } else {
            assertThat(failureOf(move)).isInstanceOf(SlotUnavailable.class);
            assertThat(instantOf(fullRow(mover.id()), "start_at")).isEqualTo(at("09:00"));
            assertThat(fullRow(mover.id()).get("version")).isEqualTo(0L);
        }
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("paths")
    void aChangeCommittedBeforeTheSnapshotIsSeenAndTheMoveNeedsOneAttempt(Path path) {
        new Mutation(path).apply();
        boolean fresh = offered();

        assertMovedExactlyWhenTheFreshViewOffersTheSlot(submitMove("a", move(mover).start("10:00")), fresh);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("paths")
    void aChangeCommittedAfterTheSnapshotFailsTheRevisionLockAndTheRetryValidatesTheNewSchedule(Path path) {
        Mutation mutation = new Mutation(path);
        CountDownLatch atGuard = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.on(Point.BEFORE_GUARD, invocation -> {
            if (invocation.ordinal() == 1) {
                atGuard.countDown();
                await(release, "the move was never released at the guard");
            }
        });
        CompletableFuture<RescheduledAppointment> pending = submitMove("a", move(mover).start("10:00"));
        await(atGuard, "the move never reached the guard");

        // The move has its snapshot and the Business, Membership and StaffMember locks, not the revision.
        mutation.apply();
        boolean fresh = offered();
        release.countDown();

        assertMovedExactlyWhenTheFreshViewOffersTheSlot(pending, fresh);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
    }

    @ParameterizedTest
    @MethodSource("paths")
    void aChangeStillInFlightAtTheRevisionLockMakesTheMoveWaitThenFailAndRetryOnTheNewSchedule(Path path) {
        Mutation mutation = new Mutation(path);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        int[] pid = new int[1];
        CompletableFuture<Void> inFlight = async(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                mutation.apply();
                pid[0] = backendPid(jdbc);
                held.countDown();
                await(commit, "the in-flight change was never committed");
            });
            return null;
        });
        await(held, "the in-flight change never started");
        CompletableFuture<RescheduledAppointment> pending = submitMove("a", move(mover).start("10:00"));
        awaitWaiterBlockedBy(jdbc, pid[0]);
        assertThat(pending).isNotDone();

        commit.countDown();
        completed(inFlight);
        boolean fresh = offered();

        assertMovedExactlyWhenTheFreshViewOffersTheSlot(pending, fresh);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
    }

    static Stream<Path> reducingPaths() {
        return Stream.of(Path.WEEKLY_REPLACE, Path.CLOSURE_CREATE);
    }

    @ParameterizedTest
    @MethodSource("reducingPaths")
    void withTheGuardBypassedTheSameInterleavingMovesToATimeTheFreshViewDoesNotOffer(Path path) {
        // CONTROL RUN: proves the interleaving above is the stale-availability race that the revision guard stops.
        Mutation mutation = new Mutation(path);
        hooks.bypassScheduleGuard(true);
        CountDownLatch atGuard = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.on(Point.BEFORE_GUARD, invocation -> {
            atGuard.countDown();
            await(release, "the move was never released at the guard");
        });
        CompletableFuture<RescheduledAppointment> pending = submitMove("a", move(mover).start("10:00"));
        await(atGuard, "the move never reached the guard");
        mutation.apply();
        release.countDown();

        assertThat(completed(pending).version()).isEqualTo(1L);
        assertThat(offered()).isFalse();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }
}
