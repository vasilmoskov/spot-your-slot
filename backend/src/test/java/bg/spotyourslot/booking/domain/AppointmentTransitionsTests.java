package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.domain.AppointmentTransitions.Applied;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.NoteUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Rejected;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Rejection;
import bg.spotyourslot.booking.domain.AppointmentTransitions.RescheduleUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.StaffTarget;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Transition;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pure transition rules of Appointment administration: which refusal wins, what an applied
 * update carries, what never changes, and that every value stays redacted.
 */
class AppointmentTransitionsTests {
    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00.123456789Z");
    private static final UUID BUSINESS = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID OTHER_STAFF = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();

    private static Appointment appointment(
            AppointmentStatus status, Instant start, long version, String note) {
        Instant end = start.plus(Duration.ofMinutes(45));
        boolean cancelled = status == AppointmentStatus.CANCELLED;
        return new Appointment(
                UUID.randomUUID(), BUSINESS, UUID.randomUUID(), SERVICE, STAFF,
                AppointmentSource.MANUAL, status, start, end, end, "Europe/Sofia", 45,
                new BigDecimal("30.00"), "Услуга", "Мария", null, "ABCDEFGHJK", null, version,
                CREATED, CREATED, cancelled ? CREATED : null,
                cancelled ? AppointmentCancelledBy.BUSINESS : null, note,
                List.of(new AppointmentServiceLine(1, SERVICE, "Услуга", 45, new BigDecimal("30.00"))));
    }

    private static Appointment confirmed() {
        return appointment(AppointmentStatus.CONFIRMED, START, 3, null);
    }

    private static <T> T applied(Transition<T> transition) {
        assertThat(transition).isInstanceOf(Applied.class);
        return ((Applied<T>) transition).update();
    }

    private static Rejection rejection(Transition<?> transition) {
        assertThat(transition).isInstanceOf(Rejected.class);
        return ((Rejected<?>) transition).reason();
    }

    // ---- reschedule and reassign ---------------------------------------------

