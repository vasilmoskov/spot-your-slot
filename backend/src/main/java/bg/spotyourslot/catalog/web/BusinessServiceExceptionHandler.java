package bg.spotyourslot.catalog.web;

import bg.spotyourslot.catalog.ServiceApplicationException.BusinessAccessDenied;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessSuspended;
import bg.spotyourslot.catalog.ServiceApplicationException.ConcurrentUpdate;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidInput;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNameConflict;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNotFound;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessServiceController.class)
public class BusinessServiceExceptionHandler {
    @ExceptionHandler(InvalidInput.class)
    ProblemDetail invalidInput() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
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

    @ExceptionHandler(ServiceNotFound.class)
    ProblemDetail notFound() {
        return problem(
                HttpStatus.NOT_FOUND,
                "SERVICE_NOT_FOUND",
                "Услугата не е намерена.");
    }

    @ExceptionHandler(ServiceNameConflict.class)
    ProblemDetail nameConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "SERVICE_NAME_CONFLICT",
                "Вече съществува услуга с това име.");
    }

    @ExceptionHandler(InvalidLifecycleTransition.class)
    ProblemDetail invalidLifecycle() {
        return problem(
                HttpStatus.CONFLICT,
                "SERVICE_INVALID_LIFECYCLE",
                "Промяната на състоянието на услугата не е разрешена.");
    }

    @ExceptionHandler(ConcurrentUpdate.class)
    ProblemDetail concurrentUpdate() {
        return problem(
                HttpStatus.CONFLICT,
                "SERVICE_CONCURRENT_UPDATE",
                "Услугата е променена. Обновете данните и опитайте отново.");
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
