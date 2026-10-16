package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.domain.ManualVisitTerms.Claim;
import bg.spotyourslot.booking.domain.ManualVisitTerms.LockedService;
import bg.spotyourslot.booking.domain.ManualVisitTerms.Visit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualVisitTermsTests {
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private static Claim claim(UUID id, String name, int minutes, long cents) {
        return new Claim(id, name, minutes, cents);
    }

    private static LockedService locked(UUID id, String name, int minutes, String price, boolean active) {
        return new LockedService(id, name, minutes, new BigDecimal(price), active);
    }

    // ---- bounds ---------------------------------------------------------------------

    @Test
    void oneToFiveDistinctServicesWithinTheBoundsAreValid() {
        List<Claim> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            five.add(claim(UUID.randomUUID(), "Услуга", 96, 999_999_999_999L));
        }

        ManualVisitTerms.requireValidClaims(List.of(claim(A, "Услуга", 480, 0)));
        ManualVisitTerms.requireValidClaims(five);
    }

    @Test
    void everyViolatedBoundIsRejectedWithAFixedMessage() {
        List<List<Claim>> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(List.of());
        invalid.add(List.of(claim(A, "x", 0, 0)));
        invalid.add(List.of(claim(A, "x", 481, 0)));
        invalid.add(List.of(claim(A, "x", 30, -1)));
        invalid.add(List.of(claim(A, "x", 30, 1_000_000_000_000L)));
        invalid.add(List.of(claim(A, "", 30, 0)));
        invalid.add(List.of(claim(A, "x".repeat(201), 30, 0)));
        invalid.add(List.of(claim(A, "x", 30, 0), claim(A, "y", 30, 0)));
        invalid.add(List.of(claim(A, "x", 300, 0), claim(B, "y", 181, 0)));
        List<Claim> six = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            six.add(claim(UUID.randomUUID(), "x", 1, 0));
        }
        invalid.add(six);

        for (List<Claim> claims : invalid) {
            assertThatThrownBy(() -> ManualVisitTerms.requireValidClaims(claims))
                    .isInstanceOf(InvalidManualVisit.class)
                    .hasMessage("Manual Appointment request is invalid")
                    .hasNoCause();
        }
    }

    @Test
    void theNoteUsesTheSharedNotePolicy() {
        assertThat(ManualVisitTerms.normalizeInternalNote(null)).isNull();
        assertThat(ManualVisitTerms.normalizeInternalNote("  \r\n ")).isNull();
        assertThat(ManualVisitTerms.normalizeInternalNote("  a\r\nb  ")).isEqualTo("a\nb");
        assertThat(ManualVisitTerms.normalizeInternalNote("x".repeat(500))).hasSize(500);
        assertThatThrownBy(() -> ManualVisitTerms.normalizeInternalNote("x".repeat(501)))
                .isInstanceOf(InvalidManualVisit.class);
        assertThatThrownBy(() -> ManualVisitTerms.normalizeInternalNote("a\u0001b"))
                .isInstanceOf(InvalidManualVisit.class);
        assertThatThrownBy(() -> ManualVisitTerms.normalizeInternalNote("\ud800"))
                .isInstanceOf(InvalidManualVisit.class);
    }

    // ---- availability and terms ---------------------------------------------------------------

    @Test
    void aMissingOrInactiveServiceIsNotAvailableAndARepeatedRowIsAnInconsistency() {
        List<Claim> claims = List.of(claim(A, "a", 30, 100), claim(B, "b", 30, 100));

        assertThat(ManualVisitTerms.allAvailable(claims, List.of(
                locked(A, "a", 30, "1.00", true), locked(B, "b", 30, "1.00", true)))).isTrue();
        assertThat(ManualVisitTerms.allAvailable(claims, List.of(locked(A, "a", 30, "1.00", true)))).isFalse();
        assertThat(ManualVisitTerms.allAvailable(claims, List.of(
                locked(A, "a", 30, "1.00", true), locked(B, "b", 30, "1.00", false)))).isFalse();
        assertThatThrownBy(() -> ManualVisitTerms.allAvailable(claims, List.of(
                locked(A, "a", 30, "1.00", true), locked(A, "a", 30, "1.00", true))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ManualVisitTerms.allAvailable(claims, List.of(
                locked(UUID.randomUUID(), "z", 30, "1.00", true))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void termsMustMatchByNameMinutesAndCentsExactlyAndAreComparedByIdentifierNotPosition() {
        List<LockedService> current = List.of(
                locked(A, "Масаж", 30, "25.00", true), locked(B, "Баня", 20, "10.50", true));

        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(B, "Баня", 20, 1050), claim(A, "Масаж", 30, 2500)), current)).isTrue();
        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(A, "Масаж", 30, 2500), claim(B, "Баня", 20, 1050)), current)).isTrue();
        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(A, "Масаж ", 30, 2500), claim(B, "Баня", 20, 1050)), current)).isFalse();
        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(A, "масаж", 30, 2500), claim(B, "Баня", 20, 1050)), current)).isFalse();
        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(A, "Масаж", 31, 2500), claim(B, "Баня", 20, 1050)), current)).isFalse();
        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(A, "Масаж", 30, 2501), claim(B, "Баня", 20, 1050)), current)).isFalse();
        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(A, "Масаж", 30, 2500)), current)).isFalse();
        assertThat(ManualVisitTerms.termsMatch(
                List.of(claim(A, "Масаж", 30, 2500), claim(UUID.randomUUID(), "Баня", 20, 1050)), current))
                .isFalse();
    }

    // ---- the calculation ----------------------------------------------------------------------

    @Test
    void theVisitKeepsTheGivenOrderAsPositionsAndSumsExactly() {
        Visit visit = ManualVisitTerms.plan(List.of(
                locked(B, "Баня", 20, "10.50", true), locked(A, "Масаж", 35, "0.10", true)));

        assertThat(visit.lines()).extracting(AppointmentServiceLine::position).containsExactly(1, 2);
        assertThat(visit.lines()).extracting(AppointmentServiceLine::serviceId).containsExactly(B, A);
        assertThat(visit.totalMinutes()).isEqualTo(55);
        assertThat(visit.totalPrice()).isEqualByComparingTo("10.60");
        assertThat(visit.toString()).doesNotContain("Баня");
    }

    @Test
    void aSingleServiceIsTheNormalCase() {
        Visit visit = ManualVisitTerms.plan(List.of(locked(A, "Масаж", 30, "25.00", true)));

        assertThat(visit.lines()).hasSize(1);
        assertThat(visit.totalMinutes()).isEqualTo(30);
        assertThat(visit.totalPrice()).isEqualByComparingTo("25.00");
    }

    @Test
    void fiveServicesOfTheLargestPriceTotalExactlyAndFitTheVisitPriceBound() {
        List<LockedService> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            five.add(locked(UUID.randomUUID(), "Услуга " + index, 96, "9999999999.99", true));
        }

        Visit visit = ManualVisitTerms.plan(five);

        assertThat(visit.totalMinutes()).isEqualTo(480);
        assertThat(visit.totalPrice()).isEqualByComparingTo("49999999999.95");
        AppointmentRules.requireVisitPrice(visit.totalPrice());
    }

    @Test
    void recordsRedactTheirValues() {
        assertThat(claim(A, "SECRET-NAME", 30, 100).toString()).doesNotContain("SECRET");
        assertThat(locked(A, "SECRET-NAME", 30, "1.00", true).toString()).doesNotContain("SECRET");
    }

    // ---- NewAppointment ------------------------------------------------------------------------

    private static NewAppointment manual(BookingAttempt attempt, String internalNote) {
        UUID service = UUID.randomUUID();
        return new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), service, UUID.randomUUID(),
                AppointmentSource.MANUAL, Instant.parse("2026-10-08T07:00:00Z"), 30, new BigDecimal("25.00"),
                "Europe/Sofia", "Масаж", "Мария", null, "ABCDEFGHJK", attempt,
                Instant.parse("2026-10-01T08:00:00Z"),
                List.of(new AppointmentServiceLine(1, service, "Масаж", 30, new BigDecimal("25.00"))),
                internalNote);
    }

    @Test
    void aManualAppointmentCarriesTheInternalNoteAndNeverAnAttempt() {
        NewAppointment appointment = manual(null, "частна бележка");

        assertThat(appointment.internalNote()).isEqualTo("частна бележка");
        assertThat(appointment.toString()).doesNotContain("частна");
        assertThatThrownBy(() -> manual(AppointmentFixturesAttempt.attempt(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A manual Appointment has no booking attempt");
        assertThatThrownBy(() -> manual(null, " padded "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A syntactically valid attempt for the invariant test. */
    private static final class AppointmentFixturesAttempt {
        static BookingAttempt attempt() {
            byte[] digest = new byte[32];
            return new BookingAttempt(digest, digest.clone(), 2, 1);
        }
    }
}