    @Test
    void aTimeOnlyMoveKeepsTheStaffMemberAndItsSnapshotAndDerivesTheEndFromTheStoredDuration() {
        Appointment current = confirmed();
        Instant target = START.plusSeconds(15 * 60L);

        RescheduleUpdate update = applied(AppointmentTransitions.reschedule(current, 3, target, null, NOW));

        assertThat(update.startAt()).isEqualTo(target);
        assertThat(update.endAt()).isEqualTo(target.plus(Duration.ofMinutes(45)));
        assertThat(update.occupiedUntil()).isEqualTo(update.endAt());
        assertThat(update.staffMemberId()).isEqualTo(STAFF);
        assertThat(update.staffDisplayName()).isEqualTo("Мария");
        assertThat(update.expectedVersion()).isEqualTo(3);
        assertThat(update.expectedStaffMemberId()).isEqualTo(STAFF);
        assertThat(update.expectedStartAt()).isEqualTo(START);
        assertThat(update.businessId()).isEqualTo(BUSINESS);
        assertThat(update.appointmentId()).isEqualTo(current.id());
        // The instant is truncated to the microseconds PostgreSQL stores.
        assertThat(update.updatedAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00.123456Z"));
    }

    @Test
    void aReassignmentRefreshesTheSnapshotFromTheTargetAndMayChangeTheStartToo() {
        RescheduleUpdate staffOnly = applied(AppointmentTransitions.reschedule(
                confirmed(), 3, START, new StaffTarget(OTHER_STAFF, "Иван"), NOW));
        assertThat(staffOnly.staffMemberId()).isEqualTo(OTHER_STAFF);
        assertThat(staffOnly.staffDisplayName()).isEqualTo("Иван");
        assertThat(staffOnly.startAt()).isEqualTo(START);

        RescheduleUpdate both = applied(AppointmentTransitions.reschedule(
                confirmed(), 3, START.plusSeconds(3600), new StaffTarget(OTHER_STAFF, "Иван"), NOW));
        assertThat(both.staffMemberId()).isEqualTo(OTHER_STAFF);
        assertThat(both.startAt()).isEqualTo(START.plusSeconds(3600));
    }

    @Test
    void aTargetEqualToTheCurrentStaffMemberIsATimeOnlyMoveAndNeverRewritesTheSnapshot() {
        RescheduleUpdate update = applied(AppointmentTransitions.reschedule(
                confirmed(), 3, START.plusSeconds(900), new StaffTarget(STAFF, "Друго име"), NOW));

        assertThat(update.staffDisplayName()).isEqualTo("Мария");
    }

    @Test
    void aRequestThatChangesNothingIsRefused() {
        assertThat(rejection(AppointmentTransitions.reschedule(confirmed(), 3, START, null, NOW)))
                .isEqualTo(Rejection.NO_CHANGE);
        assertThat(rejection(AppointmentTransitions.reschedule(
                confirmed(), 3, START, new StaffTarget(STAFF, "Мария"), NOW)))
                .isEqualTo(Rejection.NO_CHANGE);
    }

    @Test
    void theRefusalsAreCheckedInTheDocumentedOrder() {
        Appointment cancelledAndStarted = appointment(
                AppointmentStatus.CANCELLED, NOW.minusSeconds(3600), 3, null);
        // Stale version beats every state problem.
        assertThat(rejection(AppointmentTransitions.reschedule(
                cancelledAndStarted, 2, START, null, NOW))).isEqualTo(Rejection.STALE_VERSION);
        // Then the status, then the start, then no-change, then the requested start.
        assertThat(rejection(AppointmentTransitions.reschedule(
                cancelledAndStarted, 3, START, null, NOW))).isEqualTo(Rejection.NOT_CONFIRMED);
        Appointment started = appointment(AppointmentStatus.CONFIRMED, NOW.minusSeconds(60), 3, null);
        assertThat(rejection(AppointmentTransitions.reschedule(
                started, 3, started.startAt(), null, NOW))).isEqualTo(Rejection.ALREADY_STARTED);
        assertThat(rejection(AppointmentTransitions.reschedule(
                confirmed(), 3, NOW.minusSeconds(1), null, NOW))).isEqualTo(Rejection.START_BEFORE_NOW);
    }

    @Test
    void anAppointmentStartingExactlyNowHasStartedButARequestedStartExactlyNowIsAllowed() {
        Instant exact = Instant.parse("2026-11-01T10:00:00Z");
        Appointment startingNow = appointment(AppointmentStatus.CONFIRMED, exact, 3, null);
        assertThat(rejection(AppointmentTransitions.reschedule(
                startingNow, 3, exact.plusSeconds(900), null, exact))).isEqualTo(Rejection.ALREADY_STARTED);

        RescheduleUpdate update = applied(AppointmentTransitions.reschedule(
                confirmed(), 3, exact, null, exact));
        assertThat(update.startAt()).isEqualTo(exact);
    }

    @Test
    void anInstantOutsideTheSupportedRangeOrBeforeCreationIsACallerError() {
        assertThatThrownBy(() -> AppointmentTransitions.reschedule(
                confirmed(), 3, Instant.parse("9999-12-31T23:59:59Z"), null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppointmentTransitions.cancel(confirmed(), 3, CREATED.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaffTarget(OTHER_STAFF, " Иван"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- cancel --------------------------------------------------------------

    @Test
    void cancellationRecordsTheAttemptInstantAndNothingElse() {
        CancelUpdate update = applied(AppointmentTransitions.cancel(confirmed(), 3, NOW));

        assertThat(update.cancelledAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00.123456Z"));
        assertThat(update.expectedVersion()).isEqualTo(3);
    }

    @Test
    void cancellationRefusesAStaleVersionACancelledAndAStartedAppointment() {
        assertThat(rejection(AppointmentTransitions.cancel(confirmed(), 4, NOW)))
                .isEqualTo(Rejection.STALE_VERSION);
        assertThat(rejection(AppointmentTransitions.cancel(
                appointment(AppointmentStatus.CANCELLED, START, 3, null), 3, NOW)))
                .isEqualTo(Rejection.NOT_CONFIRMED);
        assertThat(rejection(AppointmentTransitions.cancel(
                appointment(AppointmentStatus.CONFIRMED, NOW.truncatedTo(ChronoUnit.MICROS), 3, null),
                3, NOW)))
                .isEqualTo(Rejection.ALREADY_STARTED);
        assertThat(rejection(AppointmentTransitions.cancel(
                appointment(AppointmentStatus.CONFIRMED, NOW.minusSeconds(1), 3, null), 3, NOW)))
                .isEqualTo(Rejection.ALREADY_STARTED);
    }

    // ---- note ----------------------------------------------------------------

    @Test
    void theNoteIsNormalizedWithTheSharedPolicyAndMayBeClearedByABlankNote() {
        NoteUpdate set = applied(AppointmentTransitions.editInternalNote(
                confirmed(), 3, "  ред едно\r\nред две\rред три  ", NOW));
        assertThat(set.internalNote()).isEqualTo("ред едно\nред две\nред три");

        NoteUpdate cleared = applied(AppointmentTransitions.editInternalNote(
                appointment(AppointmentStatus.CONFIRMED, START, 3, "стара"), 3, " \n\t ", NOW));
        assertThat(cleared.internalNote()).isNull();
    }

    @Test
    void theNoteMayBeEditedOnEveryStatusAndOnStartedAppointments() {
        Appointment cancelled = appointment(AppointmentStatus.CANCELLED, START, 3, null);
        Appointment started = appointment(AppointmentStatus.CONFIRMED, NOW.minusSeconds(3600), 3, null);

        assertThat(applied(AppointmentTransitions.editInternalNote(cancelled, 3, "бележка", NOW))
                .internalNote()).isEqualTo("бележка");
        assertThat(applied(AppointmentTransitions.editInternalNote(started, 3, "бележка", NOW))
                .internalNote()).isEqualTo("бележка");
    }

    @Test
    void anUnchangedNoteIsRefusedAndAStaleVersionIsReportedFirst() {
        Appointment current = appointment(AppointmentStatus.CONFIRMED, START, 3, "бележка");

        assertThat(rejection(AppointmentTransitions.editInternalNote(current, 3, " бележка ", NOW)))
                .isEqualTo(Rejection.NO_CHANGE);
        assertThat(rejection(AppointmentTransitions.editInternalNote(current, 2, "друга", NOW)))
                .isEqualTo(Rejection.STALE_VERSION);
        assertThat(rejection(AppointmentTransitions.editInternalNote(confirmed(), 3, null, NOW)))
                .isEqualTo(Rejection.NO_CHANGE);
    }

    @Test
    void aTooLongOrControlCharacterNoteIsInvalid() {
        assertThat(rejection(AppointmentTransitions.editInternalNote(
                confirmed(), 3, "я".repeat(501), NOW))).isEqualTo(Rejection.INVALID_NOTE);
        assertThat(rejection(AppointmentTransitions.editInternalNote(
                confirmed(), 3, "ред\u0001ред", NOW))).isEqualTo(Rejection.INVALID_NOTE);
        assertThat(rejection(AppointmentTransitions.editInternalNote(
                confirmed(), 3, "\uD83D", NOW))).isEqualTo(Rejection.INVALID_NOTE);
        assertThat(applied(AppointmentTransitions.editInternalNote(
                confirmed(), 3, "😀".repeat(500), NOW)).internalNote()).hasSize(1000);
    }

    // ---- redaction -----------------------------------------------------------

    @Test
    void everyValueCarryingPersonalDataIsRedacted() {
        Appointment current = appointment(AppointmentStatus.CONFIRMED, START, 3, "ЧУВСТВИТЕЛНА-БЕЛЕЖКА");
        NoteUpdate note = applied(AppointmentTransitions.editInternalNote(current, 3, "ТАЙНА-БЕЛЕЖКА", NOW));
        RescheduleUpdate move = applied(AppointmentTransitions.reschedule(
                current, 3, START.plusSeconds(900), new StaffTarget(OTHER_STAFF, "Иван"), NOW));
        CancelUpdate cancel = applied(AppointmentTransitions.cancel(current, 3, NOW));

        for (Object value : List.of(current, note, move, cancel, new StaffTarget(OTHER_STAFF, "Иван"))) {
            assertThat(value.toString()).doesNotContain("БЕЛЕЖКА", "Иван", "Мария", "BUSINESS");
        }
    }
}
