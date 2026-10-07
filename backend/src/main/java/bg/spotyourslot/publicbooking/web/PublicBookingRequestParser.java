package bg.spotyourslot.publicbooking.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
            "attemptId", "serviceId", "staffMemberId", "start", "customer", "note");
    private static final Set<String> CUSTOMER = Set.of("displayName", "phone", "email");

    private PublicBookingRequestParser() {
    }

    record ParsedBooking(
            String attemptId,
            UUID serviceId,
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
        UUID serviceId = uuid(root.get("serviceId"), true);
        Instant start = instant(root.get("start"));
        return new ParsedBooking(
                text(root.get("attemptId")),
                serviceId,
                uuid(root.get("staffMemberId"), false),
                start,
                customer == null ? null : text(customer.get("displayName")),
                customer == null ? null : text(customer.get("phone")),
                customer == null ? null : text(customer.get("email")),
                text(root.get("note")));
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
