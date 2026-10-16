package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentAdministration;
import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The two {@code 503} outcomes of {@code POST /api/business/appointments/{id}/reschedule} through the
 * real route, security chain, parser, controller, and exception advice, with the administration
 * capability replaced by a deterministic test double that raises exactly the published outcome.
 * Infrastructure failures are deliberately not provoked through HTTP: the orchestration that produces
 * these outcomes is proven against PostgreSQL in
 * {@code AppointmentRescheduleCommitFailureIntegrationTests} and the runner unit tests, so this class
 * proves the route, the advice, and the privacy of the problem.
 */
class BusinessAppointmentRescheduleUnavailableApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String PATH = "/api/business/appointments";

    @MockitoBean AppointmentAdministration administration;

    private Actor owner;
    private Tenant tenant;
    private UUID appointment;
    private String content;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", CUSTOMER);
        appointment = appointment(tenant, NOV_10, 30, row -> row.put("internal_note", NOTE));
        content = "{\"expectedVersion\":0,\"start\":\"2026-11-10T10:30:00+02:00\",\"staffMemberId\":\""
                + tenant.staff() + "\"}";
    }

    private ResultActions send() throws Exception {
        return mvc.perform(post(PATH + "/{id}/reschedule", appointment).cookie(owner.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private void assertUnavailable(
            AppointmentAdministrationException outcome, String code, String detail) throws Exception {
        when(administration.reschedule(any(), any())).thenThrow(outcome);

        ResultActions result = send();

        result.andExpect(status().isServiceUnavailable());
        var response = result.andReturn().getResponse();
        JsonNode problem = json(result);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(problem.get("status").asInt()).isEqualTo(503);
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("detail").asString()).isEqualTo(detail);
        assertThat(problem.get("title").asString()).isEqualTo("Заявката не може да бъде изпълнена.");
        assertThat(problem.get("instance").asString()).isEqualTo(PATH);
        assertThat(keys(problem)).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();

        String text = body(result);
        List<String> forbidden = List.of(
                CUSTOMER, NOTE, "Мария Петрова",
                tenant.customer().toString(), tenant.staff().toString(), tenant.service().toString(),
                appointment.toString(), owner.businessId().toString(), owner.userId().toString(),
                "2026-11-10", "SQL", "sql", "40001", "40P01", "25P02", "constraint", "appointment_",
                "Exception", "org.", "java.", "bg.spotyourslot", "Caused", "stack", "cause");
        for (String value : forbidden) {
            assertThat(text).as(value).doesNotContain(value);
        }
        verify(administration, times(1)).reschedule(any(), any());
    }

    @Test
    void temporarilyUnavailableIsAFixed503WithNoLocationAndNoLeak() throws Exception {
        assertUnavailable(
                new AppointmentAdministrationException.TemporarilyUnavailable(),
                "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                "Промяната не беше направена. Часът е непроменен. Опитайте отново след малко.");
    }

    @Test
    void outcomeUncertainIsAFixed503WithNoLocationAndNoLeak() throws Exception {
        assertUnavailable(
                new AppointmentAdministrationException.OutcomeUncertain(),
                "APPOINTMENT_OUTCOME_UNCERTAIN",
                "Не е ясно дали промяната е направена. Презаредете графика и проверете часа, преди да опитате отново.");
    }

    private record Result(AppointmentAdministrationException failure, int status, String code, String detail) {
    }

    @Test
    void everyResultTheRouteCanReturnHasItsExactWordingAndNoneSpeaksOfCreatingOrSavingAnAppointment()
            throws Exception {
        List<Result> results = List.of(
                new Result(new AppointmentAdministrationException.InvalidInput(), 400, "VALIDATION_ERROR",
                        "Проверете въведените данни."),
                new Result(new AppointmentAdministrationException.AccessDenied(), 403, "ACCESS_DENIED",
                        "Нямате достъп до тази операция."),
                new Result(new AppointmentAdministrationException.BusinessNotActive(), 409, "BUSINESS_NOT_ACTIVE",
                        "Бизнесът не е активен. Часовете могат да се променят само в активен бизнес."),
                new Result(new AppointmentAdministrationException.BusinessSuspended(), 409, "BUSINESS_SUSPENDED",
                        "Спрян бизнес може само да преглежда данните си."),
                new Result(new AppointmentAdministrationException.AppointmentNotFound(), 404,
                        "APPOINTMENT_NOT_FOUND", "Часът не е намерен."),
                new Result(new AppointmentAdministrationException.ConcurrentUpdate(), 409,
                        "APPOINTMENT_CONCURRENT_UPDATE",
                        "Часът е променен междувременно. Презаредете графика и опитайте отново."),
                new Result(new AppointmentAdministrationException.StateConflict(), 409,
                        "APPOINTMENT_STATE_CONFLICT",
                        "Часът не може да бъде променен, защото е отменен или вече е започнал."),
                new Result(new AppointmentAdministrationException.StaffUnavailable(), 409,
                        "APPOINTMENT_STAFF_UNAVAILABLE", "Членът на екипа не е наличен за тази промяна."),
                new Result(new AppointmentAdministrationException.SlotUnavailable(), 409,
                        "APPOINTMENT_SLOT_UNAVAILABLE", "Избраният час вече не е свободен."),
                new Result(new AppointmentAdministrationException.TemporarilyUnavailable(), 503,
                        "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                        "Промяната не беше направена. Часът е непроменен. Опитайте отново след малко."),
                new Result(new AppointmentAdministrationException.OutcomeUncertain(), 503,
                        "APPOINTMENT_OUTCOME_UNCERTAIN",
                        "Не е ясно дали промяната е направена. Презаредете графика и проверете часа, преди да опитате отново."),
                new Result(new AppointmentAdministrationException.Failure(), 500, "INTERNAL_ERROR",
                        "Възникна неочаквана грешка."),
                // Results that cannot occur on this route are the generic internal error, never create wording.
                new Result(new AppointmentAdministrationException.CustomerNotFound(), 500, "INTERNAL_ERROR",
                        "Възникна неочаквана грешка."),
                new Result(new AppointmentAdministrationException.ServiceUnavailable(), 500, "INTERNAL_ERROR",
                        "Възникна неочаквана грешка."),
                new Result(new AppointmentAdministrationException.ReviewChanged(), 500, "INTERNAL_ERROR",
                        "Възникна неочаквана грешка."));

        for (Result result : results) {
            doThrow(result.failure()).when(administration).reschedule(any(), any());

            ResultActions response = send();

            response.andExpect(status().is(result.status()));
            JsonNode problem = json(response);
            String label = result.failure().getClass().getSimpleName();
            assertThat(problem.get("code").asString()).as(label).isEqualTo(result.code());
            assertThat(problem.get("detail").asString()).as(label).isEqualTo(result.detail());
            assertThat(problem.get("instance").asString()).as(label).isEqualTo(PATH);
            assertThat(keys(problem)).as(label).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
            assertThat(problem.get("detail").asString()).as(label)
                    .doesNotContainIgnoringCase("създа").doesNotContainIgnoringCase("запаз")
                    .doesNotContainIgnoringCase("услуги");
            assertThat(body(response)).as(label).doesNotContain(CUSTOMER).doesNotContain(NOTE)
                    .doesNotContain(appointment.toString()).doesNotContain("Exception");
        }
    }

    @Test
    void theTwoOutcomesAreDistinctFromEachOtherAndFromTheInternalError() throws Exception {
        when(administration.reschedule(any(), any()))
                .thenThrow(new AppointmentAdministrationException.TemporarilyUnavailable())
                .thenThrow(new AppointmentAdministrationException.OutcomeUncertain())
                .thenThrow(new AppointmentAdministrationException.Failure());

        String first = body(send());
        String second = body(send());
        ResultActions failure = send();

        assertThat(first).isNotEqualTo(second);
        failure.andExpect(status().isInternalServerError());
        assertThat(json(failure).get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(body(failure)).doesNotContain(CUSTOMER).doesNotContain(NOTE).doesNotContain("Failed");
        verify(administration, times(3)).reschedule(any(), any());
    }
}
