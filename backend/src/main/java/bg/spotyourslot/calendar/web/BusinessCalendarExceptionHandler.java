package bg.spotyourslot.calendar.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem Details of {@link BusinessCalendarController}; the fixed instance is the route family's base path. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessCalendarController.class)
public class BusinessCalendarExceptionHandler extends BusinessCalendarProblems {
    public BusinessCalendarExceptionHandler() {
        super("/api/business/calendar");
    }
}
