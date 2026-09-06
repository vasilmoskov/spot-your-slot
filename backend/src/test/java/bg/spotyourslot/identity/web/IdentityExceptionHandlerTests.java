package bg.spotyourslot.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.identity.application.CurrentPasswordInvalid;
import bg.spotyourslot.identity.application.InvalidInvitation;
import bg.spotyourslot.identity.application.InvitationCredentialMismatch;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class IdentityExceptionHandlerTests {
    private final IdentityExceptionHandler handler = new IdentityExceptionHandler();

    @Test
    void mapsCurrentPasswordFailureToSafeProblem() {
        var exception = new CurrentPasswordInvalid();
        var problem = handler.currentPasswordInvalid();

        assertThat(exception.getMessage()).isNull();
        assertThat(exception.getCause()).isNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties())
                .containsEntry("code", "CURRENT_PASSWORD_INVALID");
        assertThat(problem.getDetail()).isEqualTo("Текущата парола е невалидна.");
        assertThat(problem.getDetail())
                .doesNotContain("password", "hash", "exception", "database");
    }

    @Test
    void mapsInvalidInvitationToOneSafeProblem() {
        var exception = new InvalidInvitation();
        var problem = handler.invalidInvitation();

        assertThat(exception.getMessage()).isNull();
        assertThat(exception.getCause()).isNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties()).containsEntry("code", "INVITATION_INVALID");
        assertThat(problem.getDetail())
                .isEqualTo(
                        "Поканата е невалидна, изтекла или вече е използвана. Поискайте нова покана.");
    }

    @Test
    void mapsExistingUserCredentialMismatchToOneSafeProblem() {
        var exception = new InvitationCredentialMismatch();
        var problem = handler.invitationCredentialMismatch();

        assertThat(exception.getMessage()).isNull();
        assertThat(exception.getCause()).isNull();
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties())
                .containsEntry("code", "INVITATION_CREDENTIAL_MISMATCH");
        assertThat(problem.getDetail())
                .isEqualTo("Паролата не съвпада със съществуващия профил за този имейл.");
    }

    @Test
    void handlerIsControllerScopedAndPrecedesTheSharedFallback() {
        var order = IdentityExceptionHandler.class.getAnnotation(Order.class);
        var advice = IdentityExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(advice.assignableTypes()).containsExactly(AuthController.class);
    }
}
