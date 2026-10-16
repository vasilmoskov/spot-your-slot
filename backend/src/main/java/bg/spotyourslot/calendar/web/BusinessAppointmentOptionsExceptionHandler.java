package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCreationOptionsException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem Details of {@link BusinessAppointmentOptionsController}; the fixed instance is the route
 * family's base path. The statuses, codes, and Bulgarian details are those the creation publishes for
 * the same results, so the form shows one wording whether the problem appears while choosing or while
 * saving. Every message is fixed and echoes nothing.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessAppointmentOptionsController.class)
public class BusinessAppointmentOptionsExceptionHandler extends BusinessCalendarProblems {
    public BusinessAppointmentOptionsExceptionHandler() {
        super("/api/business/appointment-options");
    }

    @ExceptionHandler(AppointmentCreationOptionsException.InvalidInput.class)
    ProblemDetail optionsInvalidInput() {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
    }

    @ExceptionHandler(AppointmentCreationOptionsException.AccessDenied.class)
    ProblemDetail optionsAccessDenied() {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Нямате достъп до тази операция.");
    }

    @ExceptionHandler(AppointmentCreationOptionsException.BusinessNotActive.class)
    ProblemDetail optionsBusinessNotActive() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_NOT_ACTIVE",
                "Бизнесът не е активен. Часове могат да се създават само в активен бизнес.");
    }

    @ExceptionHandler(AppointmentCreationOptionsException.BusinessSuspended.class)
    ProblemDetail optionsBusinessSuspended() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
    }

    @ExceptionHandler(AppointmentCreationOptionsException.ServiceUnavailable.class)
    ProblemDetail optionsServiceUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_SERVICE_UNAVAILABLE",
                "Някоя от избраните услуги вече не е налична.");
    }

    @ExceptionHandler(AppointmentCreationOptionsException.StaffUnavailable.class)
    ProblemDetail optionsStaffUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_STAFF_UNAVAILABLE",
                "Избраният член на екипа не е наличен за тези услуги.");
    }

    @ExceptionHandler(AppointmentCreationOptionsException.Failure.class)
    ProblemDetail optionsFailure() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Възникна неочаквана грешка.");
    }
}
