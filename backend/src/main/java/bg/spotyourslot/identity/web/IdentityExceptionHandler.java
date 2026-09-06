package bg.spotyourslot.identity.web;

import bg.spotyourslot.identity.application.CurrentPasswordInvalid;
import bg.spotyourslot.identity.application.InvalidInvitation;
import bg.spotyourslot.identity.application.InvitationCredentialMismatch;
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
        return problem("CURRENT_PASSWORD_INVALID", "Текущата парола е невалидна.");
    }

    @ExceptionHandler(InvalidInvitation.class)
    ProblemDetail invalidInvitation() {
        return problem(
                "INVITATION_INVALID",
                "Поканата е невалидна, изтекла или вече е използвана. Поискайте нова покана.");
    }

    @ExceptionHandler(InvitationCredentialMismatch.class)
    ProblemDetail invitationCredentialMismatch() {
        return problem(
                "INVITATION_CREDENTIAL_MISMATCH",
                "Паролата не съвпада със съществуващия профил за този имейл.");
    }

    private ProblemDetail problem(String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Заявката не може да бъде изпълнена.");
        problem.setProperty("code", code);
        return problem;
    }
}
