package bg.spotyourslot.customer.web;

import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessAccessDenied;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessSuspended;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConflictField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ContactConflict;
import bg.spotyourslot.customer.application.CustomerAdministrationException.CustomerNotFound;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InputField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InvalidInput;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates the typed Customer failures into the approved Problem Details with fixed Bulgarian
 * messages. No response echoes a submitted value, an ID, a constraint name, or a SQL diagnostic.
 * Every problem carries a fixed {@code instance}, so a Customer ID in the request path is never
 * echoed. Unlisted failures fall through to the global handler.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = BusinessCustomerController.class)
public class BusinessCustomerExceptionHandler {
    private static final URI INSTANCE = URI.create("/api/business/customers");
    static final String DISPLAY_NAME_MESSAGE = "Въведете име на клиента до 200 знака.";
    static final String PHONE_MESSAGE = "Въведеният телефонен номер не е валиден.";
    static final String EMAIL_MESSAGE = "Въведеният имейл адрес не е валиден.";
    static final String CONTACT_MESSAGE = "Въведете телефон или имейл.";
    static final String PHONE_CONFLICT_MESSAGE =
            "Този телефонен номер вече е записан за друг клиент.";
    static final String EMAIL_CONFLICT_MESSAGE =
            "Този имейл адрес вече е записан за друг клиент.";

    @ExceptionHandler(InvalidInput.class)
    ProblemDetail invalidInput(InvalidInput exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
        // Only body fields a client can correct are named; paging, sort, search, identifier,
        // version, and command failures stay generic.
        Set<InputField> fields = exception.fields();
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (fields.contains(InputField.DISPLAY_NAME)) {
            fieldErrors.put("displayName", DISPLAY_NAME_MESSAGE);
        }
        if (fields.contains(InputField.PHONE)) {
            fieldErrors.put("phone", PHONE_MESSAGE);
        }
        if (fields.contains(InputField.EMAIL)) {
            fieldErrors.put("email", EMAIL_MESSAGE);
        }
        if (fields.contains(InputField.CONTACT)) {
            fieldErrors.put("contact", CONTACT_MESSAGE);
        }
        if (!fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return problem;
    }

    /** Malformed bodies, parameters, and path IDs: generic, with no field and no echo. */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ProblemDetail malformedInput() {
        return problem(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
    }

    @ExceptionHandler(ContactConflict.class)
    ProblemDetail contactConflict(ContactConflict exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        if (exception.fields().contains(ConflictField.PHONE)) {
            fieldErrors.put("phone", PHONE_CONFLICT_MESSAGE);
        }
        if (exception.fields().contains(ConflictField.EMAIL)) {
            fieldErrors.put("email", EMAIL_CONFLICT_MESSAGE);
        }
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "CUSTOMER_CONTACT_CONFLICT",
                String.join(" ", fieldErrors.values()));
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
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
                HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Нямате достъп до тази операция.");
    }

    @ExceptionHandler(CustomerNotFound.class)
    ProblemDetail customerNotFound() {
        return problem(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Клиентът не е намерен.");
    }

    @ExceptionHandler(ConcurrentUpdate.class)
    ProblemDetail concurrentUpdate() {
        return problem(
                HttpStatus.CONFLICT,
                "CUSTOMER_CONCURRENT_UPDATE",
                "Данните за клиента са променени. Обновете данните и опитайте отново.");
    }

    @ExceptionHandler(CustomerConcurrentConflict.class)
    ProblemDetail concurrentConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "CUSTOMER_CONCURRENT_CONFLICT",
                "Операцията не можа да бъде завършена. Опитайте отново.");
    }

    @ExceptionHandler(BusinessSuspended.class)
    ProblemDetail businessSuspended() {
        return problem(
                HttpStatus.CONFLICT,
                "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
    }

    @ExceptionHandler(CustomerOperationFailure.class)
    ProblemDetail operationFailure() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Възникна неочаквана грешка.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setInstance(INSTANCE);
        problem.setProperty("code", code);
        return problem;
    }
}
