package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The shape rules shared by the cancellation and the internal-note requests: an object with exactly
 * the allowlisted keys, a canonical identifier in the path, and a required integral {@code
 * expectedVersion}. A failure carries no property name and no value.
 */
final class LifecycleRequestShape {
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private LifecycleRequestShape() {
    }

    /** The canonical 8-4-4-4-12 form only, so no lenient short or signed form names a record. */
    static UUID appointmentId(String text) {
        if (text == null || !CANONICAL_UUID.matcher(text).matches()) {
            throw new InvalidInput();
        }
        return UUID.fromString(text);
    }

    /** Requires an object whose keys are exactly {@code keys}: every one present, none else. */
    static void requireExactKeys(JsonNode body, Set<String> keys) {
        if (body == null || !body.isObject()) {
            throw new InvalidInput();
        }
        int count = 0;
        for (String name : body.propertyNames()) {
            if (!keys.contains(name)) {
                throw new InvalidInput();
            }
            count++;
        }
        if (count != keys.size()) {
            throw new InvalidInput();
        }
    }

    static long expectedVersion(JsonNode body) {
        JsonNode version = body.get("expectedVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()) {
            throw new InvalidInput();
        }
        return version.longValue();
    }
}
