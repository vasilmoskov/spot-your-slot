package bg.spotyourslot.booking;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The raw guest booking request as the future public endpoint passes it on (ADR-0026): there is no
 * field for the Business identifier, price, duration, end, status, source, or timezone. The
 * orchestration validates and normalizes every value itself; nothing here is trusted.
 *
 * @param businessSlug the public slug that resolves the Business
 * @param attemptId the caller-generated, unpredictable canonical UUID version 4 (ADR-0024)
 * @param serviceId the one Service of the legacy shape; {@code null} when {@code services} is used
 * @param staffMemberId the guest's preference; {@code null} is "no preference"
 * @param start the offered slot start
 * @param phone optional; at least one of phone and email is required
 * @param email optional
 * @param note optional plain text
 * @param services the new shape (ADR-0027): the selected Services as a set with the terms the guest
 *        reviewed; {@code null} for the legacy shape. Exactly one of {@code serviceId} and
 *        {@code services} is set. The list order is irrelevant; its size and contents are validated
 *        by the orchestration like every other value.
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
        String note,
        List<ReviewedService> services) {
    public GuestBookingRequest {
        Objects.requireNonNull(businessSlug, "businessSlug");
        Objects.requireNonNull(start, "start");
        if (services == null) {
            Objects.requireNonNull(serviceId, "serviceId");
        } else {
            if (serviceId != null) {
                throw new IllegalArgumentException("A request has a serviceId or services, not both");
            }
            services = List.copyOf(services);
        }
    }

    /** The legacy single-Service shape: no reviewed terms. */
    public GuestBookingRequest(
            String businessSlug,
            String attemptId,
            UUID serviceId,
            UUID staffMemberId,
            Instant start,
            String displayName,
            String phone,
            String email,
            String note) {
        this(businessSlug, attemptId, serviceId, staffMemberId, start, displayName, phone, email, note, null);
    }

    /**
     * One selected Service with the terms the guest reviewed (ADR-0027 section 6).
     *
     * @param durationMinutes the reviewed duration in whole minutes
     * @param priceCents the reviewed EUR price in cents
     */
    public record ReviewedService(UUID serviceId, int durationMinutes, long priceCents) {
        public ReviewedService {
            Objects.requireNonNull(serviceId, "serviceId");
        }
    }

    /** Personal data and the idempotency identifier are never printed. */
    @Override
    public String toString() {
        return "GuestBookingRequest[redacted]";
    }
}
