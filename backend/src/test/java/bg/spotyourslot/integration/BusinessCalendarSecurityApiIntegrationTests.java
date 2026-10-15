package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Authentication, authorization, lifecycle, tenant isolation, fixed problems, headers, verbs, and
 * privacy of the four private calendar routes. Missing and foreign identifiers must be
 * byte-identical within a resource type, and no response, problem, or log may carry an identifier
 * or a private value except the authorized fields of the owner detail.
 */
@ExtendWith(OutputCaptureExtension.class)
class BusinessCalendarSecurityApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String GUEST_NOTE = "СЕНТИНЕЛ-ГОСТ-БЕЛЕЖКА-4410";
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String DAY = "from=2026-11-10&to=2026-11-10";

    private Actor owner;
    private Tenant tenant;
    private UUID appointment;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", CUSTOMER);
        appointment = appointment(tenant, NOV_10, 30, row -> {
            row.put("internal_note", NOTE);
            row.put("customer_note", GUEST_NOTE);
        });
    }

    private List<ResultActions> everyRoute(Actor actor) throws Exception {
        return List.of(
                calendar(actor, DAY),
                agenda(actor, DAY),
                detail(actor, appointment),
                history(actor, tenant.customer(), null));
    }

    private static void assertProblem(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status));
        JsonNode body = json(result);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(result.andReturn().getResponse().getContentType()).contains("application/problem+json");
    }

    // ---- authentication and roles -------------------------------------------------------------

    @Test
    void anonymousRequestsAreUnauthenticatedOnEveryRoute() throws Exception {
        for (String path : List.of(
                "/api/business/calendar?" + DAY, "/api/business/appointments?" + DAY,
                "/api/business/appointments/" + appointment,
                "/api/business/customers/" + tenant.customer() + "/appointments")) {
            assertProblem(mvc.perform(get(path)), 401, "AUTH_REQUIRED");
        }
    }

    @Test
    void anAbsentBusinessSelectionIsRequiredOnEveryRoute() throws Exception {
        Actor noSelection = api.actor("ACTIVE", "BUSINESS_OWNER", true, false, false);
        for (ResultActions result : everyRoute(noSelection)) {
            assertProblem(result, 403, "ACTIVE_BUSINESS_REQUIRED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void managerAndStaffAreDeniedOnEveryRoute(String role) throws Exception {
        Actor denied = api.actor("ACTIVE", role, true, false, true);
        for (ResultActions result : everyRoute(denied)) {
            assertProblem(result, 403, "ACCESS_DENIED");
        }
    }

    @Test
    void inactiveMissingForeignAndPlatformAuthorityAloneAreRejectedBeforeAnyCalendarAccess() throws Exception {
        List<Actor> rejected = List.of(
                api.actor("ACTIVE", "BUSINESS_OWNER", false, false, true),
                api.actor("ACTIVE", null, false, false, true),
                api.actorWithForeignMembership(),
                api.actor("ACTIVE", null, false, true, true));
        for (Actor actor : rejected) {
            for (ResultActions result : everyRoute(actor)) {
                assertProblem(result, 403, "ACTIVE_BUSINESS_REQUIRED");
            }
        }
    }

    @Test
    void aPlatformAdministratorWhoIsAlsoAnOwnerIsAllowedAsAnOwner() throws Exception {
        Actor both = api.actor("ACTIVE", "BUSINESS_OWNER", true, true, true);

        calendar(both, DAY).andExpect(status().isOk());
        agenda(both, DAY).andExpect(status().isOk());
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE", "SUSPENDED"})
    void everyLifecycleStatusIsReadableAndSaysSo(String lifecycle) throws Exception {
        Actor actor = api.owner(lifecycle);
        Tenant data = tenant(actor.businessId(), "Служител", "Клиент");
        UUID id = appointment(data, NOV_10, 30);

        assertThat(json(calendar(actor, DAY).andExpect(status().isOk())).get("businessStatus").asString())
                .isEqualTo(lifecycle);
        assertThat(json(agenda(actor, DAY).andExpect(status().isOk())).get("businessStatus").asString())
                .isEqualTo(lifecycle);
        assertThat(json(detail(actor, id).andExpect(status().isOk())).get("businessStatus").asString())
                .isEqualTo(lifecycle);
        history(actor, data.customer(), null).andExpect(status().isOk());
    }

    // ---- tenant isolation -------------------------------------------------------------------------

    @Test
    void aForeignAppointmentAndAMissingOneAreByteIdenticalNotFoundResponses() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30);

        ResultActions missing = detail(owner, UUID.randomUUID());
        ResultActions foreignResult = detail(owner, foreignAppointment);

        assertProblem(missing, 404, "APPOINTMENT_NOT_FOUND");
        assertThat(body(foreignResult)).isEqualTo(body(missing));
        assertThat(json(missing).get("detail").asString()).isEqualTo("Часът не е намерен.");
        assertThat(body(missing)).doesNotContain(foreignAppointment.toString());
    }

    @Test
    void aForeignCustomerAndAMissingOneAreByteIdenticalNotFoundResponses() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        appointment(foreign, NOV_10, 30);

        ResultActions missing = history(owner, UUID.randomUUID(), null);
        ResultActions foreignResult = history(owner, foreign.customer(), null);

        assertProblem(missing, 404, "CUSTOMER_NOT_FOUND");
        assertThat(body(foreignResult)).isEqualTo(body(missing));
        assertThat(json(missing).get("detail").asString()).isEqualTo("Клиентът не е намерен.");
    }

    @Test
    void anotherBusinessesDataNeverAppearsAndAClientSuppliedBusinessIdIsIgnored() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30);
        String query = DAY + "&businessId=" + other.businessId();

        String calendar = body(calendar(owner, query).andExpect(status().isOk()));
        String agenda = body(agenda(owner, query).andExpect(status().isOk()));

        for (String text : List.of(calendar, agenda)) {
            assertThat(text).contains(appointment.toString())
                    .doesNotContain(foreignAppointment.toString(), "Чужд", other.businessId().toString());
        }
    }

    // ---- problems ----------------------------------------------------------------------------------

    @Test
    void everyProblemHasTheFixedInstanceOfItsRouteFamilyAndEchoesNothing() throws Exception {
        UUID missingId = UUID.randomUUID();
        record Case(ResultActions result, String instance) {
        }
        List<Case> cases = List.of(
                new Case(calendar(owner, "from=bad&to=bad&staffMemberId=" + missingId), "/api/business/calendar"),
                new Case(calendar(owner, DAY + "&staffMemberId=" + missingId), "/api/business/calendar"),
                new Case(agenda(owner, DAY + "&size=11"), "/api/business/appointments"),
                new Case(agenda(owner, DAY + "&staffMemberId=" + missingId), "/api/business/appointments"),
                new Case(detail(owner, missingId), "/api/business/appointments"),
                new Case(detail(owner, "not-a-uuid"), "/api/business/appointments"),
                new Case(history(owner, missingId, null), "/api/business/customers"),
                new Case(history(owner, "not-a-uuid", null), "/api/business/customers"),
                new Case(history(owner, tenant.customer(), "page=-1"), "/api/business/customers"));
        for (Case current : cases) {
            JsonNode problem = json(current.result());
            String raw = body(current.result());
            assertThat(problem.get("instance").asString()).isEqualTo(current.instance());
            assertThat(problem.has("type")).as("the default about:blank type is omitted").isFalse();
            assertThat(raw).doesNotContain(missingId.toString(), "not-a-uuid", "bad", tenant.customer().toString(),
                    owner.businessId().toString(), "SELECT", "constraint", "Exception");
        }
        // The authorization problems carry the same fixed instances.
        Actor manager = api.actor("ACTIVE", "MANAGER", true, false, true);
        assertThat(json(calendar(manager, DAY)).get("instance").asString()).isEqualTo("/api/business/calendar");
        assertThat(json(detail(manager, appointment)).get("instance").asString()).isEqualTo("/api/business/appointments");
        assertThat(json(history(manager, tenant.customer(), null)).get("instance").asString())
                .isEqualTo("/api/business/customers");
    }

    // ---- headers, CSRF, verbs ----------------------------------------------------------------------

    @Test
    void everyResponseIsNoStoreAndReadsNeedNoCsrfToken() throws Exception {
        for (ResultActions result : everyRoute(owner)) {
            result.andExpect(status().isOk());
            assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
        }
        for (ResultActions result : List.of(
                detail(owner, UUID.randomUUID()), calendar(owner, "from=x&to=y"),
                mvc.perform(get("/api/business/calendar?" + DAY)))) {
            assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
        }
    }

    @Test
    void onlyGetIsRoutedAndEveryOtherVerbIsRefusedWithoutChangingAnything() throws Exception {
        long before = jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
        String snapshot = jdbc.sql("SELECT to_jsonb(a)::text || xmin::text FROM appointment a WHERE id = :id")
                .param("id", appointment).query(String.class).single();
        for (String path : List.of(
                "/api/business/calendar", "/api/business/appointments", "/api/business/appointments/" + appointment,
                "/api/business/customers/" + tenant.customer() + "/appointments")) {
            // Without a CSRF token a mutating verb is refused by security (403).
            mvc.perform(post(path).cookie(owner.session())).andExpect(status().isForbidden());
            mvc.perform(put(path).cookie(owner.session())).andExpect(status().isForbidden());
            mvc.perform(delete(path).cookie(owner.session())).andExpect(status().isForbidden());
            // With one, no such route exists (405 or 404), and nothing is written.
            int post = mvc.perform(post(path).cookie(owner.session()).with(csrf())).andReturn().getResponse().getStatus();
            int put = mvc.perform(put(path).cookie(owner.session()).with(csrf())).andReturn().getResponse().getStatus();
            int delete = mvc.perform(delete(path).cookie(owner.session()).with(csrf())).andReturn().getResponse().getStatus();
            assertThat(List.of(post, put, delete)).allMatch(code -> code == 405 || code == 404);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT to_jsonb(a)::text || xmin::text FROM appointment a WHERE id = :id")
                .param("id", appointment).query(String.class).single()).isEqualTo(snapshot);
        assertThat(mvc.perform(post("/api/business/customers/" + tenant.customer() + "/appointments")
                .cookie(owner.session())).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theCustomerRoutesStillWorkNextToTheHistoryRoute() throws Exception {
        mvc.perform(get("/api/business/customers/{id}", tenant.customer()).cookie(owner.session()))
                .andExpect(status().isOk());
        history(owner, tenant.customer(), null).andExpect(status().isOk());
    }

    // ---- privacy -----------------------------------------------------------------------------------

    @Test
    void theInternalNoteTheActorTheReferenceAndTheContactAppearOnlyInTheOwnerDetail(CapturedOutput output)
            throws Exception {
        fixtures.cancel(appointment);
        String reference = jdbc.sql("SELECT public_reference FROM appointment WHERE id = :id")
                .param("id", appointment).query(String.class).single();
        String email = jdbc.sql("SELECT email FROM customer WHERE id = :id")
                .param("id", tenant.customer()).query(String.class).single();
        String phone = jdbc.sql("SELECT phone FROM customer WHERE id = :id")
                .param("id", tenant.customer()).query(String.class).single();

        String calendar = body(calendar(owner, DAY));
        String agenda = body(agenda(owner, DAY));
        String history = body(history(owner, tenant.customer(), null));
        String detail = body(detail(owner, appointment));

        for (String text : List.of(calendar, agenda, history)) {
            assertThat(text).doesNotContain(NOTE, GUEST_NOTE, "BUSINESS", "UNKNOWN", "cancel", reference,
                    email, phone, "booking_attempt", "fingerprint", "version");
        }
        assertThat(calendar + agenda).contains(CUSTOMER);
        assertThat(history).doesNotContain(CUSTOMER);
        assertThat(detail).contains(NOTE, GUEST_NOTE, reference, email, phone, "\"cancelledBy\":\"BUSINESS\"");
        assertThat(output.getAll()).doesNotContain(NOTE, GUEST_NOTE, CUSTOMER, reference, email, phone);
    }

    @Test
    void failuresAndLogsCarryNoPrivateValueOrIdentifier(CapturedOutput output) throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30, row -> row.put("internal_note", NOTE));

        List<String> bodies = List.of(
                body(detail(owner, foreignAppointment)),
                body(history(owner, foreign.customer(), null)),
                body(calendar(owner, DAY + "&staffMemberId=" + foreign.staff())),
                body(agenda(owner, "from=bad&to=bad")));

        for (String text : bodies) {
            assertThat(text).doesNotContain(NOTE, CUSTOMER, "Чужд", foreignAppointment.toString(),
                    foreign.customer().toString(), foreign.staff().toString(), other.businessId().toString());
        }
        assertThat(output.getAll()).doesNotContain(NOTE, CUSTOMER, "Чужд", foreignAppointment.toString(),
                foreign.customer().toString());
    }
}
