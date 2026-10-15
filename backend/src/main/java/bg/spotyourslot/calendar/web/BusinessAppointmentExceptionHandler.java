package bg.spotyourslot.calendar.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem Details of {@link BusinessAppointmentController}; the fixed instance is the route family's base path. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessAppointmentController.class)
public class BusinessAppointmentExceptionHandler extends BusinessCalendarProblems {
    public BusinessAppointmentExceptionHandler() {
        super("/api/business/appointments");
    }
}
