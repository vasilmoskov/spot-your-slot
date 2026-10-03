package bg.spotyourslot.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ExceptionHandler;

class ApiExceptionHandlerTests {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void arbitraryIllegalArgumentDetailsAreNotExposed() {
        var problem = handler.invalid();
        assertThat(problem.getDetail()).isEqualTo("Проверете въведените данни.");
        assertThat(problem.getDetail()).doesNotContain("database-secret-detail");
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_ERROR");
    }

    @Test
    void unexpectedInternalExceptionDetailsAreNotExposed() {
        var problem = handler.unexpected();
        assertThat(problem.getDetail()).isEqualTo("Възникна неочаквана грешка.");
        assertThat(problem.getDetail()).doesNotContain("internal-secret-detail");
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void malformedHttpInputUsesSafeValidationProblem() {
        var problem = handler.malformedInput();

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Проверете въведените данни.");
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_ERROR");
    }

    @Test
    void constraintViolationUsesSafeValidationProblem() throws NoSuchMethodException {
        var mapping = ApiExceptionHandler.class
                .getDeclaredMethod("malformedInput")
                .getAnnotation(ExceptionHandler.class);
        var problem = handler.malformedInput();

        assertThat(mapping.value()).contains(ConstraintViolationException.class);
        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Проверете въведените данни.");
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_ERROR");
        assertThat(problem.getDetail()).doesNotContain("list.page", "rejected-value");
    }

    @Test
    void anUnsupportedMethodIsAFixedMethodNotAllowedProblemWithAllowHeader() {
        var response = handler.methodNotAllowed(
                new org.springframework.web.HttpRequestMethodNotSupportedException(
                        "DELETE", java.util.List.of("GET", "PUT")));
        var problem = response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getAllow()).hasSize(2);
        assertThat(problem.getProperties()).containsEntry("code", "METHOD_NOT_ALLOWED");
        assertThat(problem.getDetail()).isEqualTo("Методът не е разрешен за този адрес.");
        assertThat(String.valueOf(problem.getInstance())).isEqualTo("/api");
    }

    @Test
    void anUnmappedPathIsAFixedNotFoundProblem() {
        var problem = handler.routeNotFound();

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getProperties()).containsEntry("code", "ROUTE_NOT_FOUND");
        assertThat(problem.getDetail()).isEqualTo("Адресът не е намерен.");
        assertThat(String.valueOf(problem.getInstance())).isEqualTo("/api");
    }
}
