package bg.spotyourslot.booking.domain;

import java.util.Optional;

/**
 * The encodings this server can compute. New attempts always use {@link #CURRENT}; encoding 1 stays
 * registered for ever so that rows written before multi-Service visits still replay. A replay asks
 * for the version stored with the original attempt, and an unknown version is a technical failure
 * of the server, never a payload mismatch.
 */
public final class FingerprintEncodings {
    public static final FingerprintEncoding CURRENT = FingerprintEncodingV2.INSTANCE;

    private FingerprintEncodings() {
    }

    public static Optional<FingerprintEncoding> forVersion(int version) {
        return switch (version) {
            case 1 -> Optional.of(FingerprintEncodingV1.INSTANCE);
            case 2 -> Optional.of(FingerprintEncodingV2.INSTANCE);
            default -> Optional.empty();
        };
    }
}
