package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The two {@code 503} outcomes of {@code POST /api/business/appointments} through the real route,
 * security chain, parser, controller, and exception advice, with the administration capability
 * replaced by a deterministic test double that raises exactly the published outcome. Infrastructure
 * failures are deliberately not provoked through HTTP: the orchestration that produces these
 * outcomes is proven against PostgreSQL in {@code ManualAppointmentCommitFailureIntegrationTests} and
 * the runner unit tests.
 */
class BusinessAppointmentCreateUnavailableApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String SERVICE_NAME = "СЕНТИНЕЛ-УСЛУГА-3321";
    private static final String PATH = "/api/business/appointments";

    @MockitoBean AppointmentAdministration administration;

    private Actor owner;
    private Tenant tenant;
    private String content;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", CUSTOMER);
        content = ("{\"customerId\":\"%s\",\"staffMemberId\":\"%s\",\"start\":\"2026-11-10T09:00:00+02:00\","
                + "\"services\":[{\"serviceId\":\"%s\",\"reviewedServiceName\":\"%s\","
                + "\"reviewedDurationMinutes\":30,\"reviewedPriceCents\":1000}],\"internalNote\":\"%s\"}")
                .formatted(tenant.customer(), tenant.staff(), tenant.service(), SERVICE_NAME, NOTE);
    }

    private ResultActions send() throws Exception {
        return mvc.perform(post(PATH).cookie(owner.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private void assertUnavailable(
            AppointmentAdministrationException outcome, String code, String detail) throws Exception {
        when(administration.create(any(), any())).thenThrow(outcome);

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
                CUSTOMER, NOTE, SERVICE_NAME, "Мария Петрова",
                tenant.customer().toString(), tenant.staff().toString(), tenant.service().toString(),
                owner.businessId().toString(), owner.userId().toString(),
                "2026-11-10", "reviewed", "SQL", "sql", "40001", "40P01", "25P02", "constraint", "appointment_",
                "Exception", "org.", "java.", "bg.spotyourslot", "Caused", "stack", "cause");
        for (String value : forbidden) {
            assertThat(text).as(value).doesNotContain(value);
        }
        verify(administration, times(1)).create(any(), any());
    }

    @Test
    void temporarilyUnavailableIsAFixed503WithNoLocationAndNoLeak() throws Exception {
        assertUnavailable(
                new AppointmentAdministrationException.TemporarilyUnavailable(),
                "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                "Часът не беше запазен. Опитайте отново след малко.");
    }

    @Test
    void outcomeUncertainIsAFixed503WithNoLocationAndNoLeak() throws Exception {
        assertUnavailable(
                new AppointmentAdministrationException.OutcomeUncertain(),
                "APPOINTMENT_OUTCOME_UNCERTAIN",
                "Не е ясно дали часът е запазен. Проверете графика, преди да опитате отново.");
    }

    @Test
    void theTwoOutcomesAreDistinctAndTheRouteIsStillTheOnlyOneCalled() throws Exception {
        when(administration.create(any(), any()))
                .thenThrow(new AppointmentAdministrationException.TemporarilyUnavailable())
                .thenThrow(new AppointmentAdministrationException.OutcomeUncertain());

        String first = body(send());
        String second = body(send());

        assertThat(first).isNotEqualTo(second);
        assertThat(json(send().andExpect(status().isServiceUnavailable())).get("code").asString())
                .isEqualTo("APPOINTMENT_OUTCOME_UNCERTAIN");
        verify(administration, times(3)).create(any(), any());
    }
}
