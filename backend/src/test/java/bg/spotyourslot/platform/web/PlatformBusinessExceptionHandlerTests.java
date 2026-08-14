package bg.spotyourslot.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ProblemDetail;

class PlatformBusinessExceptionHandlerTests {
    @ParameterizedTest
    @MethodSource("problems")
    void mapsApplicationFailuresToSafeStableProblems(
            ProblemDetail problem, int status, String code, String detail) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getProperties()).containsEntry("code", code);
        assertThat(problem.getDetail()).isEqualTo(detail);
        assertThat(problem.getDetail())
                .doesNotContain("SQL")
                .doesNotContain("constraint")
                .doesNotContain("internal-secret");
    }

    private static Stream<Arguments> problems() {
        var handler = new PlatformBusinessExceptionHandler();
        return Stream.of(
                Arguments.of(
                        handler.invalidInput(),
                        400,
                        "VALIDATION_ERROR",
                        "Проверете въведените данни."),
                Arguments.of(
                        handler.notFound(),
                        404,
                        "BUSINESS_NOT_FOUND",
                        "Бизнесът не е намерен."),
                Arguments.of(
                        handler.slugConflict(),
                        409,
                        "BUSINESS_SLUG_CONFLICT",
                        "Този адрес на бизнеса вече се използва."),
                Arguments.of(
                        handler.invalidLifecycle(),
                        409,
                        "BUSINESS_INVALID_LIFECYCLE",
                        "Промяната на статуса не е разрешена."),
                Arguments.of(
                        handler.missingOwner(),
                        409,
                        "BUSINESS_MISSING_ACTIVE_OWNER",
                        "За активиране е необходим активен собственик."),
                Arguments.of(
                        handler.concurrentUpdate(),
                        409,
                        "BUSINESS_CONCURRENT_UPDATE",
                        "Бизнесът е променен. Обновете данните и опитайте отново."));
    }
}
