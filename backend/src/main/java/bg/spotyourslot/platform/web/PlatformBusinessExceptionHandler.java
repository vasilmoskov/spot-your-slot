package bg.spotyourslot.platform.web;

import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugConflict;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.platform.MissingActiveOwner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PlatformBusinessController.class)
public class PlatformBusinessExceptionHandler {
    @ExceptionHandler(InvalidInput.class)
    ProblemDetail invalidInput() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
    }

    @ExceptionHandler(BusinessNotFound.class)
    ProblemDetail notFound() {
        return problem(
                HttpStatus.NOT_FOUND,
                "BUSINESS_NOT_FOUND",
                "Бизнесът не е намерен.");
    }

    @ExceptionHandler(BusinessSlugConflict.class)
    ProblemDetail slugConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SLUG_CONFLICT",
                "Този адрес на бизнеса вече се използва.");
    }

    @ExceptionHandler(InvalidLifecycleTransition.class)
    ProblemDetail invalidLifecycle() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_INVALID_LIFECYCLE",
                "Промяната на статуса не е разрешена.");
    }

    @ExceptionHandler(MissingActiveOwner.class)
    ProblemDetail missingOwner() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_MISSING_ACTIVE_OWNER",
                "За активиране е необходим активен собственик.");
    }

    @ExceptionHandler(ConcurrentUpdate.class)
    ProblemDetail concurrentUpdate() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_CONCURRENT_UPDATE",
                "Бизнесът е променен. Обновете данните и опитайте отново.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setProperty("code", code);
        return problem;
    }
}
