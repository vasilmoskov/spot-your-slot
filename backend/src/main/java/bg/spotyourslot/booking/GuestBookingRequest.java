package bg.spotyourslot.booking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The raw guest booking request as the future public endpoint passes it on (ADR-0026): there is no
 * field for the Business identifier, price, duration, end, status, source, or timezone. The
 * orchestration validates and normalizes every value itself; nothing here is trusted.
 *
 * @param businessSlug the public slug that resolves the Business
 * @param attemptId the caller-generated, unpredictable canonical UUID version 4 (ADR-0024)
 * @param staffMemberId the guest's preference; {@code null} is "no preference"
 * @param start the offered slot start
 * @param phone optional; at least one of phone and email is required
 * @param email optional
 * @param note optional plain text
 */
public record GuestBookingRequest(
        String businessSlug,
        String attemptId,
        UUID serviceId,
        UUID staffMemberId,
        Instant start,
        String displayName,
        String phone,
        String email,
        String note) {
    public GuestBookingRequest {
        Objects.requireNonNull(businessSlug, "businessSlug");
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(start, "start");
    }

    /** Personal data and the idempotency identifier are never printed. */
    @Override
    public String toString() {
        return "GuestBookingRequest[redacted]";
    }
}
