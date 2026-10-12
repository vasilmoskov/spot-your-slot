package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The visit rules of the Appointment domain (ADR-0027, phase M1): one to five distinct Service
 * lines at positions 1 to n, the headline equal to line 1, the totals equal to the exact sums, the
 * exact money bounds, and the unchanged single-Service constructors. All values are synthetic.
 */
class AppointmentVisitDomainTests {
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final String MAX_SERVICE_PRICE = "9999999999.99";

    private static AppointmentServiceLine line(int position, int minutes, String price) {
        return new AppointmentServiceLine(position, UUID.randomUUID(), "Услуга " + position, minutes,
                new BigDecimal(price));
    }

    private static List<AppointmentServiceLine> lines(int count, int minutes, String price) {
        List<AppointmentServiceLine> lines = new ArrayList<>();
        for (int position = 1; position <= count; position++) {
            lines.add(line(position, minutes, price));
        }
        return lines;
    }

    private static NewAppointment visit(
            List<AppointmentServiceLine> lines, int minutes, String price) {
        AppointmentServiceLine first = lines.get(0);
        return new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), first.serviceId(),
                UUID.randomUUID(), AppointmentSource.MANUAL, START, minutes, new BigDecimal(price),
                "Europe/Sofia", first.serviceName(), "Мария Тестова", null, "ABCDEFGHJK", null,
                CREATED, lines);
    }

    private static Appointment stored(
            List<AppointmentServiceLine> lines, int minutes, String price) {
        AppointmentServiceLine first = lines.get(0);
        return new Appointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), first.serviceId(),
                UUID.randomUUID(), AppointmentSource.MANUAL, AppointmentStatus.CONFIRMED, START,
                START.plusSeconds(minutes * 60L), START.plusSeconds(minutes * 60L), "Europe/Sofia",
                minutes, new BigDecimal(price), first.serviceName(), "Мария Тестова", null,
                "ABCDEFGHJK", null, 0, CREATED, CREATED, lines);
    }

    // ---- accepted visits -----------------------------------------------------

    @Test
    void aVisitOfThreeServicesIsAcceptedWithExactTotalsAndImmutableLines() {
        List<AppointmentServiceLine> source = lines(3, 20, "10.10");

        NewAppointment visit = visit(source, 60, "30.30");

        assertThat(visit.serviceCount()).isEqualTo(3);
        assertThat(visit.serviceLines()).containsExactlyElementsOf(source);
        assertThat(visit.endAt()).isEqualTo(START.plusSeconds(3600));
        assertThat(visit.occupiedUntil()).isEqualTo(visit.endAt());
        assertThat(visit.priceEur()).isEqualTo(new BigDecimal("30.30"));
        // The record keeps its own copy: changing the source list changes nothing.
        source.clear();
        assertThat(visit.serviceLines()).hasSize(3);
        assertThatThrownBy(() -> visit.serviceLines().add(line(4, 5, "1.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sumsAreExactDecimalsNotBinaryFloatingPoint() {
        // 0.1 + 0.2 is 0.30000000000000004 in binary floating point; the visit total is exactly 0.30.
        List<AppointmentServiceLine> lines = List.of(line(1, 10, "0.10"), line(2, 10, "0.20"));

        assertThat(visit(lines, 20, "0.30").priceEur()).isEqualTo(new BigDecimal("0.30"));
        assertThatThrownBy(() -> visit(lines, 20, "0.31")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> visit(lines, 20, "0.29")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStoredAppointmentFollowsTheSameRules() {
        List<AppointmentServiceLine> lines = lines(2, 45, "12.50");

        Appointment appointment = stored(lines, 90, "25.00");

        assertThat(appointment.serviceCount()).isEqualTo(2);
        assertThat(appointment.serviceLines()).containsExactlyElementsOf(lines);
        assertThatThrownBy(() -> stored(lines, 91, "25.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stored(lines, 90, "25.01")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the single-Service constructors are unchanged -----------------------

    @Test
    void theSingleServiceConstructorsCopyTheHeadlineIntoOneLine() {
        UUID service = UUID.randomUUID();
        NewAppointment created = new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), service, UUID.randomUUID(),
                AppointmentSource.MANUAL, START, 45, new BigDecimal("37.5"), "Europe/Sofia",
                "Подстригване", "Мария Тестова", null, "ABCDEFGHJK", null, CREATED);

        assertThat(created.serviceCount()).isEqualTo(1);
        assertThat(created.serviceLines()).containsExactly(new AppointmentServiceLine(
                1, service, "Подстригване", 45, new BigDecimal("37.50")));
        assertThat(created.priceEur()).isEqualTo(new BigDecimal("37.50"));

        Appointment read = new Appointment(
                created.id(), created.businessId(), created.customerId(), service,
                created.staffMemberId(), AppointmentSource.MANUAL, AppointmentStatus.CONFIRMED, START,
                created.endAt(), created.occupiedUntil(), "Europe/Sofia", 45, created.priceEur(),
                "Подстригване", "Мария Тестова", null, "ABCDEFGHJK", null, 0, CREATED, CREATED);
        assertThat(read.serviceLines()).isEqualTo(created.serviceLines());
    }

    @Test
    void aSingleServicePriceStillStopsAtTenIntegerDigits() {
        assertThatThrownBy(() -> new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AppointmentSource.MANUAL, START, 30,
                new BigDecimal("10000000000.00"), "Europe/Sofia", "Подстригване", "Мария Тестова",
                null, "ABCDEFGHJK", null, CREATED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AppointmentSource.MANUAL, START, 30, new BigDecimal(MAX_SERVICE_PRICE),
                "Europe/Sofia", "Подстригване", "Мария Тестова", null, "ABCDEFGHJK", null, CREATED)
                .priceEur()).isEqualTo(new BigDecimal(MAX_SERVICE_PRICE));
    }

    // ---- limits and structure ------------------------------------------------

    @Test
    void oneToFiveServicesAreAllowedAndSixOrNoneAreNot() {
        assertThat(visit(lines(1, 30, "1.00"), 30, "1.00").serviceCount()).isEqualTo(1);
        assertThat(visit(lines(5, 30, "1.00"), 150, "5.00").serviceCount()).isEqualTo(5);

        // A sixth position cannot exist, and a list of six lines is refused whatever its positions.
        assertThatThrownBy(() -> new AppointmentServiceLine(6, UUID.randomUUID(), "Шеста", 30, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        List<AppointmentServiceLine> six = new ArrayList<>(lines(5, 30, "1.00"));
        six.add(line(5, 30, "1.00"));
        assertThatThrownBy(() -> visit(six, 180, "6.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AppointmentSource.MANUAL, START, 30, BigDecimal.ONE, "Europe/Sofia",
                "Подстригване", "Мария Тестова", null, "ABCDEFGHJK", null, CREATED, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTotalDurationIsAtMostFourHundredAndEightyMinutes() {
        assertThat(visit(lines(2, 240, "1.00"), 480, "2.00").durationMinutes()).isEqualTo(480);
        assertThatThrownBy(() -> visit(lines(2, 241, "1.00"), 482, "2.00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void positionsMustBeOneToNInOrderAndServicesMustBeDistinct() {
        AppointmentServiceLine first = line(1, 30, "1.00");
        AppointmentServiceLine third = line(3, 30, "1.00");
        AppointmentServiceLine swappedSecond = line(1, 30, "1.00");
        AppointmentServiceLine second = line(2, 30, "1.00");

        assertThatThrownBy(() -> visit(List.of(first, third), 60, "2.00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> visit(List.of(first, swappedSecond), 60, "2.00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> visit(List.of(second, first), 60, "2.00"))
                .isInstanceOf(IllegalArgumentException.class);

        UUID same = UUID.randomUUID();
        List<AppointmentServiceLine> duplicate = List.of(
                new AppointmentServiceLine(1, same, "Услуга 1", 30, BigDecimal.ONE),
                new AppointmentServiceLine(2, same, "Услуга 1", 30, BigDecimal.ONE));
        assertThatThrownBy(() -> visit(duplicate, 60, "2.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theHeadlineMustEqualTheFirstLine() {
        List<AppointmentServiceLine> lines = lines(2, 30, "1.00");

        assertThatThrownBy(() -> new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AppointmentSource.MANUAL, START, 60, new BigDecimal("2.00"),
                "Europe/Sofia", lines.get(0).serviceName(), "Мария Тестова", null, "ABCDEFGHJK", null,
                CREATED, lines)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), lines.get(0).serviceId(),
                UUID.randomUUID(), AppointmentSource.MANUAL, START, 60, new BigDecimal("2.00"),
                "Europe/Sofia", "Друго име", "Мария Тестова", null, "ABCDEFGHJK", null, CREATED, lines))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- exact money bounds --------------------------------------------------

    @Test
    void theLargestPossibleVisitTotalIsAcceptedExactly() {
        List<AppointmentServiceLine> five = lines(5, 30, MAX_SERVICE_PRICE);

        NewAppointment visit = visit(five, 150, "49999999999.95");

        assertThat(visit.priceEur()).isEqualTo(new BigDecimal("49999999999.95"));
        assertThat(visit.priceEur().movePointRight(2).longValueExact())
                .isEqualTo(AppointmentRules.MAX_VISIT_PRICE_CENTS)
                .isEqualTo(4_999_999_999_995L);
        assertThat(AppointmentRules.MAX_SERVICE_PRICE_CENTS).isEqualTo(999_999_999_999L);
        assertThat(AppointmentRules.MAX_VISIT_PRICE_CENTS)
                .isEqualTo(AppointmentRules.MAX_SERVICE_PRICE_CENTS * AppointmentRules.MAX_SERVICES_PER_VISIT);
        assertThat(stored(five, 150, "49999999999.95").priceEur()).isEqualTo(new BigDecimal("49999999999.95"));
    }

    @Test
    void aTotalOneCentAboveTheLargestVisitIsRejectedBeforeAnyStatement() {
        List<AppointmentServiceLine> five = lines(5, 30, MAX_SERVICE_PRICE);

        assertThatThrownBy(() -> visit(five, 150, "49999999999.96"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"49999999999.96", "50000000000.00", "99999999999.99", "100000000000.00"})
    void aVisitTotalAboveTheBoundIsRejectedEvenWhenItWouldFitTheColumn(String total) {
        assertThatThrownBy(() -> AppointmentRules.requireVisitPrice(new BigDecimal(total)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Appointment price is too large");
    }

    @Test
    void aVisitTotalOfElevenIntegerDigitsIsAcceptedUpToTheBoundButALinePriceIsNot() {
        assertThat(AppointmentRules.requireVisitPrice(new BigDecimal("49999999999.95")))
                .isEqualTo(new BigDecimal("49999999999.95"));
        assertThat(AppointmentRules.requireVisitPrice(new BigDecimal("10000000000")))
                .isEqualTo(new BigDecimal("10000000000.00"));
        assertThatThrownBy(() -> AppointmentRules.requirePrice(new BigDecimal("10000000000.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppointmentServiceLine(
                1, UUID.randomUUID(), "Услуга", 30, new BigDecimal("10000000000.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyRejectsNegativeValuesAndExcessFractionDigits() {
        assertThatThrownBy(() -> AppointmentRules.requireVisitPrice(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppointmentRules.requireVisitPrice(new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppointmentRules.requireVisitPrice(new BigDecimal("1E+30")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AppointmentRules.requireVisitPrice(BigDecimal.ZERO)).isEqualTo(new BigDecimal("0.00"));
    }
}
