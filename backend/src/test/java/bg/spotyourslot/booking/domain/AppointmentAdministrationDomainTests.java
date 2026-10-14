package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The V14 facts of a persisted {@link Appointment}: the cancellation attribution invariant mirrored
 * from the database CHECK, and the independence of the internal note from the lifecycle.
 */
class AppointmentAdministrationDomainTests {
    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final UUID SERVICE = UUID.randomUUID();

    private static Appointment appointment(
            AppointmentStatus status, Instant cancelledAt, AppointmentCancelledBy by, String note) {
        Instant end = START.plus(Duration.ofMinutes(30));
        return new Appointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SERVICE, UUID.randomUUID(),
                AppointmentSource.MANUAL, status, START, end, end, "Europe/Sofia", 30,
                new BigDecimal("10.00"), "Услуга", "Мария", null, "ABCDEFGHJK", null, 0, CREATED,
                CREATED, cancelledAt, by, note,
                List.of(new AppointmentServiceLine(1, SERVICE, "Услуга", 30, new BigDecimal("10.00"))));
    }

    @Test
    void everyAllowedCombinationIsAccepted() {
        assertThat(appointment(AppointmentStatus.CONFIRMED, null, null, null).blocksTime()).isTrue();
        assertThat(appointment(
                AppointmentStatus.CANCELLED, CREATED, AppointmentCancelledBy.BUSINESS, null).blocksTime())
                .isFalse();
        assertThat(appointment(
                AppointmentStatus.CANCELLED, null, AppointmentCancelledBy.UNKNOWN, null).blocksTime())
                .isFalse();
    }

    @Test
    void everyForbiddenCombinationIsRefusedIncludingTheMissingActor() {
        List<Appointment> none = List.of();
        assertThat(none).isEmpty();
        assertThatThrownBy(() -> appointment(AppointmentStatus.CANCELLED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointment(AppointmentStatus.CANCELLED, CREATED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointment(
                AppointmentStatus.CANCELLED, null, AppointmentCancelledBy.BUSINESS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointment(
                AppointmentStatus.CANCELLED, CREATED, AppointmentCancelledBy.UNKNOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointment(
                AppointmentStatus.CONFIRMED, CREATED, AppointmentCancelledBy.BUSINESS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointment(
                AppointmentStatus.CONFIRMED, null, AppointmentCancelledBy.UNKNOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> appointment(
                AppointmentStatus.CONFIRMED, CREATED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCancellationMustNotPrecedeCreation() {
        assertThatThrownBy(() -> appointment(
                AppointmentStatus.CANCELLED, CREATED.minusSeconds(1), AppointmentCancelledBy.BUSINESS, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theInternalNoteIsValidOnEveryStatusAndFollowsTheNoteRule() {
        assertThat(appointment(AppointmentStatus.CONFIRMED, null, null, "бележка").internalNote())
                .isEqualTo("бележка");
        assertThat(appointment(
                AppointmentStatus.CANCELLED, CREATED, AppointmentCancelledBy.BUSINESS, "бележка")
                .internalNote()).isEqualTo("бележка");
        assertThat(appointment(
                AppointmentStatus.CANCELLED, null, AppointmentCancelledBy.UNKNOWN, "бележка")
                .internalNote()).isEqualTo("бележка");
        for (String invalid : List.of("", " бележка", "бележка\n", "ред\u0001", "я".repeat(501))) {
            assertThatThrownBy(() -> appointment(AppointmentStatus.CONFIRMED, null, null, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theToStringNeverShowsTheNoteTheActorOrTheNames() {
        Appointment appointment = appointment(
                AppointmentStatus.CANCELLED, CREATED, AppointmentCancelledBy.BUSINESS, "ТАЙНА-БЕЛЕЖКА");

        assertThat(appointment.toString()).isEqualTo("Appointment[redacted]");
    }
}
