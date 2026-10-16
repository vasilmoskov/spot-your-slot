package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.ReviewedService;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code POST /api/business/appointments} body by shape only (ADR-0028, ADR-0029): the
 * exact allowlisted keys (an unknown key is an error, which the global Jackson configuration would
 * otherwise ignore), canonical UUID text, integral numbers, a string name and note, and a start
 * instant that carries an explicit offset. Whether a value is <em>acceptable</em> (counts, bounds,
 * the note policy) is decided by the service after authorization and lifecycle. A failure carries
 * no property name and no value.
 */
final class CreateAppointmentRequestParser {
    private static final Set<String> ROOT = Set.of(
            "customerId", "staffMemberId", "start", "services", "internalNote");
    private static final Set<String> REQUIRED_ROOT = Set.of(
            "customerId", "staffMemberId", "start", "services");
    private static final Set<String> SERVICE = Set.of(
            "serviceId", "reviewedServiceName", "reviewedDurationMinutes", "reviewedPriceCents");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private CreateAppointmentRequestParser() {
    }

    static CreateAppointmentCommand parse(JsonNode body) {
        JsonNode root = requireObject(body, ROOT);
        for (String key : REQUIRED_ROOT) {
            if (!root.has(key)) {
                throw new InvalidInput();
            }
        }
        UUID customerId = uuid(root.get("customerId"));
        UUID staffMemberId = uuid(root.get("staffMemberId"));
        Instant start = instant(root.get("start"));
        JsonNode services = root.get("services");
        if (!services.isArray()) {
            throw new InvalidInput();
        }
        List<ReviewedService> reviewed = new ArrayList<>(services.size());
        for (JsonNode element : services) {
            JsonNode service = requireObject(element, SERVICE);
            if (service.size() != SERVICE.size()) {
                throw new InvalidInput();
            }
            reviewed.add(new ReviewedService(
                    uuid(service.get("serviceId")),
                    text(service.get("reviewedServiceName"), false),
                    integer(service.get("reviewedDurationMinutes")),
                    integerLong(service.get("reviewedPriceCents"))));
        }
        return new CreateAppointmentCommand(
                customerId, staffMemberId, start, reviewed, text(root.get("internalNote"), true));
    }

    private static JsonNode requireObject(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new InvalidInput();
        }
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new InvalidInput();
            }
        }
        return node;
    }

    /** The canonical 8-4-4-4-12 form only, so no lenient short or signed form names a record. */
    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isString() || !CANONICAL_UUID.matcher(node.asString()).matches()) {
            throw new InvalidInput();
        }
        return UUID.fromString(node.asString());
    }

    /** A JSON string; an absent or null property is {@code null} only where it is optional. */
    private static String text(JsonNode node, boolean optional) {
        if (node == null || node.isNull()) {
            if (optional) {
                return null;
            }
            throw new InvalidInput();
        }
        if (!node.isString()) {
            throw new InvalidInput();
        }
        return node.asString();
    }

    /** An ISO-8601 date-time with an explicit offset; a bare wall-clock time is never converted. */
    private static Instant instant(JsonNode node) {
        if (node == null || !node.isString()) {
            throw new InvalidInput();
        }
        try {
            return OffsetDateTime.parse(node.asString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException | ArithmeticException invalid) {
            throw new InvalidInput();
        }
    }

    private static int integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new InvalidInput();
        }
        return node.intValue();
    }

    private static long integerLong(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new InvalidInput();
        }
        return node.longValue();
    }
}
