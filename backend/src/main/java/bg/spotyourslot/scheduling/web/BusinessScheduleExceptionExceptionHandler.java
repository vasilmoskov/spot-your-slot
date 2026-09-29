package bg.spotyourslot.scheduling.web;

import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessAccessDenied;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessSuspended;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ConcurrentUpdate;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.OverlapConflict;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ScheduleExceptionNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberInactive;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberNotFound;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessScheduleExceptionController.class)
public class BusinessScheduleExceptionExceptionHandler {
    @ExceptionHandler(InvalidInput.class)
    ProblemDetail invalidInput(InvalidInput exception) {
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
        // Only body fields a client can correct are named; the identifier,
        // version, list window, and whole-command failures stay generic.
        switch (exception.field()) {
            case KIND -> addFieldError(problem, "kind", "Изберете вид на изключението.");
            case STAFF_MEMBER_ID -> addFieldError(
                    problem,
                    "staffMemberId",
                    "Членът на екипа е задължителен само за изключения, които го засягат.");
            case FIRST_DATE -> addFieldError(
                    problem,
                    "firstDate",
                    "Въведете валидна начална дата от 01.01.2000 до 31.12.2100.");
            case LAST_DATE -> addFieldError(
                    problem,
                    "lastDate",
                    "Крайната дата не може да е преди началната и обхватът е до 366 дни; "
                            + "изключение с часове е само за един ден.");
            case ALL_DAY -> addFieldError(
                    problem, "allDay", "Проверете дали изключението е за цели дни.");
            case PERIODS -> addFieldError(
                    problem,
                    "periods",
                    "Проверете периодите: до 24, начало преди край, без припокриване.");
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

    @ExceptionHandler(BusinessSuspended.class)
    ProblemDetail businessSuspended() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
    }

    @ExceptionHandler(StaffMemberNotFound.class)
    ProblemDetail staffMemberNotFound() {
        return problem(
                HttpStatus.NOT_FOUND,
                "STAFF_MEMBER_NOT_FOUND",
                "Членът на екипа не е намерен.");
    }

    @ExceptionHandler(StaffMemberInactive.class)
    ProblemDetail staffMemberInactive() {
        return problem(
                HttpStatus.CONFLICT,
                "STAFF_MEMBER_INACTIVE",
                "Изключенията на неактивен член на екипа не могат да бъдат променяни.");
    }

    @ExceptionHandler(ScheduleExceptionNotFound.class)
    ProblemDetail scheduleExceptionNotFound() {
        return problem(
                HttpStatus.NOT_FOUND,
                "SCHEDULE_EXCEPTION_NOT_FOUND",
                "Изключението от графика не е намерено.");
    }

    @ExceptionHandler(OverlapConflict.class)
    ProblemDetail overlap() {
        return problem(
                HttpStatus.CONFLICT,
                "SCHEDULE_EXCEPTION_OVERLAP",
                "Вече има изключение от същия вид за тези дати.");
    }

    @ExceptionHandler(ConcurrentUpdate.class)
    ProblemDetail concurrentUpdate() {
        return problem(
                HttpStatus.CONFLICT,
                "SCHEDULE_EXCEPTION_CONCURRENT_UPDATE",
                "Изключението от графика е променено от друга операция. "
                        + "Обновете данните и опитайте отново.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setProperty("code", code);
        return problem;
    }
}
