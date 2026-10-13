package bg.spotyourslot.booking.domain;

import java.util.UUID;

/**
 * An unambiguous, versioned canonical byte encoding of a normalized booking request (ADR-0024).
 * The encoding version is stored with every attempt next to, and independently of, the HMAC key
 * version, so a replay recomputes the fingerprint exactly as it was first computed. A layout is
 * frozen once released: any change is a new version, never an edit.
 */
public interface FingerprintEncoding {
    /** The stored {@code fingerprint_encoding_version}; always in {@code 1..32767}. */
    int version();

    /**
     * Whether this layout can describe the request at all. A stored attempt written with an encoding
     * that cannot describe the incoming request cannot equal it: that is a payload mismatch, not a
     * technical failure.
     */
    boolean supports(NormalizedBookingRequest request);

    /** The canonical bytes of the request within the Business; the HMAC input. */
    byte[] encode(UUID businessId, NormalizedBookingRequest request);
}
