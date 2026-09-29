package bg.spotyourslot.workforce.web;

import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ServiceInactive;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ServiceNotFound;
import bg.spotyourslot.workforce.StaffMemberApplicationException.StaffMemberNotFound;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessStaffMemberController.class)
public class BusinessStaffMemberExceptionHandler {
    @ExceptionHandler(InvalidInput.class)
    ProblemDetail invalidInput(InvalidInput exception) {
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
        // Only body fields a client can correct are named; page, sort,
        // identifier, version, assignment, and command failures stay generic.
        switch (exception.field()) {
            case DISPLAY_NAME -> addFieldError(
                    problem, "displayName", "Въведете име на члена на екипа до 200 знака.");
            case CONTACT_EMAIL -> addFieldError(
                    problem, "contactEmail", "Въведеният имейл адрес не е валиден.");
            case CONTACT_PHONE -> addFieldError(
                    problem, "contactPhone", "Въведеният телефонен номер не е валиден.");
            default -> {
            }
        }
        return problem;
    }

    private static void addFieldError(ProblemDetail problem, String field, String message) {
        problem.setProperty("fieldErrors", Map.of(field, message));
    }

    @ExceptionHandler(SelectedBusinessRequired.class)
    ProblemDetail selectedBusinessRequired() {
        return problem(
                HttpStatus.FORBIDDEN,
                "ACTIVE_BUSINESS_REQUIRED",
                "Изберете бизнес, за да продължите.");
    }

    @ExceptionHandler(BusinessAccessDenied.class)
    ProblemDetail accessDenied() {
        return problem(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Нямате достъп до тази операция.");
    }

    @ExceptionHandler(StaffMemberNotFound.class)
    ProblemDetail staffMemberNotFound() {
        return problem(
                HttpStatus.NOT_FOUND,
                "STAFF_MEMBER_NOT_FOUND",
                "Членът на екипа не е намерен.");
    }

    @ExceptionHandler(ServiceNotFound.class)
    ProblemDetail serviceNotFound() {
        return problem(
                HttpStatus.NOT_FOUND,
                "SERVICE_NOT_FOUND",
                "Услугата не е намерена.");
    }

    @ExceptionHandler(InvalidLifecycleTransition.class)
    ProblemDetail invalidLifecycle() {
        return problem(
                HttpStatus.CONFLICT,
                "STAFF_MEMBER_INVALID_LIFECYCLE",
                "Промяната на състоянието на члена на екипа не е разрешена.");
    }

    @ExceptionHandler(ConcurrentUpdate.class)
    ProblemDetail concurrentUpdate() {
        return problem(
                HttpStatus.CONFLICT,
                "STAFF_MEMBER_CONCURRENT_UPDATE",
                "Данните за члена на екипа са променени. Обновете данните и опитайте отново.");
    }

    @ExceptionHandler(ServiceInactive.class)
    ProblemDetail serviceInactive() {
        return problem(
                HttpStatus.CONFLICT,
                "SERVICE_INACTIVE",
                "Неактивна услуга не може да бъде добавена към член на екипа.");
    }

    @ExceptionHandler(BusinessSuspended.class)
    ProblemDetail businessSuspended() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setProperty("code", code);
        return problem;
    }
}
