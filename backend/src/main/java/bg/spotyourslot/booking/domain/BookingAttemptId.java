package bg.spotyourslot.booking.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The caller-generated idempotency identifier of one booking attempt (ADR-0024): a canonical,
 * lowercase, hyphenated UUID of version 4 (variant {@code 8}, {@code 9}, {@code a}, or {@code b}).
 * The server validates only this format. It cannot prove that a value was randomly generated, so
 * unpredictable generation is a requirement on callers, and an attempt identifier is never an
 * authentication credential.
 *
 * <p>Only {@link #hash()} is persisted: SHA-256 over the 36 ASCII bytes of the canonical text. The
 * identifier is not part of the request fingerprint. {@link #toString()} is redacted.
 */
public final class BookingAttemptId {
    private static final Pattern CANONICAL_UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final String value;

    private BookingAttemptId(String value) {
        this.value = value;
    }

    /** @return the identifier, or empty for anything but the canonical lowercase UUID version 4 text */
    public static Optional<BookingAttemptId> parse(String text) {
        if (text == null || !CANONICAL_UUID_V4.matcher(text).matches()) {
            return Optional.empty();
        }
        return Optional.of(new BookingAttemptId(text));
    }

    /** The 32-byte SHA-256 of the canonical identifier text, the only stored form. */
    public byte[] hash() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", impossible);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BookingAttemptId that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "BookingAttemptId[redacted]";
    }
}
