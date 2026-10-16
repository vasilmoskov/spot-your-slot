package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministration;
import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentCalendar;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.AgendaResponse;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.CreatedAppointmentResponse;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.DetailResponse;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.RescheduledAppointmentResponse;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/business/appointments} (the paginated agenda) and
 * {@code GET /api/business/appointments/{id}} (the owner-only detail), and
 * {@code POST /api/business/appointments} (the owner-created multi-Service Appointment), and
 * {@code POST /api/business/appointments/{id}/reschedule} (the form-based move and StaffMember
 * reassignment).
 */
@RestController
@RequestMapping("/api/business/appointments")
public class BusinessAppointmentController {
    private final AppointmentCalendar calendar;
    private final AppointmentAdministration administration;

    public BusinessAppointmentController(
            AppointmentCalendar calendar, AppointmentAdministration administration) {
        this.calendar = calendar;
        this.administration = administration;
    }

    /**
     * Creates one atomic multi-Service Appointment for the selected Business. The body is read by
     * shape only; the service validates after authorization and lifecycle.
     */
    @PostMapping
    public ResponseEntity<CreatedAppointmentResponse> create(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestBody JsonNode body) {
        CreatedAppointment created = administration.create(
                context, CreateAppointmentRequestParser.parse(body));
        return ResponseEntity.created(URI.create("/api/business/appointments/" + created.id()))
                .body(CreatedAppointmentResponse.from(created));
    }

    /**
     * Moves and/or reassigns one Appointment of the selected Business in one atomic change guarded
     * by {@code expectedVersion}. The path identifier and the body are read by shape only; the
     * service validates after authorization and lifecycle.
     */
    @PostMapping("/{appointmentId}/reschedule")
    public RescheduledAppointmentResponse reschedule(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable String appointmentId,
            @RequestBody JsonNode body) {
        RescheduleAppointmentCommand command = RescheduleAppointmentRequestParser.parse(appointmentId, body);
        try {
            return RescheduledAppointmentResponse.from(administration.reschedule(context, command));
        } catch (AppointmentAdministrationException failure) {
            // The route publishes its own wording for the shared results (see the handler).
            throw new RescheduleFailure(failure);
        }
    }

    @GetMapping
    public AgendaResponse agenda(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID staffMemberId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return AgendaResponse.from(calendar.agenda(
                context, parse(from), parse(to), staffMemberId, page, size));
    }

    @GetMapping("/{appointmentId}")
    public DetailResponse detail(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID appointmentId) {
        return DetailResponse.from(calendar.detail(context, appointmentId));
    }

    private static LocalDate parse(String value) {
        LocalDate parsed = BusinessCalendarHttpRecords.parseDate(value);
        if (parsed == null) {
            throw new InvalidInput();
        }
        return parsed;
    }
}
