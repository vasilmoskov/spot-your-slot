package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Reads {@code POST /api/business/appointments/{appointmentId}/cancel} by shape only: exactly one
 * key, a required integral {@code expectedVersion}. No reason, actor, time, or status can be named;
 * an unknown key or an explicit {@code null} is an error. Whether the version is acceptable is
 * decided by the service after authorization and lifecycle.
 */
final class CancelAppointmentRequestParser {
    private static final Set<String> KEYS = Set.of("expectedVersion");

    private CancelAppointmentRequestParser() {
    }

    static CancelAppointmentCommand parse(String appointmentId, JsonNode body) {
        var id = LifecycleRequestShape.appointmentId(appointmentId);
        LifecycleRequestShape.requireExactKeys(body, KEYS);
        return new CancelAppointmentCommand(id, LifecycleRequestShape.expectedVersion(body));
    }
}
