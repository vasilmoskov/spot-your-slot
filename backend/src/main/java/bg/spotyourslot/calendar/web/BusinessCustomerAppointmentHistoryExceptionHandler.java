package bg.spotyourslot.calendar.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem Details of {@link BusinessCustomerAppointmentHistoryController}; the fixed instance is the route family's base path. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessCustomerAppointmentHistoryController.class)
public class BusinessCustomerAppointmentHistoryExceptionHandler extends BusinessCalendarProblems {
    public BusinessCustomerAppointmentHistoryExceptionHandler() {
        super("/api/business/customers");
    }
}
