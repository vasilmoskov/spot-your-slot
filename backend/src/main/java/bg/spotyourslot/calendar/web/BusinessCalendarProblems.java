package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCalendarException.AccessDenied;
import bg.spotyourslot.booking.AppointmentCalendarException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentCalendarException.CustomerNotFound;
import bg.spotyourslot.booking.AppointmentCalendarException.Failure;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import bg.spotyourslot.booking.AppointmentCalendarException.StaffMemberNotFound;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The fixed Problem Details of the calendar API. Each route family has one fixed {@code instance}
 * (the family's base path, the convention of the Customer API), so no Appointment, Customer, or
 * StaffMember identifier, query value, or raw request path is ever echoed. A missing and a foreign
 * identifier raise the same exception and therefore produce byte-identical responses. The
 * Bulgarian messages are fixed. Subclasses only choose the instance.
 */
abstract class BusinessCalendarProblems {
    private final URI instance;

    BusinessCalendarProblems(String instance) {
        this.instance = URI.create(instance);
    }

    @ExceptionHandler({
        InvalidInput.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ProblemDetail invalidInput() {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
    }

    @ExceptionHandler(SelectedBusinessRequired.class)
    ProblemDetail selectedBusinessRequired() {
        return problem(
                HttpStatus.FORBIDDEN, "ACTIVE_BUSINESS_REQUIRED", "Изберете бизнес, за да продължите.");
    }

    @ExceptionHandler(AccessDenied.class)
    ProblemDetail accessDenied() {
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Нямате достъп до тази операция.");
    }

    @ExceptionHandler(AppointmentNotFound.class)
    ProblemDetail appointmentNotFound() {
        return problem(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
    }

    @ExceptionHandler(CustomerNotFound.class)
    ProblemDetail customerNotFound() {
        return problem(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Клиентът не е намерен.");
    }

    @ExceptionHandler(StaffMemberNotFound.class)
    ProblemDetail staffMemberNotFound() {
        return problem(HttpStatus.NOT_FOUND, "STAFF_MEMBER_NOT_FOUND", "Членът на екипа не е намерен.");
    }

    @ExceptionHandler(Failure.class)
    ProblemDetail failure() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Възникна неочаквана грешка.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setInstance(instance);
        problem.setProperty("code", code);
        return problem;
    }
}
