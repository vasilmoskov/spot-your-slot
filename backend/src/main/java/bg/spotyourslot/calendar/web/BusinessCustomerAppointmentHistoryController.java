package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCalendar;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import bg.spotyourslot.booking.AppointmentCalendarRecords.HistoryOrder;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.HistoryResponse;
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
 * {@code GET /api/business/customers/{customerId}/appointments}: the Booking-owned, server-
 * paginated history of one Customer (ADR-0028 section 11), ordered by start instant. The optional
 * {@code direction} is exactly {@code asc} (default, earlier first) or {@code desc}; the optional
 * {@code date} is one canonical Business-local {@code YYYY-MM-DD}; both apply before paging. Any other
 * value, an empty one, or a repeated parameter is {@code 400 VALIDATION_ERROR}. The Customer module
 * owns no part of it.
 */
@RestController
@RequestMapping("/api/business/customers/{customerId}/appointments")
public class BusinessCustomerAppointmentHistoryController {
    private final AppointmentCalendar calendar;

    public BusinessCustomerAppointmentHistoryController(AppointmentCalendar calendar) {
        this.calendar = calendar;
    }

    @GetMapping
    public HistoryResponse history(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID customerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String date) {
        return HistoryResponse.from(calendar.customerHistory(
                context, customerId, page, size, order(direction), day(date)));
    }

    private static HistoryOrder order(String direction) {
        if (direction == null || direction.equals("asc")) {
            return HistoryOrder.OLDEST_FIRST;
        }
        if (direction.equals("desc")) {
            return HistoryOrder.NEWEST_FIRST;
        }
        throw new InvalidInput();
    }

    private static LocalDate day(String date) {
        if (date == null) {
            return null;
        }
        LocalDate parsed = BusinessCalendarHttpRecords.parseDate(date);
        if (parsed == null) {
            throw new InvalidInput();
        }
        return parsed;
    }
}
