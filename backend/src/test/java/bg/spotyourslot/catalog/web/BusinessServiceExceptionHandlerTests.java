package bg.spotyourslot.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.catalog.ServiceApplicationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ProblemDetail;

class BusinessServiceExceptionHandlerTests {
    @ParameterizedTest
    @MethodSource("problems")
    void mapsEveryApprovedOutcomeWithoutInternalDetails(
            ProblemDetail problem, int status, String code, String detail) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getTitle()).isEqualTo("Заявката не може да бъде изпълнена.");
        assertThat(problem.getDetail()).isEqualTo(detail);
        assertThat(problem.getProperties()).containsEntry("code", code);
        assertThat(problem.toString())
                .doesNotContain("internal")
                .doesNotContain("SQL")
                .doesNotContain("constraint")
                .doesNotContain("exception");
    }

    @Test
    void namesOnlyCorrectableBodyFieldsWithFixedMessages() {
        var handler = new BusinessServiceExceptionHandler();
        var expected = java.util.Map.of(
                ServiceApplicationException.InputField.NAME, "name",
                ServiceApplicationException.InputField.DESCRIPTION, "description",
                ServiceApplicationException.InputField.DURATION_MINUTES, "durationMinutes",
                ServiceApplicationException.InputField.PRICE, "price");
        for (var field : ServiceApplicationException.InputField.values()) {
            var problem = handler.invalidInput(new ServiceApplicationException.InvalidInput(field));
            assertThat(problem.getStatus()).isEqualTo(400);
            assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_ERROR");
            assertThat(problem.getDetail()).isEqualTo("Проверете въведените данни.");
            if (expected.containsKey(field)) {
                @SuppressWarnings("unchecked")
                var fieldErrors = (java.util.Map<String, String>) problem.getProperties()
                        .get("fieldErrors");
                assertThat(fieldErrors).containsOnlyKeys(expected.get(field));
                assertThat(fieldErrors.get(expected.get(field))).isNotBlank();
            } else {
                assertThat(problem.getProperties()).doesNotContainKey("fieldErrors");
            }
        }
    }

    private static Stream<Arguments> problems() {
        var handler = new BusinessServiceExceptionHandler();
        return Stream.of(
                Arguments.of(
                        handler.invalidInput(new ServiceApplicationException.InvalidInput(ServiceApplicationException.InputField.COMMAND)),
                        400,
                        "VALIDATION_ERROR",
                        "Проверете въведените данни."),
                Arguments.of(
                        handler.selectedBusinessRequired(),
                        403,
                        "ACTIVE_BUSINESS_REQUIRED",
                        "Изберете бизнес, за да продължите."),
                Arguments.of(
                        handler.accessDenied(),
                        403,
                        "ACCESS_DENIED",
                        "Нямате достъп до тази операция."),
                Arguments.of(
                        handler.notFound(),
                        404,
                        "SERVICE_NOT_FOUND",
                        "Услугата не е намерена."),
                Arguments.of(
                        handler.nameConflict(),
                        409,
                        "SERVICE_NAME_CONFLICT",
                        "Вече съществува услуга с това име."),
                Arguments.of(
                        handler.invalidLifecycle(),
                        409,
                        "SERVICE_INVALID_LIFECYCLE",
                        "Промяната на състоянието на услугата не е разрешена."),
                Arguments.of(
                        handler.concurrentUpdate(),
                        409,
                        "SERVICE_CONCURRENT_UPDATE",
                        "Услугата е променена. Обновете данните и опитайте отново."),
                Arguments.of(
                        handler.businessSuspended(),
                        409,
                        "BUSINESS_SUSPENDED",
                        "Спрян бизнес може само да преглежда данните си."));
    }

}
