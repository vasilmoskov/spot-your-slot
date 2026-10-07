package bg.spotyourslot.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.application.RequestFingerprinter.UnverifiableFingerprint;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.NormalizedBookingRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestFingerprinterTests {
    private static final UUID BUSINESS = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BUSINESS = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SERVICE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID STAFF = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String ATTEMPT = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final Instant START = Instant.ofEpochSecond(1_790_000_000L);

    /** The key and both golden results come from an independent HMAC-SHA-256 implementation. */
    private static final String GOLDEN_KEY = key("NON-SECRET-TEST-KEY:golden-vector-key-0000");
    private static final String GOLDEN_FULL =
            "542d58be6e99cd6728033b9a6e6014c3548684cc0f037fc7d3dcfc1afabe2a95";
    private static final String GOLDEN_MINIMAL =
            "a71efcbed1a6f9e4ef7630bc4e38dd4b2104f70e71370e8ebee293ef262055d9";
    private static final String OLD_KEY = key("synthetic-historical-key-0123456789-abcd");
    private static final String NEW_KEY = key("synthetic-active-key-0123456789-abcdefgh");

    private static String key(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static NormalizedBookingRequest request(UUID staff, String name, String note) {
        return NormalizedBookingRequest.normalize(
                ATTEMPT, SERVICE, staff, START, name, "+359888123456", "ivan@example.com", note);
    }

    private static RequestFingerprinter fingerprinter(String active, Map<String, String> keys) {
        return new RequestFingerprinter(FingerprintKeyRing.create(active, keys, false));
    }

    @Test
    void aNewAttemptIsTheHmacOfTheGoldenEncodingWithTheActiveKeyAndTheCurrentVersions() {
        RequestFingerprinter fingerprinter = fingerprinter("5", Map.of("5", GOLDEN_KEY));

        BookingAttempt full = fingerprinter.newAttempt(
                BUSINESS, request(null, "Иван Петров", "Първа среща"));
        BookingAttempt minimal = fingerprinter.newAttempt(
                BUSINESS, NormalizedBookingRequest.normalize(
                        ATTEMPT, SERVICE, STAFF, START, "Ana", "+359888123456", null, null));

        assertThat(HexFormat.of().formatHex(full.requestFingerprint())).isEqualTo(GOLDEN_FULL);
        assertThat(HexFormat.of().formatHex(minimal.requestFingerprint())).isEqualTo(GOLDEN_MINIMAL);
        assertThat(full.encodingVersion()).isEqualTo(1);
        assertThat(full.keyVersion()).isEqualTo(5);
        assertThat(HexFormat.of().formatHex(full.attemptHash()))
                .isEqualTo("c812e1edb64417d6090dcfbaf16c21cd8e8665c04396e1edb472fecfb2797c6a");
    }

    @Test
    void theFingerprintIsKeyedSoAnotherKeyProducesAnotherValue() {
        BookingAttempt first = fingerprinter("1", Map.of("1", OLD_KEY)).newAttempt(BUSINESS, request(null, "Ана", null));
        BookingAttempt second = fingerprinter("1", Map.of("1", NEW_KEY)).newAttempt(BUSINESS, request(null, "Ана", null));

        assertThat(first.requestFingerprint()).isNotEqualTo(second.requestFingerprint());
    }

    @Test
    void anIdenticalAndANormalizedEquivalentRequestMatchTheStoredAttempt() {
        RequestFingerprinter fingerprinter = fingerprinter("1", Map.of("1", OLD_KEY));
        BookingAttempt stored = fingerprinter.newAttempt(BUSINESS, request(null, "Иван Петров", "Бележка"));

        assertThat(fingerprinter.matches(BUSINESS, request(null, "Иван Петров", "Бележка"), stored)).isTrue();
        assertThat(fingerprinter.matches(BUSINESS, request(null, "  Иван   Петров ", "\r\nБележка\r\n"), stored))
                .isTrue();
    }

    @Test
    void anyChangedFieldAndAnotherBusinessDoNotMatch() {
        RequestFingerprinter fingerprinter = fingerprinter("1", Map.of("1", OLD_KEY));
        BookingAttempt stored = fingerprinter.newAttempt(BUSINESS, request(null, "Иван Петров", "Бележка"));

        assertThat(fingerprinter.matches(BUSINESS, request(null, "Иван Петров", "Друга бележка"), stored)).isFalse();
        assertThat(fingerprinter.matches(BUSINESS, request(null, "Иван Петрова", "Бележка"), stored)).isFalse();
        assertThat(fingerprinter.matches(BUSINESS, request(STAFF, "Иван Петров", "Бележка"), stored)).isFalse();
        assertThat(fingerprinter.matches(OTHER_BUSINESS, request(null, "Иван Петров", "Бележка"), stored)).isFalse();
    }

    @Test
    void replayUsesTheStoredKeyVersionNotTheActiveOne() {
        BookingAttempt stored = fingerprinter("1", Map.of("1", OLD_KEY))
                .newAttempt(BUSINESS, request(null, "Ана", null));
        // After rotation the active key is 2 but key 1 is retained.
        RequestFingerprinter rotated = fingerprinter("2", Map.of("1", OLD_KEY, "2", NEW_KEY));

        assertThat(stored.keyVersion()).isEqualTo(1);
        assertThat(rotated.matches(BUSINESS, request(null, "Ана", null), stored)).isTrue();
        assertThat(rotated.matches(BUSINESS, request(null, "Бела", null), stored)).isFalse();
        // A new attempt after rotation is signed with the new active key.
        assertThat(rotated.newAttempt(BUSINESS, request(null, "Ана", null)).keyVersion()).isEqualTo(2);
    }

    @Test
    void aMissingHistoricalKeyIsATechnicalFailureNeverAMismatch() {
        BookingAttempt stored = fingerprinter("1", Map.of("1", OLD_KEY))
                .newAttempt(BUSINESS, request(null, "Ана", null));
        RequestFingerprinter withoutOldKey = fingerprinter("2", Map.of("2", NEW_KEY));

        assertThatThrownBy(() -> withoutOldKey.matches(BUSINESS, request(null, "Ана", null), stored))
                .isInstanceOfSatisfying(UnverifiableFingerprint.class,
                        failure -> assertThat(failure.reason()).isEqualTo(UnverifiableFingerprint.Cause.MISSING_KEY))
                .hasMessage("Stored booking fingerprint cannot be verified")
                .hasNoCause();
        // Even for a payload that would not match, the missing key is reported, not a mismatch.
        assertThatThrownBy(() -> withoutOldKey.matches(BUSINESS, request(null, "Различно", null), stored))
                .isInstanceOf(UnverifiableFingerprint.class);
    }

    @Test
    void anUnsupportedStoredEncodingVersionIsATechnicalFailureNeverAMismatch() {
        RequestFingerprinter fingerprinter = fingerprinter("1", Map.of("1", OLD_KEY));
        BookingAttempt current = fingerprinter.newAttempt(BUSINESS, request(null, "Ана", null));
        BookingAttempt future = new BookingAttempt(
                current.attemptHash(), current.requestFingerprint(), 2, current.keyVersion());

        assertThatThrownBy(() -> fingerprinter.matches(BUSINESS, request(null, "Ана", null), future))
                .isInstanceOfSatisfying(UnverifiableFingerprint.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(UnverifiableFingerprint.Cause.UNSUPPORTED_ENCODING));
    }

    @Test
    void theTechnicalFailureCarriesNoVersionKeyOrRequestValue() {
        RequestFingerprinter fingerprinter = fingerprinter("1", Map.of("1", OLD_KEY));
        BookingAttempt current = fingerprinter.newAttempt(BUSINESS, request(null, "Тайна", null));
        BookingAttempt future = new BookingAttempt(
                current.attemptHash(), current.requestFingerprint(), 9, 1);

        assertThatThrownBy(() -> fingerprinter.matches(BUSINESS, request(null, "Тайна", null), future))
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).doesNotContain("9").doesNotContain("Тайна");
                    assertThat(failure.toString()).doesNotContain(OLD_KEY).doesNotContain("Тайна");
                    assertThat(failure.getSuppressed()).isEmpty();
                });
    }
}
