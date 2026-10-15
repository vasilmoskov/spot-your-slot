package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCalendar;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.AgendaResponse;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.DetailResponse;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/business/appointments} (the paginated agenda) and
 * {@code GET /api/business/appointments/{id}} (the owner-only detail).
 */
@RestController
@RequestMapping("/api/business/appointments")
public class BusinessAppointmentController {
    private final AppointmentCalendar calendar;

    public BusinessAppointmentController(AppointmentCalendar calendar) {
        this.calendar = calendar;
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
