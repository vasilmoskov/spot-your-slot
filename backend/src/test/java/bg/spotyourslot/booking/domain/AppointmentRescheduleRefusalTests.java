package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.domain.AppointmentTransitions.Applied;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Rejection;
import bg.spotyourslot.booking.domain.AppointmentTransitions.StaffTarget;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pre-lock refusals of a reschedule or reassignment ({@code rescheduleRefusal}): the order of
 * ADR-0029 section 11 and the agreement with {@code reschedule}, which adds only the start-before-now
 * decision that the orchestration makes after the eligibility locks.
 */
class AppointmentRescheduleRefusalTests {
    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final Instant START = Instant.parse("2026-10-01T07:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID OTHER_STAFF = UUID.randomUUID();

    private static Appointment appointment(AppointmentStatus status, long version, Instant start) {
        UUID id = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        Instant end = start.plusSeconds(1800);
        boolean cancelled = status == AppointmentStatus.CANCELLED;
        return new Appointment(
                id, UUID.randomUUID(), UUID.randomUUID(), service, STAFF, AppointmentSource.MANUAL, status,
                start, end, end, "Europe/Sofia", 30, new BigDecimal("25.00"), "Услуга", "Мария", null,
                "ABCDEFGHJK", null, version, CREATED, CREATED,
                cancelled ? CREATED : null, cancelled ? AppointmentCancelledBy.BUSINESS : null, null,
                List.of(new AppointmentServiceLine(1, service, "Услуга", 30, new BigDecimal("25.00"))));
    }

    private static Rejection refusal(Appointment current, long version, Instant start, UUID target, Instant now) {
        return AppointmentTransitions.rescheduleRefusal(current, version, start, target, now);
    }

    @Test
    void aMatchingConfirmedFutureAppointmentWithAChangeIsNotRefused() {
        Appointment current = appointment(AppointmentStatus.CONFIRMED, 0, START);

        assertThat(refusal(current, 0, START.plusSeconds(900), null, NOW)).isNull();
        assertThat(refusal(current, 0, START, OTHER_STAFF, NOW)).isNull();
        assertThat(refusal(current, 0, START.plusSeconds(900), OTHER_STAFF, NOW)).isNull();
    }

    @Test
    void theOrderIsStaleVersionThenStateThenStartedThenNoChange() {
        Appointment cancelledAndStarted = appointment(AppointmentStatus.CANCELLED, 1, START);

        // Everything is wrong at once: the stale version comes first, then the state, then "started".
        assertThat(refusal(cancelledAndStarted, 0, START, null, START)).isEqualTo(Rejection.STALE_VERSION);
        assertThat(refusal(cancelledAndStarted, 1, START, null, START)).isEqualTo(Rejection.NOT_CONFIRMED);
        Appointment started = appointment(AppointmentStatus.CONFIRMED, 0, START);
        assertThat(refusal(started, 0, START, null, START)).isEqualTo(Rejection.ALREADY_STARTED);
        assertThat(refusal(started, 0, START, null, START.minusSeconds(1))).isEqualTo(Rejection.NO_CHANGE);
        assertThat(refusal(started, 5, START, null, START.minusSeconds(1))).isEqualTo(Rejection.STALE_VERSION);
    }

    @Test
    void namingTheCurrentStaffMemberIsNotAReassignment() {
        Appointment current = appointment(AppointmentStatus.CONFIRMED, 0, START);

        assertThat(refusal(current, 0, START, STAFF, NOW)).isEqualTo(Rejection.NO_CHANGE);
        assertThat(refusal(current, 0, START, null, NOW)).isEqualTo(Rejection.NO_CHANGE);
        assertThat(refusal(current, 0, START.plusSeconds(900), STAFF, NOW)).isNull();
    }

    @Test
    void anAppointmentStartingExactlyNowHasStartedAndOneMicrosecondLaterHasNot() {
        Appointment current = appointment(AppointmentStatus.CONFIRMED, 0, START);

        assertThat(refusal(current, 0, START.plusSeconds(900), null, START)).isEqualTo(Rejection.ALREADY_STARTED);
        assertThat(refusal(current, 0, START.plusSeconds(900), null, START.minusNanos(1_000))).isNull();
    }

    @Test
    void aStartBeforeNowIsNotAPreLockRefusalButRescheduleRefusesItAfterward() {
        Appointment current = appointment(AppointmentStatus.CONFIRMED, 0, START);
        Instant past = NOW.minusSeconds(900);

        assertThat(refusal(current, 0, past, null, NOW)).isNull();
        assertThat(AppointmentTransitions.reschedule(current, 0, past, null, NOW))
                .isEqualTo(new AppointmentTransitions.Rejected<>(Rejection.START_BEFORE_NOW));
        assertThat(AppointmentTransitions.reschedule(current, 0, NOW, null, NOW)).isInstanceOf(Applied.class);
    }

    @Test
    void rescheduleAndTheRefusalAgreeOnEveryPreLockOutcome() {
        List<Appointment> appointments = List.of(
                appointment(AppointmentStatus.CONFIRMED, 0, START),
                appointment(AppointmentStatus.CANCELLED, 1, START),
                appointment(AppointmentStatus.CONFIRMED, 0, NOW));
        for (Appointment current : appointments) {
            for (long version : new long[] {0, 1}) {
                for (Instant requested : List.of(current.startAt(), current.startAt().plusSeconds(900))) {
                    for (UUID target : new UUID[] {null, STAFF, OTHER_STAFF}) {
                        Rejection expected = refusal(current, version, requested, target, NOW);
                        var result = AppointmentTransitions.reschedule(
                                current, version, requested, target == null ? null : new StaffTarget(target, "Име"), NOW);
                        if (expected == null) {
                            assertThat(result).isInstanceOf(Applied.class);
                        } else {
                            assertThat(result).isEqualTo(new AppointmentTransitions.Rejected<>(expected));
                        }
                    }
                }
            }
        }
    }
}
