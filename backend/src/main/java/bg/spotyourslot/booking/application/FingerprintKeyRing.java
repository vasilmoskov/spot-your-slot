package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.application.FingerprintConfigurationException.Reason;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;

/**
 * The versioned HMAC-SHA-256 keys of the booking request fingerprint (ADR-0024). Exactly one key is
 * <em>active</em> and signs every new attempt; every configured key stays available so a replay can
 * verify an attempt with the key version stored beside it. A key may be removed only when no
 * retained Appointment references its version.
 *
 * <p>Validation happens once, when the ring is built (at startup), and never exposes key material:
 * a failure is a {@link FingerprintConfigurationException} that names a fixed reason and nothing
 * taken from a configured value. The ring redacts {@link #toString()}.
 *
 * <p>Rules: the active key version is an integer in {@code 1..32767} (the stored column is a
 * {@code smallint}); every key version is such an integer and a standard Base64 string that decodes
 * to at least 32 bytes; the active version is configured; and, for the production profile, no key
 * is a published non-secret development or test key (one whose decoded bytes start with
 * {@link #NON_SECRET_MARKER}).
 */
public final class FingerprintKeyRing {
    public static final int MIN_KEY_BYTES = 32;
    public static final int MAX_KEY_VERSION = Short.MAX_VALUE;
    /** The ASCII prefix of every non-secret development and test key; production rejects it. */
    public static final String NON_SECRET_MARKER = "NON-SECRET-TEST-KEY";

    private final int activeVersion;
    private final Map<Integer, byte[]> keys;

    private FingerprintKeyRing(int activeVersion, Map<Integer, byte[]> keys) {
        this.activeVersion = activeVersion;
        this.keys = keys;
    }

    /**
     * @param activeVersion the configured active version text, or {@code null} when absent
     * @param encodedKeys configured key version text to standard Base64 key text
     * @param production whether the production profile is active
     * @throws FingerprintConfigurationException when the configuration is unusable
     */
    public static FingerprintKeyRing create(
            String activeVersion, Map<String, String> encodedKeys, boolean production) {
        if (activeVersion == null || activeVersion.isBlank()) {
            throw new FingerprintConfigurationException(Reason.ACTIVE_VERSION_MISSING);
        }
        int active = parseVersion(activeVersion, Reason.ACTIVE_VERSION_INVALID);
        if (encodedKeys == null || encodedKeys.isEmpty()) {
            throw new FingerprintConfigurationException(Reason.NO_KEYS);
        }
        Map<Integer, byte[]> decoded = new HashMap<>();
        for (Map.Entry<String, String> entry : encodedKeys.entrySet()) {
            int version = parseVersion(entry.getKey(), Reason.KEY_VERSION_INVALID);
            if (decoded.containsKey(version)) {
                throw new FingerprintConfigurationException(Reason.KEY_VERSION_DUPLICATE);
            }
            byte[] key = decode(entry.getValue());
            if (production && hasNonSecretMarker(key)) {
                throw new FingerprintConfigurationException(Reason.NON_SECRET_KEY_IN_PRODUCTION);
            }
            decoded.put(version, key);
        }
        if (!decoded.containsKey(active)) {
            throw new FingerprintConfigurationException(Reason.ACTIVE_KEY_MISSING);
        }
        return new FingerprintKeyRing(active, Map.copyOf(decoded));
    }

    public int activeVersion() {
        return activeVersion;
    }

    /** The key of a configured version, or empty when this server does not hold it. */
    public Optional<SecretKeySpec> find(int version) {
        byte[] key = keys.get(version);
        return key == null ? Optional.empty() : Optional.of(new SecretKeySpec(key, "HmacSHA256"));
    }

    public SecretKeySpec active() {
        return find(activeVersion).orElseThrow();
    }

    private static int parseVersion(String text, FingerprintConfigurationException.Reason reason) {
        String trimmed = text == null ? "" : text.strip();
        if (!trimmed.matches("[0-9]{1,5}")) {
            throw new FingerprintConfigurationException(reason);
        }
        int version = Integer.parseInt(trimmed);
        if (version < 1 || version > MAX_KEY_VERSION) {
            throw new FingerprintConfigurationException(reason);
        }
        return version;
    }

    private static byte[] decode(String text) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(text == null ? "" : text.strip());
        } catch (IllegalArgumentException malformed) {
            throw new FingerprintConfigurationException(Reason.KEY_NOT_BASE64);
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new FingerprintConfigurationException(Reason.KEY_TOO_SHORT);
        }
        return key;
    }

    private static boolean hasNonSecretMarker(byte[] key) {
        byte[] marker = NON_SECRET_MARKER.getBytes(StandardCharsets.US_ASCII);
        if (key.length < marker.length) {
            return false;
        }
        for (int index = 0; index < marker.length; index++) {
            if (key[index] != marker[index]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "FingerprintKeyRing[redacted]";
    }
}
