package bg.spotyourslot.customer.web;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessAccessDenied;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessSuspended;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ConflictField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.ContactConflict;
import bg.spotyourslot.customer.application.CustomerAdministrationException.CustomerNotFound;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InputField;
import bg.spotyourslot.customer.application.CustomerAdministrationException.InvalidInput;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.CreateCustomerRequest;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.CustomerListItemResponse;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.CustomerPageResponse;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.CustomerResponse;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.SearchCustomersRequest;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.UpdateCustomerRequest;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class BusinessCustomerExceptionHandlerTests {
    private final BusinessCustomerExceptionHandler handler = new BusinessCustomerExceptionHandler();

    @Test
    void everyInvalidBodyFieldMapsToItsOwnFieldErrorWithTheApprovedMessage() {
        ProblemDetail problem = handler.invalidInput(new InvalidInput(EnumSet.of(
                InputField.DISPLAY_NAME, InputField.PHONE, InputField.EMAIL, InputField.CONTACT)));

        assertProblem(problem, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
        assertThat(fieldErrors(problem)).containsExactly(
                Map.entry("displayName", "Въведете име на клиента до 200 знака."),
                Map.entry("phone", "Въведеният телефонен номер не е валиден."),
                Map.entry("email", "Въведеният имейл адрес не е валиден."),
                Map.entry("contact", "Въведете телефон или имейл."));
    }

    @Test
    void pagingSortSearchIdentifierVersionAndCommandFailuresStayGenericWithoutFields() {
        for (InputField field : List.of(
                InputField.PAGE, InputField.SIZE, InputField.SORT, InputField.DIRECTION, InputField.SEARCH,
                InputField.CUSTOMER_ID, InputField.BUSINESS_ID, InputField.EXPECTED_VERSION,
                InputField.COMMAND)) {
            ProblemDetail problem = handler.invalidInput(new InvalidInput(field));

            assertProblem(problem, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(problem.getProperties()).doesNotContainKey("fieldErrors");
        }
    }

    @Test
    void contactConflictsNameOnlyTheConflictingFields() {
        ProblemDetail phone = handler.contactConflict(new ContactConflict(EnumSet.of(ConflictField.PHONE)));
        ProblemDetail email = handler.contactConflict(new ContactConflict(EnumSet.of(ConflictField.EMAIL)));
        ProblemDetail both = handler.contactConflict(new ContactConflict(EnumSet.allOf(ConflictField.class)));

        assertProblem(phone, HttpStatus.CONFLICT, "CUSTOMER_CONTACT_CONFLICT",
                "Този телефонен номер вече е записан за друг клиент.");
        assertThat(fieldErrors(phone)).containsOnlyKeys("phone");
        assertProblem(email, HttpStatus.CONFLICT, "CUSTOMER_CONTACT_CONFLICT",
                "Този имейл адрес вече е записан за друг клиент.");
        assertThat(fieldErrors(email)).containsOnlyKeys("email");
        assertThat(fieldErrors(both)).containsOnlyKeys("phone", "email");
        assertThat(both.getDetail()).contains("телефонен номер").contains("имейл адрес");
    }

    @Test
    void theRemainingTypedFailuresMapToTheApprovedCodesMessagesAndStatuses() {
        assertProblem(handler.selectedBusinessRequired(), HttpStatus.FORBIDDEN,
                "ACTIVE_BUSINESS_REQUIRED", "Изберете бизнес, за да продължите.");
        assertProblem(handler.accessDenied(), HttpStatus.FORBIDDEN,
                "ACCESS_DENIED", "Нямате достъп до тази операция.");
        assertProblem(handler.customerNotFound(), HttpStatus.NOT_FOUND,
                "CUSTOMER_NOT_FOUND", "Клиентът не е намерен.");
        assertProblem(handler.concurrentUpdate(), HttpStatus.CONFLICT, "CUSTOMER_CONCURRENT_UPDATE",
                "Данните за клиента са променени. Обновете данните и опитайте отново.");
        assertProblem(handler.concurrentConflict(), HttpStatus.CONFLICT, "CUSTOMER_CONCURRENT_CONFLICT",
                "Операцията не можа да бъде завършена. Опитайте отново.");
        assertProblem(handler.businessSuspended(), HttpStatus.CONFLICT,
                "BUSINESS_SUSPENDED", "Спрян бизнес може само да преглежда данните си.");
        assertProblem(handler.operationFailure(), HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "Възникна неочаквана грешка.");
        assertProblem(handler.malformedInput(), HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR", "Проверете въведените данни.");
    }

    @Test
    void everyProblemHasTheFixedInstanceAndNoCauseDetail() {
        List<ProblemDetail> problems = List.of(
                handler.accessDenied(), handler.customerNotFound(), handler.concurrentUpdate(),
                handler.malformedInput(), handler.operationFailure());

        for (ProblemDetail problem : problems) {
            assertThat(String.valueOf(problem.getInstance())).isEqualTo("/api/business/customers");
        }
    }

    @Test
    void theTypedFailuresCarryOnlyFixedTextAndNoCauseOrSuppressedException() {
        List<RuntimeException> failures = List.of(
                new InvalidInput(InputField.PHONE), new BusinessAccessDenied(), new BusinessSuspended(),
                new CustomerNotFound(), new ContactConflict(EnumSet.of(ConflictField.PHONE)),
                new ConcurrentUpdate(), new CustomerConcurrentConflict(), new CustomerOperationFailure(),
                new SelectedBusinessRequired());

        for (RuntimeException failure : failures) {
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getMessage()).isNotBlank();
        }
    }

    @Test
    void httpRequestAndResponseRecordsRedactTheirToString() {
        UUID id = UUID.randomUUID();
        String sentinel = "Sentinel-Value";
        List<String> texts = List.of(
                new CreateCustomerRequest(sentinel, sentinel, sentinel).toString(),
                new UpdateCustomerRequest(sentinel, sentinel, sentinel, 1L).toString(),
                new SearchCustomersRequest(sentinel, 1, 10, "name", "asc").toString(),
                new CustomerListItemResponse(id, sentinel, sentinel, sentinel).toString(),
                new CustomerResponse(id, sentinel, sentinel, sentinel, 1, Instant.EPOCH, Instant.EPOCH).toString(),
                new CustomerPageResponse(List.of(new CustomerListItemResponse(id, sentinel, sentinel, sentinel)),
                        0, 10, 1).toString());

        for (String text : texts) {
            assertThat(text).contains("redacted").doesNotContain(sentinel).doesNotContain(id.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fieldErrors(ProblemDetail problem) {
        return (Map<String, String>) problem.getProperties().get("fieldErrors");
    }

    private static void assertProblem(ProblemDetail problem, HttpStatus status, String code, String detail) {
        assertThat(problem.getStatus()).isEqualTo(status.value());
        assertThat(problem.getProperties()).containsEntry("code", code);
        assertThat(problem.getDetail()).isEqualTo(detail);
    }
}
