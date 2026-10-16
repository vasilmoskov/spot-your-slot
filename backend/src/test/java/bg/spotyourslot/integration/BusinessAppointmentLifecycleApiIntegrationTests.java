package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.AppointmentLifecycleAdministration;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/business/appointments/{id}/cancel} and
 * {@code PUT /api/business/appointments/{id}/internal-note}: the exact request shapes, the exact
 * success bodies and headers, CSRF, the role and lifecycle matrix, tenant isolation (byte-identical
 * foreign and missing Appointments), the distinct results with their Bulgarian messages, validation
 * layering, statement counts, and privacy of the note across every other payload. Every value is
 * fictional. The clock is 2026-10-02 11:00 in Sofia; the Appointment is Tuesday 2026-11-10 09:00+02:00.
 */
@ExtendWith(OutputCaptureExtension.class)
class BusinessAppointmentLifecycleApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String STAFF = "Мария Петрова";
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String NEW_NOTE = "СЕНТИНЕЛ-НОВА-БЕЛЕЖКА-1204";
    private static final String PATH = "/api/business/appointments";
    private static final String CONFLICT_DETAIL = "Часът е променен междувременно. Презаредете графика и опитайте отново.";

    @Autowired AppointmentLifecycleAdministration lifecycle;

    private Actor owner;
    private Tenant tenant;
    private UUID appointment;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), STAFF, CUSTOMER);
        availability.everyDay(owner.businessId(), tenant.staff(), "09:00", "12:00");
        appointment = appointment(tenant, NOV_10, 30, row -> row.put("internal_note", NOTE));
    }

    // ---- requests ------------------------------------------------------------------------------------

    private ResultActions cancel(Actor actor, Object id, String content, boolean withCsrf) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH + "/{id}/cancel", id)
                .contentType(MediaType.APPLICATION_JSON).content(content);
        if (actor != null) {
            request.cookie(actor.session());
        }
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions cancel(String content) throws Exception {
        return cancel(owner, appointment, content, true);
    }

    private ResultActions note(Actor actor, Object id, String content, boolean withCsrf) throws Exception {
        MockHttpServletRequestBuilder request = put(PATH + "/{id}/internal-note", id)
                .contentType(MediaType.APPLICATION_JSON).content(content);
        if (actor != null) {
            request.cookie(actor.session());
        }
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions note(String content) throws Exception {
        return note(owner, appointment, content, true);
    }

    private static String version(Object version) {
        return "{\"expectedVersion\":" + version + "}";
    }

    private static String noteBody(Object version, String note) {
        return "{\"expectedVersion\":" + version + ",\"internalNote\":\"" + note + "\"}";
    }

    private static void assertProblem(ResultActions result, int status, String code, String detail) throws Exception {
        result.andExpect(status().is(status));
        JsonNode body = json(result);
        assertThat(body.get("code")).as(body.toString()).isNotNull();
        assertThat(body.get("code").asString()).isEqualTo(code);
        if (detail != null) {
            assertThat(body.get("detail").asString()).isEqualTo(detail);
        }
        if (status != 401) {
            // The authentication entry point answers before MVC with the fixed body of the security chain.
            assertThat(body.get("instance").asString()).isEqualTo(PATH);
            assertThat(keys(body)).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
        }
        assertThat(result.andReturn().getResponse().getContentType()).contains("application/problem+json");
        assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    private Map<String, Object> row() {
        return jdbc.sql("SELECT a.*, a.xmin::text AS xmin_text FROM appointment a WHERE id = :id")
                .param("id", appointment).query().singleRow();
    }

    private static final List<String> APPOINTMENT_KEYS = List.of(
            "id", "reference", "status", "source", "start", "end", "timezone", "staffMemberId",
            "staffDisplayName", "customerId", "services", "durationMinutes", "price", "internalNote",
            "version", "createdAt", "updatedAt");

    // ---- cancellation: success ------------------------------------------------------------------------------

    @Test
    void aCancellationReturnsTheCompleteUpdatedAppointmentWithTheExactBodyHeadersAndNoActor() throws Exception {
        ResultActions result = cancel(version(0));

        result.andExpect(status().isOk());
        JsonNode updated = json(result);
        assertThat(keys(updated)).containsExactlyElementsOf(APPOINTMENT_KEYS);
        assertThat(keys(updated.get("services").get(0))).containsExactly("position", "name", "durationMinutes", "price");
        assertThat(updated.get("id").asString()).isEqualTo(appointment.toString());
        assertThat(updated.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(updated.get("source").asString()).isEqualTo("MANUAL");
        assertThat(updated.get("version").asLong()).isEqualTo(1L);
        assertThat(updated.get("start").asString()).isEqualTo("2026-11-10T09:00:00+02:00");
        assertThat(updated.get("end").asString()).isEqualTo("2026-11-10T09:30:00+02:00");
        assertThat(updated.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(updated.get("customerId").asString()).isEqualTo(tenant.customer().toString());
        assertThat(updated.get("staffMemberId").asString()).isEqualTo(tenant.staff().toString());
        assertThat(updated.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(updated.get("price").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(updated.get("internalNote").asString()).isEqualTo(NOTE);
        assertThat(updated.get("createdAt").asString()).isEqualTo("2026-10-01T11:00:00+03:00");
        assertThat(updated.get("updatedAt").asString()).isEqualTo("2026-10-02T11:00:00+03:00");
        assertThat(body(result)).doesNotContain(CUSTOMER).doesNotContain("customerNote").doesNotContain("phone")
                .doesNotContain("email").doesNotContain("cancelledBy").doesNotContain("cancelledAt")
                .doesNotContain("BUSINESS\"");
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();

        Map<String, Object> stored = row();
        assertThat(stored.get("status")).isEqualTo("CANCELLED");
        assertThat(stored.get("cancelled_by")).isEqualTo("BUSINESS");
        assertThat(stored.get("version")).isEqualTo(1L);
        // The authorized detail shows the same cancelled visit.
        JsonNode detail = json(detail(owner, appointment.toString()).andExpect(status().isOk()));
        assertThat(detail.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(detail.get("version").asLong()).isEqualTo(1L);
    }

    @Test
    void theRepeatedCancellationFailsDeterministicallyAndNeverSucceedsSilently() throws Exception {
        cancel(version(0)).andExpect(status().isOk());

        assertProblem(cancel(version(0)), 409, "APPOINTMENT_CONCURRENT_UPDATE", CONFLICT_DETAIL);
        assertProblem(cancel(version(1)), 409, "APPOINTMENT_STATE_CONFLICT",
                "Часът не може да бъде отменен, защото вече е отменен или е започнал.");

        assertThat(row().get("version")).isEqualTo(1L);
    }

    @Test
    void anAppointmentStartingAtTheClockInstantIsStartedAndOneStartingAMicrosecondLaterIsCancellable()
            throws Exception {
        // The clock also dates the session, so the Appointments are placed around the fixed clock instant.
        UUID startingNow = appointment(tenant, NOW, 30);
        Tenant other = withStaff(tenant, staff(owner.businessId(), "Друг служител", true));
        UUID startingLater = appointment(other, NOW.plusNanos(1_000), 30);
        Map<String, Object> before = jdbc.sql("SELECT a.*, a.xmin::text AS xmin_text FROM appointment a WHERE id = :id")
                .param("id", startingNow).query().singleRow();

        assertProblem(cancel(owner, startingNow, version(0), true), 409, "APPOINTMENT_STATE_CONFLICT",
                "Часът не може да бъде отменен, защото вече е отменен или е започнал.");
        assertThat(jdbc.sql("SELECT a.*, a.xmin::text AS xmin_text FROM appointment a WHERE id = :id")
                .param("id", startingNow).query().singleRow()).isEqualTo(before);

        cancel(owner, startingLater, version(0), true).andExpect(status().isOk());
    }

    // ---- cancellation: authorization, lifecycle, shape --------------------------------------------------------------

    @Test
    void aCancellationWithoutACsrfTokenIsRefusedAndAnonymousIsUnauthenticatedAndNothingChanges() throws Exception {
        Map<String, Object> before = row();

        cancel(owner, appointment, version(0), false).andExpect(status().isForbidden());
        assertProblem(cancel(null, appointment, version(0), true), 401, "AUTH_REQUIRED", null);

        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void managerAndStaffAreDeniedOnBothRoutes(String role) throws Exception {
        Actor denied = api.actor("ACTIVE", role, true, false, true);
        Tenant own = tenant(denied.businessId(), "Служител", "Клиент");
        UUID theirs = appointment(own, NOV_10, 30);
        Map<String, Object> before = row();

        assertProblem(cancel(denied, theirs, version(0), true), 403, "ACCESS_DENIED", "Нямате достъп до тази операция.");
        assertProblem(note(denied, theirs, noteBody(0, NEW_NOTE), true), 403, "ACCESS_DENIED",
                "Нямате достъп до тази операция.");

        assertThat(row()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT version FROM appointment WHERE id = :id").param("id", theirs)
                .query(Long.class).single()).isZero();
    }

    @Test
    void noSelectionInactiveForeignAndPlatformAuthorityAloneAreActiveBusinessRequiredOnBothRoutes() throws Exception {
        List<Actor> rejected = List.of(
                api.actor("ACTIVE", "BUSINESS_OWNER", true, false, false),
                api.actor("ACTIVE", "BUSINESS_OWNER", false, false, true),
                api.actor("ACTIVE", null, false, false, true),
                api.actorWithForeignMembership(),
                api.actor("ACTIVE", null, false, true, true));
        Map<String, Object> before = row();

        for (Actor actor : rejected) {
            assertProblem(cancel(actor, appointment, version(0), true), 403, "ACTIVE_BUSINESS_REQUIRED", null);
            assertProblem(note(actor, appointment, noteBody(0, NEW_NOTE), true), 403, "ACTIVE_BUSINESS_REQUIRED", null);
        }

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void aDraftAndASuspendedBusinessAreReadOnlyBeforeAnyDomainValidationOnBothRoutes() throws Exception {
        Map<String, Object> before = row();
        jdbc.sql("UPDATE business SET status = 'DRAFT' WHERE id = :id").param("id", owner.businessId()).update();
        assertProblem(cancel(version(0)), 409, "BUSINESS_NOT_ACTIVE",
                "Бизнесът не е активен. Часовете могат да се отменят само в активен бизнес.");
        assertProblem(cancel(version(-1)), 409, "BUSINESS_NOT_ACTIVE", null);
        assertProblem(note(noteBody(0, NEW_NOTE)), 409, "BUSINESS_NOT_ACTIVE",
                "Бизнесът не е активен. Бележките могат да се променят само в активен бизнес.");
        assertProblem(note(noteBody(-1, "x")), 409, "BUSINESS_NOT_ACTIVE", null);
        jdbc.sql("UPDATE business SET status = 'SUSPENDED' WHERE id = :id").param("id", owner.businessId()).update();
        assertProblem(cancel(version(0)), 409, "BUSINESS_SUSPENDED", "Спрян бизнес може само да преглежда данните си.");
        assertProblem(cancel(version(-1)), 409, "BUSINESS_SUSPENDED", null);
        assertProblem(note(noteBody(0, NEW_NOTE)), 409, "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
        assertProblem(note(noteBody(-1, "x")), 409, "BUSINESS_SUSPENDED", null);

        assertThat(row()).isEqualTo(before);
    }

    private static List<String> invalidCancelBodies(String secret) {
        return List.of(
                "",
                "not json",
                "[]",
                "null",
                "\"" + secret + "\"",
                "{}",
                "{\"expectedVersion\":null}",
                "{\"expectedVersion\":\"0\"}",
                "{\"expectedVersion\":1.5}",
                "{\"expectedVersion\":true}",
                "{\"expectedVersion\":[0]}",
                "{\"expectedVersion\":99999999999999999999}",
                "{\"expectedVersion\":0,\"reason\":\"" + secret + "\"}",
                "{\"expectedVersion\":0,\"status\":\"CANCELLED\"}",
                "{\"expectedVersion\":0,\"cancelledBy\":\"BUSINESS\"}",
                "{\"expectedVersion\":0,\"" + secret + "\":1}");
    }

    @Test
    void everyCancellationShapeViolationIsTheSameGenericValidationErrorThatEchoesNothing() throws Exception {
        String secret = "СЕНТИНЕЛ-СТОЙНОСТ-5531";
        Map<String, Object> before = row();
        String first = null;

        for (String content : invalidCancelBodies(secret)) {
            ResultActions result = cancel(content);
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            first = first == null ? body(result) : first;
            assertThat(body(result)).as(content).isEqualTo(first).doesNotContain(secret).doesNotContain(appointment.toString());
        }
        for (String id : List.of("not-a-uuid", "0-0-0-0-0", "{" + UUID.randomUUID() + "}", "1")) {
            assertProblem(cancel(owner, id, version(0), true), 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        }

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void aNegativeVersionIsTheSameValidationErrorAfterAuthorization() throws Exception {
        Map<String, Object> before = row();

        assertProblem(cancel(version(-1)), 400, "VALIDATION_ERROR", "Проверете въведените данни.");

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void anUnsupportedMediaTypeAndOtherVerbsAreRefusedOnTheCancelRoute() throws Exception {
        Map<String, Object> before = row();
        ResultActions text = mvc.perform(post(PATH + "/{id}/cancel", appointment).cookie(owner.session()).with(csrf())
                .contentType(MediaType.TEXT_PLAIN).content(version(0)));
        assertProblem(text, 415, "UNSUPPORTED_MEDIA_TYPE", "Заявката не може да бъде изпълнена.");
        assertThat(body(text)).doesNotContain(appointment.toString());

        for (MockHttpServletRequestBuilder request : List.of(
                put(PATH + "/{id}/cancel", appointment),
                patch(PATH + "/{id}/cancel", appointment),
                delete(PATH + "/{id}/cancel", appointment),
                get(PATH + "/{id}/cancel", appointment))) {
            mvc.perform(request.cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(version(0)))
                    .andExpect(status().isMethodNotAllowed());
        }
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void aMissingAndAForeignAppointmentAreByteIdenticalNotFoundResponsesOnBothRoutes() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30);
        Map<String, Object> mine = row();

        ResultActions missing = cancel(owner, UUID.randomUUID(), version(0), true);
        ResultActions foreignResult = cancel(owner, foreignAppointment, version(0), true);
        ResultActions missingNote = note(owner, UUID.randomUUID(), noteBody(0, NEW_NOTE), true);
        ResultActions foreignNote = note(owner, foreignAppointment, noteBody(0, NEW_NOTE), true);

        assertProblem(missing, 404, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
        assertThat(body(foreignResult)).isEqualTo(body(missing));
        assertProblem(missingNote, 404, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
        assertThat(body(foreignNote)).isEqualTo(body(missingNote));
        assertThat(body(missing)).doesNotContain(foreignAppointment.toString());
        assertThat(row()).isEqualTo(mine);
        assertThat(jdbc.sql("SELECT version FROM appointment WHERE id = :id").param("id", foreignAppointment)
                .query(Long.class).single()).isZero();
    }

    // ---- internal note: success ------------------------------------------------------------------------------------

    @Test
    void aNoteEditReturnsTheCompleteUpdatedAppointmentWithTheExactBodyAndHeaders() throws Exception {
        ResultActions result = note(noteBody(0, NEW_NOTE));

        result.andExpect(status().isOk());
        JsonNode updated = json(result);
        assertThat(keys(updated)).containsExactlyElementsOf(APPOINTMENT_KEYS);
        assertThat(updated.get("internalNote").asString()).isEqualTo(NEW_NOTE);
        assertThat(updated.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(updated.get("version").asLong()).isEqualTo(1L);
        assertThat(updated.get("start").asString()).isEqualTo("2026-11-10T09:00:00+02:00");
        assertThat(updated.get("updatedAt").asString()).isEqualTo("2026-10-02T11:00:00+03:00");
        assertThat(body(result)).doesNotContain(CUSTOMER).doesNotContain("phone").doesNotContain("email")
                .doesNotContain("cancelledBy");
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(row().get("internal_note")).isEqualTo(NEW_NOTE);
    }

    @Test
    void aBlankNoteClearsItAndTheResponseCarriesAnExplicitNull() throws Exception {
        ResultActions result = note(noteBody(0, "   \\n  "));

        result.andExpect(status().isOk());
        JsonNode updated = json(result);
        assertThat(updated.has("internalNote")).isTrue();
        assertThat(updated.get("internalNote").isNull()).isTrue();
        assertThat(updated.get("version").asLong()).isEqualTo(1L);
        assertThat(row().get("internal_note")).isNull();
    }

    @Test
    void anUnchangedNoteIsASuccessThatAdvancesNothingAndStaleStillWinsOverIt() throws Exception {
        Map<String, Object> before = row();

        ResultActions result = note(noteBody(0, "  " + NOTE + "  "));

        result.andExpect(status().isOk());
        assertThat(json(result).get("version").asLong()).isZero();
        assertThat(json(result).get("internalNote").asString()).isEqualTo(NOTE);
        assertThat(row()).isEqualTo(before);

        note(noteBody(0, NEW_NOTE)).andExpect(status().isOk());
        assertProblem(note(noteBody(0, NEW_NOTE)), 409, "APPOINTMENT_CONCURRENT_UPDATE", CONFLICT_DETAIL);
    }

    @Test
    void aCancelledAndAStartedAppointmentStillAcceptANote() throws Exception {
        cancel(version(0)).andExpect(status().isOk());
        ResultActions edited = note(noteBody(1, NEW_NOTE));
        edited.andExpect(status().isOk());
        assertThat(json(edited).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(json(edited).get("version").asLong()).isEqualTo(2L);

        UUID past = appointment(withStaff(tenant, staff(owner.businessId(), "Друг служител", true)),
                NOW.minusSeconds(86_400), 30);
        ResultActions pastNote = note(owner, past, noteBody(0, "Минала бележка"), true);
        pastNote.andExpect(status().isOk());
        assertThat(json(pastNote).get("internalNote").asString()).isEqualTo("Минала бележка");
    }

    @Test
    void theStaleVersionAndTheOverlongNoteAreDistinctAndNothingIsTruncated() throws Exception {
        Map<String, Object> before = row();

        assertProblem(note(noteBody(5, NEW_NOTE)), 409, "APPOINTMENT_CONCURRENT_UPDATE", CONFLICT_DETAIL);
        assertProblem(note(noteBody(0, "а".repeat(501))), 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        // A stale form is told it is stale, not that its note is invalid.
        assertProblem(note(noteBody(5, "а".repeat(501))), 409, "APPOINTMENT_CONCURRENT_UPDATE", CONFLICT_DETAIL);
        assertThat(row()).isEqualTo(before);

        note(noteBody(0, "а".repeat(500))).andExpect(status().isOk());
        assertThat(((String) row().get("internal_note")).codePointCount(0, 500)).isEqualTo(500);
    }

    @Test
    void everyNoteShapeViolationIsTheSameGenericValidationErrorThatEchoesNothing() throws Exception {
        String secret = "СЕНТИНЕЛ-СТОЙНОСТ-5531";
        List<String> invalid = new ArrayList<>(List.of(
                "",
                "not json",
                "[]",
                "null",
                "{}",
                "{\"expectedVersion\":0}",
                "{\"internalNote\":\"" + secret + "\"}",
                "{\"expectedVersion\":0,\"internalNote\":null}",
                "{\"expectedVersion\":0,\"internalNote\":7}",
                "{\"expectedVersion\":0,\"internalNote\":[\"" + secret + "\"]}",
                "{\"expectedVersion\":0,\"internalNote\":{\"" + secret + "\":1}}",
                "{\"expectedVersion\":null,\"internalNote\":\"" + secret + "\"}",
                "{\"expectedVersion\":\"0\",\"internalNote\":\"" + secret + "\"}",
                "{\"expectedVersion\":0,\"internalNote\":\"" + secret + "\",\"status\":\"CANCELLED\"}",
                "{\"expectedVersion\":0,\"internalNote\":\"" + secret + "\",\"customerNote\":\"x\"}",
                "{\"expectedVersion\":0,\"internalNote\":\"" + secret + "\",\"" + secret + "\":1}"));
        Map<String, Object> before = row();
        String first = null;

        for (String content : invalid) {
            ResultActions result = note(content);
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            first = first == null ? body(result) : first;
            assertThat(body(result)).as(content).isEqualTo(first).doesNotContain(secret).doesNotContain(appointment.toString());
        }
        for (String id : List.of("not-a-uuid", "0-0-0-0-0", "{" + UUID.randomUUID() + "}")) {
            assertProblem(note(owner, id, noteBody(0, NEW_NOTE), true), 400, "VALIDATION_ERROR",
                    "Проверете въведените данни.");
        }
        // A control character in an otherwise well-shaped request is a domain violation, same body.
        assertProblem(note(noteBody(0, "a\\u0007b")), 400, "VALIDATION_ERROR", "Проверете въведените данни.");

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void aNoteEditWithoutCsrfAnonymousAndOtherVerbsAreRefusedAndChangeNothing() throws Exception {
        Map<String, Object> before = row();

        note(owner, appointment, noteBody(0, NEW_NOTE), false).andExpect(status().isForbidden());
        assertProblem(note(null, appointment, noteBody(0, NEW_NOTE), true), 401, "AUTH_REQUIRED", null);
        ResultActions text = mvc.perform(put(PATH + "/{id}/internal-note", appointment).cookie(owner.session())
                .with(csrf()).contentType(MediaType.TEXT_PLAIN).content(noteBody(0, NEW_NOTE)));
        assertProblem(text, 415, "UNSUPPORTED_MEDIA_TYPE", "Заявката не може да бъде изпълнена.");
        for (MockHttpServletRequestBuilder request : List.of(
                post(PATH + "/{id}/internal-note", appointment),
                patch(PATH + "/{id}/internal-note", appointment),
                delete(PATH + "/{id}/internal-note", appointment),
                get(PATH + "/{id}/internal-note", appointment))) {
            mvc.perform(request.cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(noteBody(0, NEW_NOTE)))
                    .andExpect(status().isMethodNotAllowed());
        }

        assertThat(row()).isEqualTo(before);
    }

    // ---- privacy -------------------------------------------------------------------------------------------------------------

    @Test
    void theNoteAppearsOnlyInTheAuthorizedDetailAndTheTwoMutationResponsesNeverInTheListsOrTheHistory()
            throws Exception {
        note(noteBody(0, NEW_NOTE)).andExpect(status().isOk());

        assertThat(body(detail(owner, appointment.toString()))).contains(NEW_NOTE);
        assertThat(body(calendar(owner, "from=2026-11-09&to=2026-11-11"))).doesNotContain(NEW_NOTE).doesNotContain(NOTE);
        assertThat(body(agenda(owner, "from=2026-11-09&to=2026-11-11"))).doesNotContain(NEW_NOTE).doesNotContain(NOTE);
        assertThat(body(history(owner, tenant.customer(), null))).doesNotContain(NEW_NOTE).doesNotContain(NOTE);
        cancel(version(1)).andExpect(status().isOk());
        assertThat(body(calendar(owner, "from=2026-11-09&to=2026-11-11"))).doesNotContain(NEW_NOTE);
        assertThat(body(agenda(owner, "from=2026-11-09&to=2026-11-11"))).doesNotContain(NEW_NOTE);
    }

    @Test
    void neitherTheNoteNorAnyPrivateValueIsLoggedOrEchoedInAnyProblemOfEitherRoute(CapturedOutput output)
            throws Exception {
        cancel(version(0)).andExpect(status().isOk());
        List<ResultActions> problems = List.of(
                cancel(version(0)),
                cancel(version(1)),
                cancel(owner, UUID.randomUUID(), version(0), true),
                cancel("{\"expectedVersion\":0,\"" + NOTE + "\":1}"),
                note(noteBody(0, NEW_NOTE)),
                note(noteBody(5, NOTE)),
                note(owner, UUID.randomUUID(), noteBody(0, NOTE), true),
                note("{\"expectedVersion\":0,\"internalNote\":null,\"" + NOTE + "\":1}"));

        for (ResultActions problem : problems) {
            assertThat(body(problem)).doesNotContain(NOTE).doesNotContain(NEW_NOTE).doesNotContain(CUSTOMER)
                    .doesNotContain(STAFF).doesNotContain(appointment.toString())
                    .doesNotContain(tenant.customer().toString());
        }
        assertThat(output.getAll()).doesNotContain(NOTE).doesNotContain(NEW_NOTE).doesNotContain(CUSTOMER);
    }

    // ---- statement counts --------------------------------------------------------------------------------------------------------

    private AuthenticatedBusinessContext context() {
        return new Context(owner.userId(), owner.businessId());
    }

    private record Context(UUID userId, UUID businessId) implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(businessId);
        }
    }

    private int statementsOfCancel(UUID id, long version) {
        CalendarSqlRecording.clear();
        lifecycle.cancel(context(), new CancelAppointmentCommand(id, version));
        return CalendarSqlRecording.statementsOfCurrentThread().size();
    }

    private int statementsOfNote(UUID id, long version, String note) {
        CalendarSqlRecording.clear();
        lifecycle.editInternalNote(context(), new EditInternalNoteCommand(id, version, note));
        return CalendarSqlRecording.statementsOfCurrentThread().size();
    }

    @Test
    void theNumberOfStatementsOfACancellationAndOfANoteEditDoesNotDependOnTheNumberOfServices() {
        UUID five = visit(tenant, Instant.parse("2026-11-11T07:00:00Z"), "A", "B", "C", "D", "E");
        UUID one = visit(tenant, Instant.parse("2026-11-12T07:00:00Z"), "A");

        int singleCancel = statementsOfCancel(one, 0L);
        int severalCancel = statementsOfCancel(five, 0L);
        int singleNote = statementsOfNote(one, 1L, "Бележка");
        int severalNote = statementsOfNote(five, 1L, "Бележка");
        int singleNoOp = statementsOfNote(one, 2L, "Бележка");
        int severalNoOp = statementsOfNote(five, 2L, "Бележка");

        assertThat(severalCancel).isEqualTo(singleCancel);
        assertThat(severalNote).isEqualTo(singleNote);
        assertThat(severalNoOp).isEqualTo(singleNoOp);
        // Business lock, Membership lock, the Appointment row and its lines, the conditional update with its
        // lines, the read-your-write row and lines, and the verification row and lines after the commit.
        assertThat(singleCancel).isEqualTo(10);
        assertThat(singleNote).isEqualTo(10);
        // No update and no read-your-write: the two locks, the row and its lines, the verification row and lines.
        assertThat(singleNoOp).isEqualTo(6);
        for (String sql : CalendarSqlRecording.statementsOfCurrentThread()) {
            assertThat(sql).doesNotContain(CUSTOMER).doesNotContain(NOTE).doesNotContain("Бележка");
        }
    }

    @Test
    void theOnlyWritesOfACancellationAreOneConditionalUpdateOfTheAppointment() {
        statementsOfCancel(appointment, 0L);

        List<String> writes = CalendarSqlRecording.statementsOfCurrentThread().stream()
                .map(CalendarSqlRecording::normalized)
                .filter(sql -> sql.startsWith("update") || sql.startsWith("insert") || sql.startsWith("delete"))
                .toList();
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0)).startsWith("update appointment set status = 'cancelled'")
                .contains("and version = :expectedversion".replace(":expectedversion", "?"));
    }

    @Test
    void anUnchangedNoteIssuesNoWriteAtAll() {
        statementsOfNote(appointment, 0L, NOTE);

        assertThat(CalendarSqlRecording.statementsOfCurrentThread().stream()
                .map(CalendarSqlRecording::normalized)
                .filter(sql -> sql.startsWith("update") || sql.startsWith("insert") || sql.startsWith("delete")))
                .isEmpty();
    }
}
