package bg.spotyourslot.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.application.FingerprintConfigurationException.Reason;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Synthetic keys only. Nothing here is, or resembles, a production secret. */
class FingerprintKeyRingTests {
    private static String key(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final String SECRET_ONE = key("synthetic-key-one-0123456789-abcdefghij");
    private static final String SECRET_TWO = key("synthetic-key-two-0123456789-abcdefghij");
    private static final String NON_SECRET = key(FingerprintKeyRing.NON_SECRET_MARKER + ":synthetic-dev-key-000000");

    private static Reason rejectedWith(String active, Map<String, String> keys, boolean production) {
        try {
            FingerprintKeyRing.create(active, keys, production);
        } catch (FingerprintConfigurationException failure) {
            // A configuration failure must never print a configured value.
            assertThat(failure.getMessage()).doesNotContain(SECRET_ONE).doesNotContain(SECRET_TWO);
            assertThat(failure.getCause()).isNull();
            return failure.reason();
        }
        throw new AssertionError("The configuration was accepted");
    }

    @Test
    void anActiveKeyAndHistoricalKeysAreAllAvailableByVersion() {
        FingerprintKeyRing ring = FingerprintKeyRing.create(
                "2", Map.of("1", SECRET_ONE, "2", SECRET_TWO), true);

        assertThat(ring.activeVersion()).isEqualTo(2);
        assertThat(ring.find(1)).isPresent();
        assertThat(ring.find(2)).isPresent();
        assertThat(ring.find(3)).isEmpty();
        assertThat(ring.active().getEncoded())
                .isEqualTo(Base64.getDecoder().decode(SECRET_TWO));
        assertThat(ring.find(1).orElseThrow().getEncoded())
                .isEqualTo(Base64.getDecoder().decode(SECRET_ONE));
    }

    @Test
    void rotationKeepsTheOldKeyForReplayWhileNewAttemptsUseTheNewActiveOne() {
        FingerprintKeyRing before = FingerprintKeyRing.create("1", Map.of("1", SECRET_ONE), true);
        FingerprintKeyRing after = FingerprintKeyRing.create(
                "2", Map.of("1", SECRET_ONE, "2", SECRET_TWO), true);

        assertThat(before.activeVersion()).isEqualTo(1);
        assertThat(after.activeVersion()).isEqualTo(2);
        assertThat(after.find(1).orElseThrow().getEncoded()).isEqualTo(before.active().getEncoded());
    }

    @Test
    void aMissingOrMalformedActiveVersionIsRejected() {
        Map<String, String> keys = Map.of("1", SECRET_ONE);

        assertThat(rejectedWith(null, keys, false)).isEqualTo(Reason.ACTIVE_VERSION_MISSING);
        assertThat(rejectedWith("  ", keys, false)).isEqualTo(Reason.ACTIVE_VERSION_MISSING);
        for (String malformed : new String[] {"0", "-1", "32768", "abc", "1.0", "1 2", "100000"}) {
            assertThat(rejectedWith(malformed, keys, false)).as(malformed).isEqualTo(Reason.ACTIVE_VERSION_INVALID);
        }
    }

    @Test
    void anEmptyKeySetOrAMissingActiveKeyIsRejected() {
        assertThat(rejectedWith("1", Map.of(), false)).isEqualTo(Reason.NO_KEYS);
        assertThat(rejectedWith("1", null, false)).isEqualTo(Reason.NO_KEYS);
        assertThat(rejectedWith("2", Map.of("1", SECRET_ONE), false)).isEqualTo(Reason.ACTIVE_KEY_MISSING);
    }

    @Test
    void malformedKeyVersionsAreRejected() {
        for (String version : new String[] {"0", "x", "-3", "32768", ""}) {
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put("1", SECRET_ONE);
            keys.put(version, SECRET_TWO);
            assertThat(rejectedWith("1", keys, false)).as(version).isEqualTo(Reason.KEY_VERSION_INVALID);
        }
    }

    @Test
    void versionsThatDifferOnlyInPaddingAreDuplicates() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("1", SECRET_ONE);
        keys.put(" 1 ", SECRET_TWO);

        assertThat(rejectedWith("1", keys, false)).isEqualTo(Reason.KEY_VERSION_DUPLICATE);
    }

    @Test
    void aKeyThatIsNotBase64OrIsTooShortIsRejectedWithoutEchoingIt() {
        assertThat(rejectedWith("1", Map.of("1", "!!! not base64 !!!"), false)).isEqualTo(Reason.KEY_NOT_BASE64);
        assertThat(rejectedWith("1", Map.of("1", "a b"), false)).isEqualTo(Reason.KEY_NOT_BASE64);
        // Unpadded Base64 decodes, but three characters are far below the minimum length.
        assertThat(rejectedWith("1", Map.of("1", "abc"), false)).isEqualTo(Reason.KEY_TOO_SHORT);
        assertThat(rejectedWith("1", Map.of("1", key("short")), false)).isEqualTo(Reason.KEY_TOO_SHORT);
        assertThat(rejectedWith("1", Map.of("1", key("x".repeat(31))), false)).isEqualTo(Reason.KEY_TOO_SHORT);
        assertThat(FingerprintKeyRing.create("1", Map.of("1", key("x".repeat(32))), false).find(1)).isPresent();
    }

    @Test
    void aPublishedNonSecretKeyIsAcceptedOutsideProductionAndRejectedInIt() {
        assertThat(FingerprintKeyRing.create("1", Map.of("1", NON_SECRET), false).find(1)).isPresent();
        assertThat(rejectedWith("1", Map.of("1", NON_SECRET), true))
                .isEqualTo(Reason.NON_SECRET_KEY_IN_PRODUCTION);
        // A historical key is checked too, not only the active one.
        assertThat(rejectedWith("2", Map.of("1", NON_SECRET, "2", SECRET_TWO), true))
                .isEqualTo(Reason.NON_SECRET_KEY_IN_PRODUCTION);
    }

    @Test
    void theRingNeverPrintsKeyMaterial() {
        FingerprintKeyRing ring = FingerprintKeyRing.create("1", Map.of("1", SECRET_ONE), true);

        assertThat(ring.toString()).doesNotContain(SECRET_ONE).contains("redacted");
        assertThatThrownBy(() -> FingerprintKeyRing.create("1", Map.of("1", "bad key"), true))
                .hasMessageNotContaining("bad key");
    }
}
