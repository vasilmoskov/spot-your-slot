package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Reads {@code PUT /api/business/appointments/{appointmentId}/internal-note} by shape only: exactly
 * the keys {@code expectedVersion} (a required integral number) and {@code internalNote} (a required
 * JSON string; a blank string clears the note). An unknown key, a missing key, or an explicit
 * {@code null} is an error. The note rule and the length are decided by the service after
 * authorization, lifecycle, existence, and version, through the shared note policy.
 */
final class EditInternalNoteRequestParser {
    private static final Set<String> KEYS = Set.of("expectedVersion", "internalNote");

    private EditInternalNoteRequestParser() {
    }

    static EditInternalNoteCommand parse(String appointmentId, JsonNode body) {
        var id = LifecycleRequestShape.appointmentId(appointmentId);
        LifecycleRequestShape.requireExactKeys(body, KEYS);
        long version = LifecycleRequestShape.expectedVersion(body);
        JsonNode note = body.get("internalNote");
        if (note == null || !note.isString()) {
            throw new InvalidInput();
        }
        return new EditInternalNoteCommand(id, version, note.asString());
    }
}
