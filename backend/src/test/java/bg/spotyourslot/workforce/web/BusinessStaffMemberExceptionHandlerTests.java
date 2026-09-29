package bg.spotyourslot.workforce.web;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.workforce.StaffMemberApplicationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ProblemDetail;

class BusinessStaffMemberExceptionHandlerTests {
    @ParameterizedTest
    @MethodSource("problems")
    void mapsEveryApprovedOutcomeWithoutInternalDetails(
            ProblemDetail problem, int status, String code, String detail) {
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getTitle()).isEqualTo("Заявката не може да бъде изпълнена.");
        assertThat(problem.getDetail()).isEqualTo(detail);
        assertThat(problem.getProperties()).containsEntry("code", code);
        assertThat(problem.toString())
                .doesNotContain("private-input@example.invalid")
                .doesNotContain("00000000-0000-0000-0000-000000000404")
                .doesNotContain("SQL")
                .doesNotContain("constraint")
                .doesNotContain("Membership")
                .doesNotContain("exception")
                .doesNotContain("stackTrace");
    }

    @Test
    void namesOnlyCorrectableBodyFieldsWithFixedMessages() {
        var handler = new BusinessStaffMemberExceptionHandler();
        var expected = java.util.Map.of(
                StaffMemberApplicationException.InputField.DISPLAY_NAME, "displayName",
                StaffMemberApplicationException.InputField.CONTACT_EMAIL, "contactEmail",
                StaffMemberApplicationException.InputField.CONTACT_PHONE, "contactPhone");
        for (var field : StaffMemberApplicationException.InputField.values()) {
            var problem = handler.invalidInput(
                    new StaffMemberApplicationException.InvalidInput(field));
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
        var handler = new BusinessStaffMemberExceptionHandler();
        return Stream.of(
                Arguments.of(
                        handler.invalidInput(new StaffMemberApplicationException.InvalidInput(StaffMemberApplicationException.InputField.COMMAND)),
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
                        handler.staffMemberNotFound(),
                        404,
                        "STAFF_MEMBER_NOT_FOUND",
                        "Членът на екипа не е намерен."),
                Arguments.of(
                        handler.serviceNotFound(),
                        404,
                        "SERVICE_NOT_FOUND",
                        "Услугата не е намерена."),
                Arguments.of(
                        handler.invalidLifecycle(),
                        409,
                        "STAFF_MEMBER_INVALID_LIFECYCLE",
                        "Промяната на състоянието на члена на екипа не е разрешена."),
                Arguments.of(
                        handler.concurrentUpdate(),
                        409,
                        "STAFF_MEMBER_CONCURRENT_UPDATE",
                        "Данните за члена на екипа са променени. Обновете данните и опитайте отново."),
                Arguments.of(
                        handler.serviceInactive(),
                        409,
                        "SERVICE_INACTIVE",
                        "Неактивна услуга не може да бъде добавена към член на екипа."),
                Arguments.of(
                        handler.businessSuspended(),
                        409,
                        "BUSINESS_SUSPENDED",
                        "Спрян бизнес може само да преглежда данните си."));
    }
}
