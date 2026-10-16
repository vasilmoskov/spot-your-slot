package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.AppointmentLifecycleAdministration;
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
 * Every result the cancellation and the internal-note routes can return, through the real route,
 * security chain, parser, controller, and exception advice, with the lifecycle capability replaced by
 * a deterministic test double that raises exactly the published outcome (<b>injected</b>). The
 * orchestration that produces the outcomes is proven against PostgreSQL in
 * {@code AppointmentLifecycleCommitFailureIntegrationTests} and the runner unit tests; this class
 * proves the route, the advice, the exact operation-specific wording, and the privacy of each problem.
 */
class BusinessAppointmentLifecycleUnavailableApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String PATH = "/api/business/appointments";
    private static final String GENERIC = "Възникна неочаквана грешка.";

    @MockitoBean AppointmentLifecycleAdministration lifecycle;

    private Actor owner;
    private Tenant tenant;
    private UUID appointment;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", CUSTOMER);
        appointment = appointment(tenant, NOV_10, 30, row -> row.put("internal_note", NOTE));
    }

    private enum Route {
        CANCEL,
        NOTE
    }

    private ResultActions send(Route route) throws Exception {
        if (route == Route.CANCEL) {
            return mvc.perform(post(PATH + "/{id}/cancel", appointment).cookie(owner.session()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"));
        }
        return mvc.perform(put(PATH + "/{id}/internal-note", appointment).cookie(owner.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"internalNote\":\"ново\"}"));
    }

    private void raise(Route route, AppointmentAdministrationException failure) {
        if (route == Route.CANCEL) {
            doThrow(failure).when(lifecycle).cancel(any(), any());
        } else {
            doThrow(failure).when(lifecycle).editInternalNote(any(), any());
        }
    }

    private record Result(AppointmentAdministrationException failure, int status, String code, String detail) {
    }

    private static List<Result> cancelResults() {
        return List.of(
                new Result(new AppointmentAdministrationException.InvalidInput(), 400, "VALIDATION_ERROR",
                        "Проверете въведените данни."),
                new Result(new AppointmentAdministrationException.AccessDenied(), 403, "ACCESS_DENIED",
                        "Нямате достъп до тази операция."),
                new Result(new AppointmentAdministrationException.AppointmentNotFound(), 404,
                        "APPOINTMENT_NOT_FOUND", "Часът не е намерен."),
                new Result(new AppointmentAdministrationException.BusinessNotActive(), 409, "BUSINESS_NOT_ACTIVE",
                        "Бизнесът не е активен. Часовете могат да се отменят само в активен бизнес."),
                new Result(new AppointmentAdministrationException.BusinessSuspended(), 409, "BUSINESS_SUSPENDED",
                        "Спрян бизнес може само да преглежда данните си."),
                new Result(new AppointmentAdministrationException.ConcurrentUpdate(), 409,
                        "APPOINTMENT_CONCURRENT_UPDATE",
                        "Часът е променен междувременно. Презаредете графика и опитайте отново."),
                new Result(new AppointmentAdministrationException.StateConflict(), 409,
                        "APPOINTMENT_STATE_CONFLICT",
                        "Часът не може да бъде отменен, защото вече е отменен или е започнал."),
                new Result(new AppointmentAdministrationException.TemporarilyUnavailable(), 503,
                        "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                        "Часът не беше отменен. Той е непроменен. Опитайте отново след малко."),
                new Result(new AppointmentAdministrationException.OutcomeUncertain(), 503,
                        "APPOINTMENT_OUTCOME_UNCERTAIN",
                        "Не е ясно дали часът е отменен. Презаредете графика и проверете часа, преди да опитате отново."),
                new Result(new AppointmentAdministrationException.Failure(), 500, "INTERNAL_ERROR", GENERIC),
                // Results that cannot occur on this route are the generic internal error.
                new Result(new AppointmentAdministrationException.CustomerNotFound(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.ServiceUnavailable(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.StaffUnavailable(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.ReviewChanged(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.SlotUnavailable(), 500, "INTERNAL_ERROR", GENERIC));
    }

    private static List<Result> noteResults() {
        return List.of(
                new Result(new AppointmentAdministrationException.InvalidInput(), 400, "VALIDATION_ERROR",
                        "Проверете въведените данни."),
                new Result(new AppointmentAdministrationException.AccessDenied(), 403, "ACCESS_DENIED",
                        "Нямате достъп до тази операция."),
                new Result(new AppointmentAdministrationException.AppointmentNotFound(), 404,
                        "APPOINTMENT_NOT_FOUND", "Часът не е намерен."),
                new Result(new AppointmentAdministrationException.BusinessNotActive(), 409, "BUSINESS_NOT_ACTIVE",
                        "Бизнесът не е активен. Бележките могат да се променят само в активен бизнес."),
                new Result(new AppointmentAdministrationException.BusinessSuspended(), 409, "BUSINESS_SUSPENDED",
                        "Спрян бизнес може само да преглежда данните си."),
                new Result(new AppointmentAdministrationException.ConcurrentUpdate(), 409,
                        "APPOINTMENT_CONCURRENT_UPDATE",
                        "Часът е променен междувременно. Презаредете графика и опитайте отново."),
                new Result(new AppointmentAdministrationException.TemporarilyUnavailable(), 503,
                        "APPOINTMENT_TEMPORARILY_UNAVAILABLE",
                        "Бележката не беше променена. Опитайте отново след малко."),
                new Result(new AppointmentAdministrationException.OutcomeUncertain(), 503,
                        "APPOINTMENT_OUTCOME_UNCERTAIN",
                        "Не е ясно дали бележката е променена. Презаредете графика и проверете часа, преди да опитате отново."),
                new Result(new AppointmentAdministrationException.Failure(), 500, "INTERNAL_ERROR", GENERIC),
                // A note edit has no state conflict, and the create and reschedule results cannot occur.
                new Result(new AppointmentAdministrationException.StateConflict(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.CustomerNotFound(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.ServiceUnavailable(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.StaffUnavailable(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.ReviewChanged(), 500, "INTERNAL_ERROR", GENERIC),
                new Result(new AppointmentAdministrationException.SlotUnavailable(), 500, "INTERNAL_ERROR", GENERIC));
    }

    private void assertEveryResult(Route route, List<Result> results) throws Exception {
        for (Result result : results) {
            raise(route, result.failure());

            ResultActions response = send(route);

            response.andExpect(status().is(result.status()));
            JsonNode problem = json(response);
            String label = route + " " + result.failure().getClass().getSimpleName();
            assertThat(problem.get("code").asString()).as(label).isEqualTo(result.code());
            assertThat(problem.get("detail").asString()).as(label).isEqualTo(result.detail());
            assertThat(problem.get("title").asString()).as(label).isEqualTo("Заявката не може да бъде изпълнена.");
            assertThat(problem.get("status").asInt()).as(label).isEqualTo(result.status());
            assertThat(problem.get("instance").asString()).as(label).isEqualTo(PATH);
            assertThat(keys(problem)).as(label).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
            var raw = response.andReturn().getResponse();
            assertThat(raw.getContentType()).as(label).contains("application/problem+json");
            assertThat(raw.getHeader("Cache-Control")).as(label).contains("no-store");
            assertThat(raw.getHeader("Location")).as(label).isNull();
            assertThat(raw.getHeader("Set-Cookie")).as(label).isNull();
            String text = body(response);
            for (String forbidden : List.of(
                    CUSTOMER, NOTE, "Мария Петрова", tenant.customer().toString(), tenant.staff().toString(),
                    tenant.service().toString(), appointment.toString(), owner.businessId().toString(),
                    owner.userId().toString(), "2026-11-10", "SQL", "40001", "40P01", "25P02", "constraint",
                    "appointment_", "Exception", "org.", "java.", "bg.spotyourslot", "Caused", "stack", "cause")) {
                assertThat(text).as(label + " must not contain " + forbidden).doesNotContain(forbidden);
            }
        }
    }

    @Test
    void everyResultOfTheCancellationRouteHasItsExactStatusCodeAndWording() throws Exception {
        assertEveryResult(Route.CANCEL, cancelResults());
    }

    @Test
    void everyResultOfTheInternalNoteRouteHasItsExactStatusCodeAndWording() throws Exception {
        assertEveryResult(Route.NOTE, noteResults());
    }

    @Test
    void theWordingOfEachOperationNamesItsOwnOperationAndNeverAnotherOne() throws Exception {
        List<String> cancelOnly = List.of("създа", "запаз", "премест", "бележк");
        for (Result result : cancelResults()) {
            assertThat(result.detail().toLowerCase()).as("cancel " + result.code())
                    .doesNotContain(cancelOnly.toArray(String[]::new));
        }
        List<String> noteOnly = List.of("създа", "запаз", "премест", "отмен");
        for (Result result : noteResults()) {
            assertThat(result.detail().toLowerCase()).as("note " + result.code())
                    .doesNotContain(noteOnly.toArray(String[]::new));
        }
    }

    @Test
    void theTwoUnavailableOutcomesAreDistinctFromEachOtherAndFromTheStaleVersionOnBothRoutes() throws Exception {
        for (Route route : Route.values()) {
            raise(route, new AppointmentAdministrationException.TemporarilyUnavailable());
            String temporarily = body(send(route));
            raise(route, new AppointmentAdministrationException.OutcomeUncertain());
            String uncertain = body(send(route));
            raise(route, new AppointmentAdministrationException.ConcurrentUpdate());
            String stale = body(send(route));

            assertThat(List.of(temporarily, uncertain, stale)).doesNotHaveDuplicates();
        }
    }
}
