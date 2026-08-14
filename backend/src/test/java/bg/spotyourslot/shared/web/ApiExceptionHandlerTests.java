package bg.spotyourslot.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
