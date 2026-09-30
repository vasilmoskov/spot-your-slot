package bg.spotyourslot.platform.web;

import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugConflict;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugImmutable;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugReserved;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InputField;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.platform.MissingActiveOwner;
import java.util.Map;
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
    ProblemDetail invalidInput(InvalidInput exception) {
        var problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
        // Only the reserved public address is named; every other failure stays generic.
        // The reserved list itself is never disclosed.
        if (exception.field() == InputField.RESERVED_SLUG) {
            problem.setProperty(
                    "fieldErrors",
                    Map.of("slug", "Изберете друг публичен адрес на бизнеса."));
        }
        return problem;
    }

    @ExceptionHandler(BusinessSlugImmutable.class)
    ProblemDetail slugImmutable() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SLUG_IMMUTABLE",
                "Публичният адрес на активиран бизнес не може да бъде променян.");
    }

    @ExceptionHandler(BusinessSlugReserved.class)
    ProblemDetail slugReserved() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SLUG_RESERVED",
                "Променете публичния адрес на бизнеса преди активиране.");
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
