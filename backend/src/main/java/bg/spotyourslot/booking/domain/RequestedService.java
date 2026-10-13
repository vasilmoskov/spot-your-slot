package bg.spotyourslot.booking.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One Service of a booking request (ADR-0027). The reviewed terms are the guest's claims about the
 * Service as the guest saw it; they are compared with the locked server facts and then discarded,
 * never persisted.
 *
 * @param reviewed the reviewed duration and price, or {@code null} for the legacy request shape
 */
public record RequestedService(UUID serviceId, ReviewedTerms reviewed) {
    public RequestedService {
        Objects.requireNonNull(serviceId, "serviceId");
    }

    /**
     * @param durationMinutes the reviewed whole minutes
     * @param priceCents the reviewed EUR price in cents
     */
    public record ReviewedTerms(int durationMinutes, long priceCents) {
    }
}
