package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem Details of {@link BusinessAppointmentController}; the fixed instance is the route family's
 * base path. The Appointment creation failures are mapped here only, so the other route families
 * never carry them. Every message is fixed and echoes nothing.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessAppointmentController.class)
public class BusinessAppointmentExceptionHandler extends BusinessCalendarProblems {
    public BusinessAppointmentExceptionHandler() {
        super("/api/business/appointments");
    }

    @ExceptionHandler(AppointmentAdministrationException.InvalidInput.class)
    ProblemDetail invalidAdministrationInput() {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
    }

    @ExceptionHandler(AppointmentAdministrationException.AccessDenied.class)
    ProblemDetail administrationAccessDenied() {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Нямате достъп до тази операция.");
    }

    @ExceptionHandler(AppointmentAdministrationException.BusinessNotActive.class)
    ProblemDetail businessNotActive() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_NOT_ACTIVE",
                "Бизнесът не е активен. Часове могат да се създават само в активен бизнес.");
    }

    @ExceptionHandler(AppointmentAdministrationException.BusinessSuspended.class)
    ProblemDetail businessSuspended() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
    }

    @ExceptionHandler(AppointmentAdministrationException.CustomerNotFound.class)
    ProblemDetail administrationCustomerNotFound() {
        return problem(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Клиентът не е намерен.");
    }

    @ExceptionHandler(AppointmentAdministrationException.ServiceUnavailable.class)
    ProblemDetail serviceUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_SERVICE_UNAVAILABLE",
                "Някоя от избраните услуги вече не е налична.");
    }

    @ExceptionHandler(AppointmentAdministrationException.StaffUnavailable.class)
    ProblemDetail staffUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_STAFF_UNAVAILABLE",
                "Избраният член на екипа не е наличен за тези услуги.");
    }

    @ExceptionHandler(AppointmentAdministrationException.ReviewChanged.class)
    ProblemDetail reviewChanged() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_REVIEW_CHANGED",
                "Услугите са променени. Прегледайте ги отново и опитайте пак.");
    }

    @ExceptionHandler(AppointmentAdministrationException.SlotUnavailable.class)
    ProblemDetail slotUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_SLOT_UNAVAILABLE",
                "Избраният час вече не е свободен.");
    }

    @ExceptionHandler(AppointmentAdministrationException.TemporarilyUnavailable.class)
    ProblemDetail temporarilyUnavailable() {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                "Часът не беше запазен. Опитайте отново след малко.");
    }

    @ExceptionHandler(AppointmentAdministrationException.OutcomeUncertain.class)
    ProblemDetail outcomeUncertain() {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "APPOINTMENT_OUTCOME_UNCERTAIN",
                "Не е ясно дали часът е запазен. Проверете графика, преди да опитате отново.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedMediaType() {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Заявката не може да бъде изпълнена.");
    }

    @ExceptionHandler({AppointmentAdministrationException.Failure.class, BookingOrchestrationFailure.class})
    ProblemDetail administrationFailure() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Възникна неочаквана грешка.");
    }
}
