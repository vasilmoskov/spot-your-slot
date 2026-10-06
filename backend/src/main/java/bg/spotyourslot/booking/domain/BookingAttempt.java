package bg.spotyourslot.booking.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * The stored idempotency material of one online booking attempt (ADR-0024): the SHA-256 of the
 * attempt ID, the HMAC request fingerprint, and the distinct encoding and key versions that
 * produced it. Phase 2 stores and returns these values; it computes none of them.
 *
 * <p>The record is deeply immutable: the arrays are copied on the way in and on the way out. It
 * redacts {@link #toString()} because the fingerprint is replay material.
 */
public final class BookingAttempt {
    public static final int DIGEST_LENGTH = 32;
    public static final int MAX_VERSION = Short.MAX_VALUE;

    private final byte[] attemptHash;
    private final byte[] requestFingerprint;
    private final int encodingVersion;
    private final int keyVersion;

    public BookingAttempt(
            byte[] attemptHash, byte[] requestFingerprint, int encodingVersion, int keyVersion) {
        Objects.requireNonNull(attemptHash, "attemptHash");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        if (attemptHash.length != DIGEST_LENGTH || requestFingerprint.length != DIGEST_LENGTH) {
            throw new IllegalArgumentException("Booking attempt digests must be 32 bytes");
        }
        if (encodingVersion < 1 || encodingVersion > MAX_VERSION
                || keyVersion < 1 || keyVersion > MAX_VERSION) {
            throw new IllegalArgumentException("Booking attempt versions are out of range");
        }
        this.attemptHash = attemptHash.clone();
        this.requestFingerprint = requestFingerprint.clone();
        this.encodingVersion = encodingVersion;
        this.keyVersion = keyVersion;
    }

    public byte[] attemptHash() {
        return attemptHash.clone();
    }

    public byte[] requestFingerprint() {
        return requestFingerprint.clone();
    }

    public int encodingVersion() {
        return encodingVersion;
    }

    public int keyVersion() {
        return keyVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BookingAttempt that)) {
            return false;
        }
        return encodingVersion == that.encodingVersion
                && keyVersion == that.keyVersion
                && Arrays.equals(attemptHash, that.attemptHash)
                && Arrays.equals(requestFingerprint, that.requestFingerprint);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(encodingVersion, keyVersion);
        result = 31 * result + Arrays.hashCode(attemptHash);
        result = 31 * result + Arrays.hashCode(requestFingerprint);
        return result;
    }

    @Override
    public String toString() {
        return "BookingAttempt[redacted]";
    }
}
