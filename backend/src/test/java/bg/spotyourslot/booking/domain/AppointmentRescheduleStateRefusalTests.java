package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.domain.AppointmentTransitions.Rejection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code rescheduleStateRefusal}, the version-free state check behind the reschedule options: it must
 * decide exactly like the state part of {@code rescheduleRefusal} for the same Appointment and instant.
 */
class AppointmentRescheduleStateRefusalTests {
    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant START = Instant.parse("2026-10-01T07:00:00Z");

    private static Appointment appointment(AppointmentStatus status, long version, Instant start) {
        UUID service = UUID.randomUUID();
        Instant end = start.plusSeconds(1800);
        boolean cancelled = status == AppointmentStatus.CANCELLED;
        return new Appointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), service, UUID.randomUUID(),
                AppointmentSource.MANUAL, status, start, end, end, "Europe/Sofia", 30, new BigDecimal("25.00"),
                "Услуга", "Мария", null, "ABCDEFGHJK", null, version, CREATED, CREATED,
                cancelled ? CREATED : null, cancelled ? AppointmentCancelledBy.BUSINESS : null, null,
                List.of(new AppointmentServiceLine(1, service, "Услуга", 30, new BigDecimal("25.00"))));
    }

    @Test
    void aConfirmedFutureAppointmentIsNotRefusedWhateverItsVersion() {
        assertThat(AppointmentTransitions.rescheduleStateRefusal(
                appointment(AppointmentStatus.CONFIRMED, 0, START), START.minusSeconds(1))).isNull();
        assertThat(AppointmentTransitions.rescheduleStateRefusal(
                appointment(AppointmentStatus.CONFIRMED, 7, START), START.minusSeconds(1))).isNull();
    }

    @Test
    void aCancelledAppointmentIsNotConfirmedAndAStartedOneHasStarted() {
        assertThat(AppointmentTransitions.rescheduleStateRefusal(
                appointment(AppointmentStatus.CANCELLED, 1, START), START.minusSeconds(60)))
                .isEqualTo(Rejection.NOT_CONFIRMED);
        assertThat(AppointmentTransitions.rescheduleStateRefusal(
                appointment(AppointmentStatus.CONFIRMED, 0, START), START)).isEqualTo(Rejection.ALREADY_STARTED);
        assertThat(AppointmentTransitions.rescheduleStateRefusal(
                appointment(AppointmentStatus.CANCELLED, 1, START), START)).isEqualTo(Rejection.NOT_CONFIRMED);
    }

    @Test
    void itAgreesWithTheStatePartOfTheReschedulesOwnRefusal() {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            for (Instant now : List.of(START.minusSeconds(1), START, START.plusSeconds(1))) {
                Appointment current = appointment(status, 3, START);
                Rejection own = AppointmentTransitions.rescheduleRefusal(
                        current, current.version(), START.plusSeconds(900), null, now);
                assertThat(AppointmentTransitions.rescheduleStateRefusal(current, now)).isEqualTo(own);
            }
        }
    }
}
