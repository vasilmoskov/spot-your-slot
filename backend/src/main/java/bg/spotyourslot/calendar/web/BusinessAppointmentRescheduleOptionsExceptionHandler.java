package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentRescheduleOptionsException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem Details of {@link BusinessAppointmentRescheduleOptionsController}; the fixed instance is
 * the Appointment route family's base path, so no identifier is ever echoed. The statuses, codes, and
 * Bulgarian details are those the reschedule route publishes for the same results, so the form shows
 * one wording whether the problem appears while choosing or while saving. Every message is fixed.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessAppointmentRescheduleOptionsController.class)
public class BusinessAppointmentRescheduleOptionsExceptionHandler extends BusinessCalendarProblems {
    public BusinessAppointmentRescheduleOptionsExceptionHandler() {
        super("/api/business/appointments");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.InvalidInput.class)
    ProblemDetail rescheduleOptionsInvalidInput() {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.AccessDenied.class)
    ProblemDetail rescheduleOptionsAccessDenied() {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Нямате достъп до тази операция.");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.BusinessNotActive.class)
    ProblemDetail rescheduleOptionsBusinessNotActive() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_NOT_ACTIVE",
                "Бизнесът не е активен. Часовете могат да се променят само в активен бизнес.");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.BusinessSuspended.class)
    ProblemDetail rescheduleOptionsBusinessSuspended() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.AppointmentNotFound.class)
    ProblemDetail rescheduleOptionsNotFound() {
        return problem(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.StateConflict.class)
    ProblemDetail rescheduleOptionsStateConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_STATE_CONFLICT",
                "Часът не може да бъде променен, защото е отменен или вече е започнал.");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.StaffUnavailable.class)
    ProblemDetail rescheduleOptionsStaffUnavailable() {
        return problem(
                HttpStatus.CONFLICT,
                "APPOINTMENT_STAFF_UNAVAILABLE",
                "Членът на екипа не е наличен за тази промяна.");
    }

    @ExceptionHandler(AppointmentRescheduleOptionsException.Failure.class)
    ProblemDetail rescheduleOptionsFailure() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Възникна неочаквана грешка.");
    }
}
