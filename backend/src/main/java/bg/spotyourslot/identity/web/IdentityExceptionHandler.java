package bg.spotyourslot.identity.web;

import bg.spotyourslot.identity.application.CurrentPasswordInvalid;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class IdentityExceptionHandler {
    @ExceptionHandler(CurrentPasswordInvalid.class)
    ProblemDetail currentPasswordInvalid() {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Текущата парола е невалидна.");
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setProperty("code", "CURRENT_PASSWORD_INVALID");
        return problem;
    }
}
