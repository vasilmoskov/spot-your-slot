package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCalendar;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.CalendarResponse;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/business/calendar}: the Appointments of a window of at most 31 Business-local
 * dates with the working facts of the StaffMembers. Owner-only; the Business comes from the
 * authenticated context.
 */
@RestController
@RequestMapping("/api/business/calendar")
public class BusinessCalendarController {
    private final AppointmentCalendar calendar;

    public BusinessCalendarController(AppointmentCalendar calendar) {
        this.calendar = calendar;
    }

    @GetMapping
    public CalendarResponse calendar(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) UUID staffMemberId) {
        return CalendarResponse.from(calendar.calendar(
                context, parse(from), parse(to), staffMemberId));
    }

    private static LocalDate parse(String value) {
        LocalDate parsed = BusinessCalendarHttpRecords.parseDate(value);
        if (parsed == null) {
            throw new InvalidInput();
        }
        return parsed;
    }
}
