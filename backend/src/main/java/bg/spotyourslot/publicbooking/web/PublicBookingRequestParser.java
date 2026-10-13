package bg.spotyourslot.publicbooking.web;

import bg.spotyourslot.booking.GuestBookingRequest.ReviewedService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Reads the public booking body strictly (ADR-0026): the root and the {@code customer} object must
 * be objects, only the allowlisted properties may appear (an unknown property is a validation
 * error, which the global Jackson configuration would otherwise ignore), and every value must be a
 * string (or null) except the two identifiers and the instant, which must also parse. Nothing here
 * decides whether a value is <em>acceptable</em>; the orchestration validates and normalizes every
 * value itself. A failure carries no property name and no value.
 *
 * <p>The record holds personal data, so its {@code toString()} is redacted.
 */
final class PublicBookingRequestParser {
    private static final Set<String> ROOT = Set.of(
            "attemptId", "serviceId", "services", "staffMemberId", "start", "customer", "note");
    private static final Set<String> SERVICE = Set.of(
            "serviceId", "reviewedDurationMinutes", "reviewedPriceCents");
    private static final int MAX_SERVICES = 5;
    private static final int MIN_SERVICE_MINUTES = 1;
    private static final int MAX_VISIT_MINUTES = 480;
    private static final long MAX_PRICE_CENTS = 999_999_999_999L;
    private static final Set<String> CUSTOMER = Set.of("displayName", "phone", "email");

    private PublicBookingRequestParser() {
    }

    /**
     * @param serviceId the one Service of the legacy shape, or {@code null}
     * @param services the new shape (ADR-0027) with the reviewed terms, or {@code null}
     */
    record ParsedBooking(
            String attemptId,
            UUID serviceId,
            List<ReviewedService> services,
            UUID staffMemberId,
            Instant start,
            String displayName,
            String phone,
            String email,
            String note) {
        @Override
        public String toString() {
            return "ParsedBooking[redacted]";
        }
    }

    static ParsedBooking parse(JsonNode body) {
        JsonNode root = requireObject(body, ROOT);
        JsonNode customer = root.get("customer");
        if (customer != null && !customer.isNull()) {
            customer = requireObject(customer, CUSTOMER);
        } else {
            customer = null;
        }
        UUID serviceId = null;
        List<ReviewedService> services = null;
        boolean legacyShape = present(root.get("serviceId"));
        boolean newShape = present(root.get("services"));
        if (legacyShape && newShape) {
            throw InvalidPublicRequest.services();
        }
        if (newShape) {
            services = reviewedServices(root.get("services"));
        } else {
            serviceId = uuid(root.get("serviceId"), true);
        }
        Instant start = instant(root.get("start"));
        return new ParsedBooking(
                text(root.get("attemptId")),
                serviceId,
                services,
                uuid(root.get("staffMemberId"), false),
                start,
                customer == null ? null : text(customer.get("displayName")),
                customer == null ? null : text(customer.get("phone")),
                customer == null ? null : text(customer.get("email")),
                text(root.get("note")));
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }

    /**
     * The new shape: one to five distinct objects of exactly {@code serviceId},
     * {@code reviewedDurationMinutes}, and {@code reviewedPriceCents}. The numbers must be JSON
     * integers (a string, a fraction, or an exponent form is not one) inside the approved bounds, and
     * the claimed durations may not sum to more than the visit ceiling. Any violation is the public
     * {@code services} field error; a null element, a missing key, or an unknown key is one too.
     */
    private static List<ReviewedService> reviewedServices(JsonNode node) {
        if (!node.isArray() || node.size() < 1 || node.size() > MAX_SERVICES) {
            throw InvalidPublicRequest.services();
        }
        List<ReviewedService> services = new ArrayList<>(node.size());
        Set<UUID> seen = new HashSet<>();
        long totalMinutes = 0;
        for (JsonNode element : node) {
            if (!element.isObject() || element.size() != SERVICE.size()) {
                throw InvalidPublicRequest.services();
            }
            for (String name : element.propertyNames()) {
                if (!SERVICE.contains(name)) {
                    throw InvalidPublicRequest.services();
                }
            }
            UUID serviceId = canonicalUuid(element.get("serviceId"));
            int minutes = integer(element.get("reviewedDurationMinutes"), MIN_SERVICE_MINUTES, MAX_VISIT_MINUTES);
            long cents = integerLong(element.get("reviewedPriceCents"), 0, MAX_PRICE_CENTS);
            if (!seen.add(serviceId)) {
                throw InvalidPublicRequest.services();
            }
            totalMinutes += minutes;
            services.add(new ReviewedService(serviceId, minutes, cents));
        }
        if (totalMinutes > MAX_VISIT_MINUTES) {
            throw InvalidPublicRequest.services();
        }
        return services;
    }

    /** The canonical 8-4-4-4-12 form only, so no lenient short or signed form names a Service. */
    private static UUID canonicalUuid(JsonNode node) {
        UUID id = node == null || !node.isString() ? null : CanonicalUuid.parse(node.asString());
        if (id == null) {
            throw InvalidPublicRequest.services();
        }
        return id;
    }

    private static int integer(JsonNode node, int min, int max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw InvalidPublicRequest.services();
        }
        int value = node.intValue();
        if (value < min || value > max) {
            throw InvalidPublicRequest.services();
        }
        return value;
    }

    private static long integerLong(JsonNode node, long min, long max) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw InvalidPublicRequest.services();
        }
        long value = node.longValue();
        if (value < min || value > max) {
            throw InvalidPublicRequest.services();
        }
        return value;
    }

    private static JsonNode requireObject(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new InvalidPublicRequest();
        }
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new InvalidPublicRequest();
            }
        }
        return node;
    }

    /** A JSON string, or null for an absent or null property; any other JSON type is invalid. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new InvalidPublicRequest();
        }
        return node.asString();
    }

    private static UUID uuid(JsonNode node, boolean required) {
        String text = text(node);
        if (text == null) {
            if (required) {
                throw new InvalidPublicRequest();
            }
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw new InvalidPublicRequest();
        }
    }

    private static Instant instant(JsonNode node) {
        String text = text(node);
        if (text == null) {
            throw new InvalidPublicRequest();
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException | ArithmeticException exception) {
            throw new InvalidPublicRequest();
        }
    }
}
