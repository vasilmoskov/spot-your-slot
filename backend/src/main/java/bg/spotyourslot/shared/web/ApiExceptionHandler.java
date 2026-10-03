package bg.spotyourslot.shared.web;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final URI ROUTING_INSTANCE = URI.create("/api");

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail credentials() {
        return problem(
                HttpStatus.UNAUTHORIZED,
                "AUTH_FAILED",
                "Имейлът или паролата са невалидни.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied() {
        return problem(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "Нямате достъп до тази операция.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
    }

    @ExceptionHandler({
        ConstraintViolationException.class,
        HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ProblemDetail malformedInput() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Проверете въведените данни.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException exception) {
        var status = HttpStatus.valueOf(exception.getStatusCode().value());
        return problem(
                status,
                status == HttpStatus.TOO_MANY_REQUESTS ? "RATE_LIMITED" : "REQUEST_REJECTED",
                status == HttpStatus.TOO_MANY_REQUESTS
                        ? "Твърде много опити. Опитайте по-късно."
                        : "Заявката не може да бъде изпълнена.");
    }

    /**
     * A known path with an unsupported method is a client routing error, not a server failure. The
     * instance is fixed so the request path (which can hold an identifier) is never echoed.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        var problem = problem(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "Методът не е разрешен за този адрес.");
        problem.setInstance(ROUTING_INSTANCE);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(exception.getHeaders())
                .body(problem);
    }

    /** An unmapped path is a client routing error with a fixed, non-echoing body. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ProblemDetail routeNotFound() {
        var problem = problem(
                HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Адресът не е намерен.");
        problem.setInstance(ROUTING_INSTANCE);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Възникна неочаквана грешка.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setProperty("code", code);
        return problem;
    }
}
