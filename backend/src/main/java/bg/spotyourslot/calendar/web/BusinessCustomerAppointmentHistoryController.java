package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCalendar;
import bg.spotyourslot.calendar.web.BusinessCalendarHttpRecords.HistoryResponse;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.util.UUID;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/business/customers/{customerId}/appointments}: the Booking-owned, server-
 * paginated history of one Customer, newest first (ADR-0028 section 11). The Customer module
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
            @RequestParam(required = false) Integer size) {
        return HistoryResponse.from(calendar.customerHistory(context, customerId, page, size));
    }
}
