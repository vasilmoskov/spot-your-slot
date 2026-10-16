package bg.spotyourslot.booking.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The pure rules of an owner-created visit (ADR-0028 section 4): the bounds of the request, the
 * exact comparison of the reviewed terms with the locked Service rows, and the server calculation of
 * the Service lines and totals. It is JDK-only, reads no clock, repository, or security context,
 * and never trusts a client total: the request has none. Every failure uses a fixed message.
 *
 * <p>Claims are paired with the locked rows by Service identifier, never by list position. The
 * line order is the order of the locked rows as given (the committed profile order,
 * {@code normalized_name ASC, id ASC}, ADR-0027 section 2), so the client's order has no effect.
 */
public final class ManualVisitTerms {
    public static final int MAX_SERVICES = 5;
    public static final int MIN_SERVICE_MINUTES = 1;
    public static final int MAX_VISIT_MINUTES = 480;
    public static final long MAX_SERVICE_PRICE_CENTS = 999_999_999_999L;
    public static final int MAX_NAME_CODE_POINTS = 200;

    private ManualVisitTerms() {
    }

    /** The reviewed terms of one Service as the owner saw them. */
    public record Claim(UUID serviceId, String serviceName, int durationMinutes, long priceCents) {
        public Claim {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(serviceName, "serviceName");
        }

        @Override
        public String toString() {
            return "Claim[redacted]";
        }
    }

    /** The authoritative facts of a Service as read under its lock. */
    public record LockedService(UUID id, String name, int durationMinutes, BigDecimal price, boolean active) {
        public LockedService {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(price, "price");
        }

        @Override
        public String toString() {
            return "LockedService[redacted]";
        }
    }

    /** The server-calculated visit: lines in stored order and the exact totals. */
    public record Visit(List<AppointmentServiceLine> lines, int totalMinutes, BigDecimal totalPrice) {
        public Visit {
            lines = List.copyOf(lines);
        }

        @Override
        public String toString() {
            return "Visit[redacted]";
        }
    }

    /**
     * One to five distinct Services; each duration 1 to 480 minutes, each price 0 to the largest
     * Service price, each name present and at most 200 code points; the claimed durations sum to at
     * most 480 minutes.
     */
    public static void requireValidClaims(List<Claim> claims) {
        if (claims == null || claims.isEmpty() || claims.size() > MAX_SERVICES) {
            throw new InvalidManualVisit();
        }
        Set<UUID> seen = new HashSet<>();
        long minutes = 0;
        for (Claim claim : claims) {
            if (claim == null
                    || !seen.add(claim.serviceId())
                    || claim.durationMinutes() < MIN_SERVICE_MINUTES
                    || claim.durationMinutes() > MAX_VISIT_MINUTES
                    || claim.priceCents() < 0
                    || claim.priceCents() > MAX_SERVICE_PRICE_CENTS
                    || claim.serviceName().isEmpty()
                    || claim.serviceName().codePointCount(0, claim.serviceName().length())
                            > MAX_NAME_CODE_POINTS) {
                throw new InvalidManualVisit();
            }
            minutes += claim.durationMinutes();
        }
        if (minutes > MAX_VISIT_MINUTES) {
            throw new InvalidManualVisit();
        }
    }

    /** The internal note under the shared note policy: normalized, blank is absent; invalid throws. */
    public static String normalizeInternalNote(String note) {
        String normalized = NoteText.normalize(note);
        if (normalized != null && !NoteText.isValid(normalized)) {
            throw new InvalidManualVisit();
        }
        return normalized;
    }

    /**
     * Every claimed Service came back exactly once and is active. A missing or foreign Service is
     * absent from the locked rows; a repeated or unrequested row is an internal inconsistency.
     */
    public static boolean allAvailable(List<Claim> claims, List<LockedService> locked) {
        Set<UUID> expected = new HashSet<>();
        claims.forEach(claim -> expected.add(claim.serviceId()));
        Set<UUID> seen = new HashSet<>();
        for (LockedService service : locked) {
            if (!expected.contains(service.id()) || !seen.add(service.id())) {
                throw new IllegalStateException("Catalog locked an unrequested or repeated Service");
            }
        }
        return seen.size() == expected.size() && locked.stream().allMatch(LockedService::active);
    }

    /**
     * Exact equality of the reviewed name, minutes, and cents of every Service with the locked
     * row, paired by identifier. The stored price has scale 2 and at most 12 digits, so the cents
     * are exact.
     */
    public static boolean termsMatch(List<Claim> claims, List<LockedService> locked) {
        Map<UUID, LockedService> byId = new HashMap<>();
        locked.forEach(service -> byId.put(service.id(), service));
        if (byId.size() != claims.size()) {
            return false;
        }
        for (Claim claim : claims) {
            LockedService current = byId.get(claim.serviceId());
            if (current == null
                    || !current.name().equals(claim.serviceName())
                    || current.durationMinutes() != claim.durationMinutes()
                    || cents(current.price()) != claim.priceCents()) {
                return false;
            }
        }
        return true;
    }

    /** Lines in the given order (positions 1 to n) with exact integer minutes and decimal price sums. */
    public static Visit plan(List<LockedService> locked) {
        List<AppointmentServiceLine> lines = new ArrayList<>(locked.size());
        int minutes = 0;
        BigDecimal price = BigDecimal.ZERO;
        for (LockedService service : locked) {
            lines.add(new AppointmentServiceLine(
                    lines.size() + 1, service.id(), service.name(), service.durationMinutes(), service.price()));
            minutes = Math.addExact(minutes, service.durationMinutes());
            price = price.add(service.price());
        }
        return new Visit(lines, minutes, price);
    }

    private static long cents(BigDecimal price) {
        return price.movePointRight(2).longValueExact();
    }
}
