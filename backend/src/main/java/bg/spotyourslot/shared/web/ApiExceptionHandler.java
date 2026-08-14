package bg.spotyourslot.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
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
