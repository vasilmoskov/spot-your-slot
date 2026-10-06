package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppointmentDomainTests {
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final String SENTINEL = "СЕНТИНЕЛ-бележка-7731";

    private static BookingAttempt attempt() {
        byte[] hash = new byte[32];
        byte[] fingerprint = new byte[32];
        Arrays.fill(hash, (byte) 1);
        Arrays.fill(fingerprint, (byte) 2);
        return new BookingAttempt(hash, fingerprint, 1, 1);
    }

    private static NewAppointment valid() {
        return build(AppointmentSource.ONLINE, START, 30, "25", "Europe/Sofia",
                "Подстригване", "Мария", null, "ABCDEFGHJK", attempt());
    }

    private static NewAppointment build(
            AppointmentSource source, Instant start, int minutes, String price, String timezone,
            String service, String staff, String note, String reference, BookingAttempt attempt) {
        return new NewAppointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), source, start, minutes, new BigDecimal(price), timezone,
                service, staff, note, reference, attempt, CREATED);
    }

    @Test
    void theServiceEndAndTheOccupiedEndAreElapsedTimeAndEqualWithZeroBuffers() {
        NewAppointment appointment = valid();

        assertThat(appointment.endAt()).isEqualTo(START.plus(Duration.ofMinutes(30)));
        assertThat(appointment.occupiedUntil()).isEqualTo(appointment.endAt());
    }

    @Test
    void elapsedTimeIsUsedAcrossADaylightSavingTransition() {
        Instant transition = ZoneId.of("Europe/Sofia").getRules()
                .nextTransition(Instant.parse("2027-01-01T00:00:00Z")).getInstant();
        NewAppointment appointment = build(AppointmentSource.MANUAL, transition.minusSeconds(1800),
                60, "10", "Europe/Sofia", "Услуга", "Служител", null, "ABCDEFGHJK", null);

        assertThat(Duration.between(appointment.startAt(), appointment.endAt()))
                .isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void thePriceKeepsTheStoredScaleAndRejectsRoundingAndExcessDigits() {
        assertThat(build(AppointmentSource.MANUAL, START, 30, "25", "Europe/Sofia", "С", "Р",
                null, "ABCDEFGHJK", null).priceEur()).isEqualTo(new BigDecimal("25.00"));
        assertThat(build(AppointmentSource.MANUAL, START, 30, "9999999999.99", "Europe/Sofia",
                "С", "Р", null, "ABCDEFGHJK", null).priceEur())
                .isEqualTo(new BigDecimal("9999999999.99"));

        for (String price : new String[] {"25.001", "-0.01", "10000000000.00"}) {
            assertThatThrownBy(() -> build(AppointmentSource.MANUAL, START, 30, price,
                    "Europe/Sofia", "С", "Р", null, "ABCDEFGHJK", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 481, Integer.MAX_VALUE})
    void aDurationOutsideOneToFourHundredEightyMinutesIsRejected(int minutes) {
        assertThatThrownBy(() -> build(AppointmentSource.MANUAL, START, minutes, "1",
                "Europe/Sofia", "С", "Р", null, "ABCDEFGHJK", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBoundaryDurationsAreAccepted() {
        build(AppointmentSource.MANUAL, START, 1, "0", "Europe/Sofia", "С", "Р", null,
                "ABCDEFGHJK", null);
        build(AppointmentSource.MANUAL, START, 480, "0", "Europe/Sofia", "С", "Р", null,
                "ABCDEFGHJK", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Подстригване", "Боядисване и прическа", "Мария Иванова-Петрова", "Д-р Иван Петров",
        "Маникюр (гел лак)", "Ё", "A"})
    void canonicalBulgarianAndOtherNamesAreAcceptedUnchangedForBothSnapshots(String name) {
        NewAppointment service = build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia",
                name, "Р", null, "ABCDEFGHJK", null);
        NewAppointment staff = build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia",
                "С", name, null, "ABCDEFGHJK", null);

        assertThat(service.serviceName()).isEqualTo(name);
        assertThat(staff.staffDisplayName()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // empty and whitespace-only
        "", " ", "   ", "\t",
        // edge whitespace
        " Услуга", "Услуга ", "\tУслуга", "Услуга\n",
        // repeated internal spaces
        "Двойни  интервали", "Три   интервала",
        // a tab or another approved whitespace character inside, even a single one
        "Тест\tтаб", "Тест\nред", "Тест\u00A0нбсп", "Тест\u2003ем", "Тест\u3000идеографски",
        // changed by NFKC: full-width, a ligature, a superscript, a compatibility form
        "\uFF21\uFF22\uFF23", "\uFB01nish", "Услуга\u00B2", "\u2460 Услуга"})
    void aNoncanonicalSnapshotNameIsRejectedRatherThanChangedForBothSnapshots(String name) {
        assertThatThrownBy(() -> build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia",
                name, "Р", null, "ABCDEFGHJK", null))
                .as("Service name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Appointment snapshot name is invalid");
        assertThatThrownBy(() -> build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia",
                "С", name, null, "ABCDEFGHJK", null))
                .as("StaffMember display name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Appointment snapshot name is invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Двойни  интервали", "Тест\tтаб", "\uFB01nish", " Услуга"})
    void aPersistedAppointmentRejectsANoncanonicalSnapshotNameToo(String name) {
        Instant end = START.plus(Duration.ofMinutes(30));

        assertThatThrownBy(() -> persistedWithNames(end, name, "Служител"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persistedWithNames(end, "Услуга", name))
                .isInstanceOf(IllegalArgumentException.class);
        persistedWithNames(end, "Подстригване", "Мария Иванова-Петрова");
    }

    private static Appointment persistedWithNames(Instant end, String service, String staff) {
        return new Appointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AppointmentSource.MANUAL, AppointmentStatus.CONFIRMED, START,
                end, end, "Europe/Sofia", 30, new BigDecimal("25.00"), service, staff, null,
                "ABCDEFGHJK", null, 0, CREATED, CREATED);
    }

    @Test
    void aNameIsBoundedInCodePointsNotUtf16Units() {
        build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia", "😀".repeat(200), "Р",
                null, "ABCDEFGHJK", null);

        assertThatThrownBy(() -> build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia",
                "😀".repeat(201), "Р", null, "ABCDEFGHJK", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mars/Phobos", "+03:00", "", " Europe/Sofia", "europe/sofia", "UTC+3"})
    void anUnknownOrMalformedTimezoneIsRejected(String timezone) {
        assertThatThrownBy(() -> build(AppointmentSource.MANUAL, START, 30, "1", timezone, "С",
                "Р", null, "ABCDEFGHJK", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNoteIsOptionalPlainTextBoundedInCodePoints() {
        assertThat(noteAppointment(null).customerNote()).isNull();
        assertThat(noteAppointment("ред\n\tред").customerNote()).isEqualTo("ред\n\tред");
        assertThat(noteAppointment("😀".repeat(500)).customerNote()).hasSize(1000);

        for (String note : new String[] {
            "", "   ", " отпред", "отзад\n", "а\rб", "а\u0001б", "а\u007Fб", "😀".repeat(501)}) {
            assertThatThrownBy(() -> noteAppointment(note))
                    .as(note.length() + " chars")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static NewAppointment noteAppointment(String note) {
        return build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia", "С", "Р", note,
                "ABCDEFGHJK", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdefghjk", "IIIIIIIIII", "ABCDEFGHJ", "ABCDEFGHJKM", "ABCDEFGHJ!"})
    void thePublicReferenceMustUseTheCrockfordAlphabetAndTenCharacters(String reference) {
        assertThatThrownBy(() -> build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia",
                "С", "Р", null, reference, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOnlineAppointmentRequiresAnAttemptAndAManualOneDoesNot() {
        assertThatThrownBy(() -> build(AppointmentSource.ONLINE, START, 30, "1", "Europe/Sofia",
                "С", "Р", null, "ABCDEFGHJK", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia", "С", "Р", null,
                "ABCDEFGHJK", null).attempt()).isNull();
    }

    @Test
    void instantsOutsideThePostgresRangeAreRejected() {
        for (Instant start : new Instant[] {
            Instant.parse("0000-12-31T00:00:00Z"), Instant.MAX, Instant.MIN,
            Instant.parse("9999-12-31T23:59:00Z")}) {
            assertThatThrownBy(() -> build(AppointmentSource.MANUAL, start, 30, "1",
                    "Europe/Sofia", "С", "Р", null, "ABCDEFGHJK", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anAppointmentRejectsEveryNullRequiredValueAndRedactsItsText() {
        NewAppointment appointment = build(AppointmentSource.MANUAL, START, 30, "1", "Europe/Sofia",
                SENTINEL, SENTINEL, SENTINEL, "ABCDEFGHJK", null);

        assertThat(appointment.toString()).doesNotContain(SENTINEL).isEqualTo("NewAppointment[redacted]");
        assertThatThrownBy(() -> new NewAppointment(null, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), AppointmentSource.MANUAL, START, 30,
                BigDecimal.ONE, "Europe/Sofia", "С", "Р", null, "ABCDEFGHJK", null, CREATED))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- BookingAttempt ------------------------------------------------------

    @Test
    void aBookingAttemptCopiesItsArraysOnTheWayInAndOut() {
        byte[] hash = new byte[32];
        byte[] fingerprint = new byte[32];
        BookingAttempt attempt = new BookingAttempt(hash, fingerprint, 3, 7);

        hash[0] = 9;
        fingerprint[0] = 9;
        attempt.attemptHash()[0] = 5;
        attempt.requestFingerprint()[0] = 5;

        assertThat(attempt.attemptHash()[0]).isZero();
        assertThat(attempt.requestFingerprint()[0]).isZero();
        assertThat(attempt.encodingVersion()).isEqualTo(3);
        assertThat(attempt.keyVersion()).isEqualTo(7);
    }

    @Test
    void aBookingAttemptValidatesLengthsAndTheTwoDistinctVersions() {
        byte[] ok = new byte[32];
        assertThatThrownBy(() -> new BookingAttempt(new byte[31], ok, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingAttempt(ok, new byte[33], 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingAttempt(ok, ok, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingAttempt(ok, ok, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingAttempt(ok, ok, 32768, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingAttempt(null, ok, 1, 1))
                .isInstanceOf(NullPointerException.class);
        new BookingAttempt(ok, ok, 32767, 32767);
    }

    @Test
    void aBookingAttemptHasValueEqualityAndRedactsItsReplayMaterial() {
        assertThat(attempt()).isEqualTo(attempt()).hasSameHashCodeAs(attempt());
        assertThat(new BookingAttempt(new byte[32], new byte[32], 1, 1))
                .isNotEqualTo(new BookingAttempt(new byte[32], new byte[32], 2, 1))
                .isNotEqualTo(new BookingAttempt(new byte[32], new byte[32], 1, 2));
        assertThat(attempt().toString()).isEqualTo("BookingAttempt[redacted]");
    }

    // ---- Appointment ---------------------------------------------------------

    private static Appointment persisted(
            Instant end, Instant occupied, long version, Instant updated, BookingAttempt attempt,
            AppointmentSource source) {
        return new Appointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), source, AppointmentStatus.CONFIRMED, START, end, occupied,
                "Europe/Sofia", 30, new BigDecimal("25.00"), "Услуга", "Служител", SENTINEL,
                "ABCDEFGHJK", attempt, version, CREATED, updated);
    }

    @Test
    void aPersistedAppointmentEnforcesTheSameInvariants() {
        Instant end = START.plus(Duration.ofMinutes(30));

        Appointment ok = persisted(end, end, 0, CREATED, attempt(), AppointmentSource.ONLINE);
        assertThat(ok.blocksTime()).isTrue();
        assertThat(ok.toString()).isEqualTo("Appointment[redacted]").doesNotContain(SENTINEL);

        assertThatThrownBy(() -> persisted(end.plusSeconds(60), end.plusSeconds(60), 0, CREATED,
                attempt(), AppointmentSource.ONLINE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persisted(end, end.plusSeconds(60), 0, CREATED, attempt(),
                AppointmentSource.ONLINE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persisted(end, end, -1, CREATED, attempt(),
                AppointmentSource.ONLINE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persisted(end, end, 0, CREATED.minusSeconds(1), attempt(),
                AppointmentSource.ONLINE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> persisted(end, end, 0, CREATED, null,
                AppointmentSource.ONLINE)).isInstanceOf(IllegalArgumentException.class);
        persisted(end, end, 0, CREATED, null, AppointmentSource.MANUAL);
    }

    @Test
    void onlyAConfirmedAppointmentBlocksTime() {
        Instant end = START.plus(Duration.ofMinutes(30));
        Appointment cancelled = new Appointment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AppointmentSource.MANUAL, AppointmentStatus.CANCELLED, START,
                end, end, "Europe/Sofia", 30, BigDecimal.TEN, "Услуга", "Служител", null,
                "ABCDEFGHJK", null, 1, CREATED, CREATED);

        assertThat(cancelled.blocksTime()).isFalse();
        assertThat(AppointmentStatus.values())
                .containsExactly(AppointmentStatus.CONFIRMED, AppointmentStatus.CANCELLED);
        assertThat(AppointmentSource.values())
                .containsExactly(AppointmentSource.ONLINE, AppointmentSource.MANUAL);
    }

    @Test
    void aBlockingWindowMustHaveItsStartBeforeItsEnd() {
        UUID staff = UUID.randomUUID();

        assertThat(new BlockingWindow(staff, START, START.plusSeconds(1)).start()).isEqualTo(START);
        assertThatThrownBy(() -> new BlockingWindow(staff, START, START))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlockingWindow(staff, START.plusSeconds(1), START))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
