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

    @ExceptionHandler(AppointmentAdministrationException.AppointmentNotFound.class)
    ProblemDetail administrationAppointmentNotFound() {
        return problem(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
    }

    @ExceptionHandler(AppointmentAdministrationException.ConcurrentUpdate.class)
    ProblemDetail concurrentUpdate() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_CONCURRENT_UPDATE",
                "Часът е променен междувременно. Презаредете графика и опитайте отново.");
    }

    @ExceptionHandler(AppointmentAdministrationException.StateConflict.class)
    ProblemDetail stateConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_STATE_CONFLICT",
                "Часът не може да бъде променен, защото е отменен или вече е започнал.");
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

    /**
     * The reschedule and reassignment route. Every result maps to the same status and code as on the
     * create route, but the details of the results whose create wording speaks of creating or saving
     * an Appointment are reworded for changing an existing one. The results that cannot occur on this
     * route (Customer, Service, and reviewed terms) are the generic internal error, never create
     * wording. The cause is only inspected for its type.
     */
    @ExceptionHandler(RescheduleFailure.class)
    ProblemDetail rescheduleFailure(RescheduleFailure failure) {
        return switch (failure.failure()) {
            case AppointmentAdministrationException.InvalidInput invalid -> invalidAdministrationInput();
            case AppointmentAdministrationException.AccessDenied denied -> administrationAccessDenied();
            case AppointmentAdministrationException.BusinessNotActive inactive -> problem(
                    HttpStatus.CONFLICT,
                    "BUSINESS_NOT_ACTIVE",
                    "Бизнесът не е активен. Часовете могат да се променят само в активен бизнес.");
            case AppointmentAdministrationException.BusinessSuspended suspended -> businessSuspended();
            case AppointmentAdministrationException.AppointmentNotFound missing ->
                    administrationAppointmentNotFound();
            case AppointmentAdministrationException.ConcurrentUpdate stale -> concurrentUpdate();
            case AppointmentAdministrationException.StateConflict state -> stateConflict();
            case AppointmentAdministrationException.StaffUnavailable staff -> problem(
                    HttpStatus.CONFLICT,
                    "APPOINTMENT_STAFF_UNAVAILABLE",
                    "Членът на екипа не е наличен за тази промяна.");
            case AppointmentAdministrationException.SlotUnavailable slot -> slotUnavailable();
            case AppointmentAdministrationException.TemporarilyUnavailable unavailable -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                    "Промяната не беше направена. Часът е непроменен. Опитайте отново след малко.");
            case AppointmentAdministrationException.OutcomeUncertain uncertain -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "APPOINTMENT_OUTCOME_UNCERTAIN",
                    "Не е ясно дали промяната е направена. Презаредете графика и проверете часа, преди да опитате отново.");
            case AppointmentAdministrationException.CustomerNotFound unexpected -> administrationFailure();
            case AppointmentAdministrationException.ServiceUnavailable unexpected -> administrationFailure();
            case AppointmentAdministrationException.ReviewChanged unexpected -> administrationFailure();
            case AppointmentAdministrationException.Failure failed -> administrationFailure();
        };
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
