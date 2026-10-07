package bg.spotyourslot.booking.domain;

import java.util.Optional;

/**
 * The encodings this server can compute. New attempts always use {@link #CURRENT}; a replay asks
 * for the version stored with the original attempt, and an unknown version is a technical failure
 * of the server, never a payload mismatch.
 */
public final class FingerprintEncodings {
    public static final FingerprintEncoding CURRENT = FingerprintEncodingV1.INSTANCE;

    private FingerprintEncodings() {
    }

    public static Optional<FingerprintEncoding> forVersion(int version) {
        return version == CURRENT.version() ? Optional.of(CURRENT) : Optional.empty();
    }
}
