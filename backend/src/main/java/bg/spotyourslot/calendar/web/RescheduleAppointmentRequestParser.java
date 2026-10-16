package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Reads the {@code POST /api/business/appointments/{appointmentId}/reschedule} request by shape only
 * (ADR-0028, ADR-0029): the exact allowlisted keys (an unknown key, a Customer, Service, duration, or
 * price included, is an error), a canonical identifier in the path and in the body, a required
 * integral {@code expectedVersion}, and optional {@code start} (an ISO-8601 instant with an explicit
 * offset, never converted from a bare wall-clock time) and {@code staffMemberId}. An explicit JSON
 * {@code null} is an error: a key is either absent (keep the current value) or valid. Whether the
 * values are <em>acceptable</em> (a negative version, neither key present, the instant bounds) is
 * decided by the service after authorization and lifecycle. A failure carries no property name and
 * no value.
 */
final class RescheduleAppointmentRequestParser {
    private static final Set<String> ROOT = Set.of("expectedVersion", "start", "staffMemberId");
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RescheduleAppointmentRequestParser() {
    }

    static RescheduleAppointmentCommand parse(String appointmentId, JsonNode body) {
        UUID id = uuid(appointmentId);
        if (body == null || !body.isObject()) {
            throw new InvalidInput();
        }
        for (String name : body.propertyNames()) {
            if (!ROOT.contains(name)) {
                throw new InvalidInput();
            }
        }
        JsonNode version = body.get("expectedVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
            throw new InvalidInput();
        }
        Instant start = body.has("start") ? instant(body.get("start")) : null;
        UUID staffMemberId = body.has("staffMemberId") ? uuid(body.get("staffMemberId")) : null;
        return new RescheduleAppointmentCommand(id, version.longValue(), start, staffMemberId);
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isString()) {
            throw new InvalidInput();
        }
        return uuid(node.asString());
    }

    /** The canonical 8-4-4-4-12 form only, so no lenient short or signed form names a record. */
    private static UUID uuid(String text) {
        if (text == null || !CANONICAL_UUID.matcher(text).matches()) {
            throw new InvalidInput();
        }
        return UUID.fromString(text);
    }

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
}
