package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.FingerprintEncoding;
import bg.spotyourslot.booking.domain.FingerprintEncodings;
import bg.spotyourslot.booking.domain.NormalizedBookingRequest;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Computes and verifies the request fingerprint {@code HMAC-SHA-256(key, canonical bytes)}
 * (ADR-0024). A new attempt always uses the current encoding and the active key. Verification
 * recomputes the fingerprint with the encoding and key versions <em>stored with the original
 * attempt</em> and compares in constant time.
 *
 * <p>A stored encoding or key version that this server does not have is a technical failure
 * ({@link UnverifiableFingerprint}), never a payload mismatch: the Appointment exists but its
 * request cannot be checked. The raw request, the canonical bytes, and the key never leave this
 * class.
 */
@Component
public class RequestFingerprinter {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final FingerprintKeyRing keys;

    public RequestFingerprinter(FingerprintKeyRing keys) {
        this.keys = keys;
    }

    /** The stored idempotency material of a new attempt: current encoding, active key. */
    public BookingAttempt newAttempt(UUID businessId, NormalizedBookingRequest request) {
        FingerprintEncoding encoding = FingerprintEncodings.CURRENT;
        byte[] fingerprint = hmac(keys.active(), encoding.encode(businessId, request));
        return new BookingAttempt(
                request.attemptId().hash(), fingerprint, encoding.version(), keys.activeVersion());
    }

    /**
     * Whether the incoming request equals, in every fingerprinted field, the request that produced
     * the stored attempt.
     *
     * @throws UnverifiableFingerprint when the stored encoding or key version is not available
     */
    public boolean matches(UUID businessId, NormalizedBookingRequest request, BookingAttempt stored) {
        Optional<FingerprintEncoding> encoding = FingerprintEncodings.forVersion(stored.encodingVersion());
        if (encoding.isEmpty()) {
            throw new UnverifiableFingerprint(UnverifiableFingerprint.Cause.UNSUPPORTED_ENCODING);
        }
        Optional<SecretKeySpec> key = keys.find(stored.keyVersion());
        if (key.isEmpty()) {
            throw new UnverifiableFingerprint(UnverifiableFingerprint.Cause.MISSING_KEY);
        }
        byte[] recomputed = hmac(key.get(), encoding.get().encode(businessId, request));
        return MessageDigest.isEqual(recomputed, stored.requestFingerprint());
    }

    private static byte[] hmac(SecretKeySpec key, byte[] canonicalBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(canonicalBytes);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA-256 is required by every Java runtime");
        }
    }

    /** A stored fingerprint cannot be verified by this server; never a payload mismatch. */
    public static final class UnverifiableFingerprint extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** The only information carried; contains no version number or key material. */
        public enum Cause {
            UNSUPPORTED_ENCODING,
            MISSING_KEY
        }

        private final transient Cause reason;

        UnverifiableFingerprint(Cause reason) {
            super("Stored booking fingerprint cannot be verified", null, false, true);
            this.reason = reason;
        }

        public Cause reason() {
            return reason;
        }
    }
}
