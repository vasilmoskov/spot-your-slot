package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentAdministration;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * {@code POST /api/business/appointments/{appointmentId}/reschedule}: the exact request shape, the
 * exact success body and headers, CSRF, the role and lifecycle matrix, tenant isolation (byte-identical
 * foreign and missing Appointments and StaffMembers), the five distinct conflict and state results with
 * their Bulgarian messages, the validation layering, and privacy. Every value is fictional. The clock
 * is 2026-10-02 11:00 in Sofia; the Appointment is Tuesday 2026-11-10 09:00+02:00 (30 minutes), the
 * StaffMember works 09:00 to 12:00 every day.
 */
@ExtendWith(OutputCaptureExtension.class)
class BusinessAppointmentRescheduleApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String STAFF = "Мария Петрова";
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String PATH = "/api/business/appointments";
    private static final String START = "2026-11-10T09:00:00+02:00";
    private static final String LATER = "2026-11-10T10:30:00+02:00";

    @Autowired AppointmentAdministration administration;

    private Actor owner;
    private Tenant tenant;
    private UUID appointment;
    private UUID second;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), STAFF, CUSTOMER);
        availability.everyDay(owner.businessId(), tenant.staff(), "09:00", "12:00");
        second = staff(owner.businessId(), "Втори Служител", true);
        availability.assign(owner.businessId(), second, tenant.service());
        availability.everyDay(owner.businessId(), second, "09:00", "12:00");
        appointment = appointment(tenant, NOV_10, 30, row -> row.put("internal_note", NOTE));
    }

    // ---- requests --------------------------------------------------------------------------------

    private static String move(Object version, String start, UUID staff) {
        StringBuilder body = new StringBuilder("{\"expectedVersion\":").append(version);
        if (start != null) {
            body.append(",\"start\":\"").append(start).append("\"");
        }
        if (staff != null) {
            body.append(",\"staffMemberId\":\"").append(staff).append("\"");
        }
        return body.append("}").toString();
    }

    private ResultActions send(Actor actor, Object id, String content, boolean withCsrf) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH + "/{id}/reschedule", id)
                .contentType(MediaType.APPLICATION_JSON).content(content);
        if (actor != null) {
            request.cookie(actor.session());
        }
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions send(String content) throws Exception {
        return send(owner, appointment, content, true);
    }

    private static void assertProblem(ResultActions result, int status, String code, String detail) throws Exception {
        result.andExpect(status().is(status));
        JsonNode body = json(result);
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

    private Instant storedStart() {
        return jdbc.sql("SELECT start_at FROM appointment WHERE id = :id").param("id", appointment)
                .query(OffsetDateTime.class).single().toInstant();
    }

    // ---- success -----------------------------------------------------------------------------------------

    @Test
    void aTimeOnlyMoveReturnsTheCompleteUpdatedAppointmentWithTheExactBodyAndHeaders() throws Exception {
        ResultActions result = send(move(0, LATER, null));

        result.andExpect(status().isOk());
        JsonNode updated = json(result);
        assertThat(keys(updated)).containsExactly(
                "id", "reference", "status", "source", "start", "end", "timezone", "staffMemberId",
                "staffDisplayName", "customerId", "services", "durationMinutes", "price", "internalNote",
                "version", "createdAt", "updatedAt");
        assertThat(keys(updated.get("services").get(0))).containsExactly("position", "name", "durationMinutes", "price");
        assertThat(updated.get("id").asString()).isEqualTo(appointment.toString());
        assertThat(updated.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(updated.get("source").asString()).isEqualTo("MANUAL");
        assertThat(updated.get("version").asLong()).isEqualTo(1L);
        assertThat(updated.get("start").asString()).isEqualTo(LATER);
        assertThat(updated.get("end").asString()).isEqualTo("2026-11-10T11:00:00+02:00");
        assertThat(updated.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(updated.get("customerId").asString()).isEqualTo(tenant.customer().toString());
        assertThat(updated.get("staffMemberId").asString()).isEqualTo(tenant.staff().toString());
        assertThat(updated.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(updated.get("price").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(updated.get("internalNote").asString()).isEqualTo(NOTE);
        assertThat(updated.get("services").get(0).get("position").asInt()).isEqualTo(1);
        assertThat(updated.get("createdAt").asString()).isEqualTo("2026-10-01T11:00:00+03:00");
        assertThat(updated.get("updatedAt").asString()).isEqualTo("2026-10-02T11:00:00+03:00");
        assertThat(body(result)).doesNotContain(CUSTOMER).doesNotContain("customerNote").doesNotContain("phone")
                .doesNotContain("email");
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();

        // The same visit through the owner-only detail route.
        JsonNode detail = json(detail(owner, appointment.toString()).andExpect(status().isOk()));
        assertThat(detail.get("start").asString()).isEqualTo(LATER);
        assertThat(detail.get("version").asLong()).isEqualTo(1L);
        assertThat(detail.get("internalNote").asString()).isEqualTo(NOTE);
    }

    @Test
    void aStaffMemberOnlyChangeKeepsTheTimeAndRefreshesTheName() throws Exception {
        ResultActions result = send(move(0, null, second));

        result.andExpect(status().isOk());
        JsonNode updated = json(result);
        assertThat(updated.get("start").asString()).isEqualTo(START);
        assertThat(updated.get("staffMemberId").asString()).isEqualTo(second.toString());
        assertThat(updated.get("staffDisplayName").asString()).isEqualTo("Втори Служител");
        assertThat(updated.get("version").asLong()).isEqualTo(1L);
    }

    @Test
    void aCombinedChangeIsOneRequestAndOneVersionStep() throws Exception {
        ResultActions result = send(move(0, LATER, second));

        result.andExpect(status().isOk());
        JsonNode updated = json(result);
        assertThat(updated.get("start").asString()).isEqualTo(LATER);
        assertThat(updated.get("staffMemberId").asString()).isEqualTo(second.toString());
        assertThat(updated.get("version").asLong()).isEqualTo(1L);
    }

    @Test
    void theStartMayBeGivenInAnyOffsetAndIsStoredAsTheSameInstant() throws Exception {
        ResultActions result = send(move(0, "2026-11-10T08:30:00Z", null));

        result.andExpect(status().isOk());
        assertThat(json(result).get("start").asString()).isEqualTo(LATER);
        assertThat(storedStart()).isEqualTo(Instant.parse("2026-11-10T08:30:00Z"));
    }

    @Test
    void theRepeatedRequestFailsDeterministicallyInsteadOfRepeatingItsEffect() throws Exception {
        send(move(0, LATER, null)).andExpect(status().isOk());

        assertProblem(send(move(0, LATER, null)), 409, "APPOINTMENT_CONCURRENT_UPDATE",
                "Часът е променен междувременно. Презаредете графика и опитайте отново.");

        assertThat(row().get("version")).isEqualTo(1L);
    }

    // ---- CSRF and authorization --------------------------------------------------------------------------------

    @Test
    void aRequestWithoutACsrfTokenIsRefusedAndChangesNothing() throws Exception {
        Map<String, Object> before = row();

        send(owner, appointment, move(0, LATER, null), false).andExpect(status().isForbidden());

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void anonymousIsUnauthenticated() throws Exception {
        Map<String, Object> before = row();

        assertProblem(send(null, appointment, move(0, LATER, null), true), 401, "AUTH_REQUIRED", null);

        assertThat(row()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void managerAndStaffAreDeniedAndChangeNothing(String role) throws Exception {
        Actor denied = api.actor("ACTIVE", role, true, false, true);
        Tenant own = tenant(denied.businessId(), "Служител", "Клиент");
        availability.everyDay(denied.businessId(), own.staff(), "09:00", "12:00");
        UUID theirs = appointment(own, NOV_10, 30);
        Map<String, Object> before = row();

        assertProblem(send(denied, theirs, move(0, LATER, null), true),
                403, "ACCESS_DENIED", "Нямате достъп до тази операция.");

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void noSelectionInactiveForeignAndPlatformAuthorityAloneAreActiveBusinessRequired() throws Exception {
        List<Actor> rejected = List.of(
                api.actor("ACTIVE", "BUSINESS_OWNER", true, false, false),
                api.actor("ACTIVE", "BUSINESS_OWNER", false, false, true),
                api.actor("ACTIVE", null, false, false, true),
                api.actorWithForeignMembership(),
                api.actor("ACTIVE", null, false, true, true));
        Map<String, Object> before = row();

        for (Actor actor : rejected) {
            assertProblem(send(actor, appointment, move(0, LATER, null), true),
                    403, "ACTIVE_BUSINESS_REQUIRED", null);
        }

        assertThat(row()).isEqualTo(before);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    @Test
    void aDraftBusinessIsBusinessNotActiveAndASuspendedOneIsBusinessSuspendedBeforeAnyDomainValidation() throws Exception {
        String invalidDomain = "{\"expectedVersion\":-1}";
        Map<String, Object> before = row();
        jdbc.sql("UPDATE business SET status = 'DRAFT' WHERE id = :id").param("id", owner.businessId()).update();
        assertProblem(send(move(0, LATER, null)), 409, "BUSINESS_NOT_ACTIVE",
                "Бизнесът не е активен. Часовете могат да се променят само в активен бизнес.");
        assertProblem(send(invalidDomain), 409, "BUSINESS_NOT_ACTIVE", null);
        jdbc.sql("UPDATE business SET status = 'SUSPENDED' WHERE id = :id").param("id", owner.businessId()).update();
        assertProblem(send(move(0, LATER, null)), 409, "BUSINESS_SUSPENDED",
                "Спрян бизнес може само да преглежда данните си.");
        assertProblem(send(invalidDomain), 409, "BUSINESS_SUSPENDED", null);

        assertThat(row()).isEqualTo(before);
    }

    // ---- validation layering ------------------------------------------------------------------------------------------

    @Test
    void everyShapeViolationIsTheSameGenericValidationErrorThatEchoesNothing() throws Exception {
        String secret = "СЕНТИНЕЛ-СТОЙНОСТ-5531";
        List<String> invalid = List.of(
                "",
                "not json",
                "[]",
                "null",
                "\"" + secret + "\"",
                "{\"expectedVersion\":0,\"start\":\"" + LATER + "\",\"customerId\":\"" + tenant.customer() + "\"}",
                "{\"expectedVersion\":0,\"start\":\"" + LATER + "\",\"services\":[]}",
                "{\"expectedVersion\":0,\"start\":\"" + LATER + "\",\"durationMinutes\":60}",
                "{\"expectedVersion\":0,\"start\":\"" + LATER + "\",\"price\":1}",
                "{\"expectedVersion\":0,\"start\":\"" + LATER + "\",\"businessId\":\"" + owner.businessId() + "\"}",
                "{\"expectedVersion\":0,\"start\":\"" + LATER + "\",\"" + secret + "\":1}",
                "{\"start\":\"" + LATER + "\"}",
                "{\"expectedVersion\":null,\"start\":\"" + LATER + "\"}",
                "{\"expectedVersion\":\"0\",\"start\":\"" + LATER + "\"}",
                "{\"expectedVersion\":0.5,\"start\":\"" + LATER + "\"}",
                "{\"expectedVersion\":99999999999999999999,\"start\":\"" + LATER + "\"}",
                "{\"expectedVersion\":0,\"start\":\"2026-11-10T10:30:00\"}",
                "{\"expectedVersion\":0,\"start\":\"2026-11-10\"}",
                "{\"expectedVersion\":0,\"start\":\"10 Nov 2026 10:30 +02:00\"}",
                "{\"expectedVersion\":0,\"start\":null}",
                "{\"expectedVersion\":0,\"start\":5}",
                "{\"expectedVersion\":0,\"staffMemberId\":null}",
                "{\"expectedVersion\":0,\"staffMemberId\":5}",
                "{\"expectedVersion\":0,\"staffMemberId\":\"1\"}",
                "{\"expectedVersion\":0,\"staffMemberId\":\"+" + second.toString().substring(1) + "\"}");
        Map<String, Object> before = row();
        String first = null;
        for (String content : invalid) {
            ResultActions result = send(content);
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            String text = body(result);
            assertThat(text).doesNotContain(secret).doesNotContain(owner.businessId().toString())
                    .doesNotContain(tenant.customer().toString());
            first = first == null ? text : first;
            assertThat(text).isEqualTo(first);
        }
        for (String id : List.of("1", "+" + appointment.toString().substring(1), "not-a-uuid", "00000000-0000-0000-0000-00000000000G")) {
            ResultActions result = send(owner, id, move(0, LATER, null), true);
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(body(result)).isEqualTo(first);
        }
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void everyDomainViolationIsTheSameValidationErrorAfterAuthorization() throws Exception {
        String shapeBody = body(send("{}"));
        Map<String, Object> before = row();
        List<String> invalid = List.of(
                "{\"expectedVersion\":-1,\"start\":\"" + LATER + "\"}",
                "{\"expectedVersion\":0}",
                move(0, START, null),
                move(0, null, tenant.staff()),
                move(0, START, tenant.staff()),
                move(0, "1999-12-31T00:00:00+00:00", null),
                move(0, "2101-01-01T12:00:00+00:00", null));

        for (String content : invalid) {
            ResultActions result = send(content);
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(body(result)).isEqualTo(shapeBody);
        }

        assertThat(row()).isEqualTo(before);
    }

    @Test
    void anUnsupportedMediaTypeAndOtherVerbsAreRefusedWithoutEchoingOrChanging() throws Exception {
        Map<String, Object> before = row();
        ResultActions text = mvc.perform(post(PATH + "/{id}/reschedule", appointment).cookie(owner.session()).with(csrf())
                .contentType(MediaType.TEXT_PLAIN).content(move(0, LATER, null)));
        assertProblem(text, 415, "UNSUPPORTED_MEDIA_TYPE", "Заявката не може да бъде изпълнена.");
        assertThat(body(text)).doesNotContain(appointment.toString());

        for (MockHttpServletRequestBuilder request : List.of(
                put(PATH + "/{id}/reschedule", appointment),
                patch(PATH + "/{id}/reschedule", appointment),
                delete(PATH + "/{id}/reschedule", appointment),
                get(PATH + "/{id}/reschedule", appointment))) {
            mvc.perform(request.cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(move(0, LATER, null)))
                    .andExpect(status().isMethodNotAllowed());
        }
        assertThat(row()).isEqualTo(before);
    }

    // ---- tenant isolation and the distinct results ----------------------------------------------------------------------

    @Test
    void aMissingAndAForeignAppointmentAreByteIdenticalNotFoundResponses() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        availability.everyDay(other.businessId(), foreign.staff(), "09:00", "12:00");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30);
        Map<String, Object> mine = row();

        ResultActions missing = send(owner, UUID.randomUUID(), move(0, LATER, null), true);
        ResultActions foreignResult = send(owner, foreignAppointment, move(0, LATER, null), true);
        ResultActions foreignWithMyStaff = send(owner, foreignAppointment, move(0, LATER, tenant.staff()), true);

        assertProblem(missing, 404, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
        assertThat(body(foreignResult)).isEqualTo(body(missing));
        assertThat(body(foreignWithMyStaff)).isEqualTo(body(missing));
        assertThat(body(missing)).doesNotContain(foreignAppointment.toString());
        assertThat(row()).isEqualTo(mine);
        assertThat(jdbc.sql("SELECT version FROM appointment WHERE id = :id").param("id", foreignAppointment)
                .query(Long.class).single()).isZero();
    }

    @Test
    void aForeignAMissingAnInactiveAndAnUnassignedStaffMemberAreByteIdenticalConflicts() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        availability.assign(owner.businessId(), inactive, tenant.service());
        UUID unassigned = staff(owner.businessId(), "Неназначен", true);
        Map<String, Object> before = row();

        String first = null;
        for (UUID staff : List.of(UUID.randomUUID(), foreign.staff(), inactive, unassigned)) {
            ResultActions result = send(move(0, null, staff));
            assertProblem(result, 409, "APPOINTMENT_STAFF_UNAVAILABLE",
                    "Членът на екипа не е наличен за тази промяна.");
            first = first == null ? body(result) : first;
            assertThat(body(result)).isEqualTo(first);
            assertThat(body(result)).doesNotContain(foreign.staff().toString()).doesNotContain(inactive.toString());
        }
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void theFiveResultsAreFiveDistinctProblemsWithDistinctCodesAndMessages() throws Exception {
        UUID cancelled = appointment(tenant, NOV_10.plusSeconds(3600), 30);
        assertThat(store.cancel(new CancelUpdate(owner.businessId(), cancelled, 0L, NOW))).isPresent();
        jdbc.sql("UPDATE staff_member SET active = false WHERE id = :id").param("id", second).update();
        Map<String, Object> before = row();

        ResultActions stale = send(move(5, LATER, null));
        ResultActions state = send(owner, cancelled, move(1, LATER, null), true);
        ResultActions slot = send(move(0, "2026-11-10T13:00:00+02:00", null));
        ResultActions staff = send(move(0, null, second));
        ResultActions missing = send(owner, UUID.randomUUID(), move(0, LATER, null), true);

        assertProblem(stale, 409, "APPOINTMENT_CONCURRENT_UPDATE",
                "Часът е променен междувременно. Презаредете графика и опитайте отново.");
        assertProblem(state, 409, "APPOINTMENT_STATE_CONFLICT",
                "Часът не може да бъде променен, защото е отменен или вече е започнал.");
        assertProblem(slot, 409, "APPOINTMENT_SLOT_UNAVAILABLE", "Избраният час вече не е свободен.");
        assertProblem(staff, 409, "APPOINTMENT_STAFF_UNAVAILABLE",
                "Членът на екипа не е наличен за тази промяна.");
        assertProblem(missing, 404, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
        assertThat(List.of(body(stale), body(state), body(slot), body(staff), body(missing))).doesNotHaveDuplicates();
        assertThat(row()).isEqualTo(before);
    }

    @Test
    void aStartedAppointmentIsAStateConflictAndATakenOrUnofferedStartIsASlotConflict() throws Exception {
        UUID neighbour = appointment(tenant, Instant.parse("2026-11-10T08:30:00Z"), 30);
        Map<String, Object> before = row();

        assertProblem(send(move(0, "2026-11-10T10:30:00+02:00", null)), 409, "APPOINTMENT_SLOT_UNAVAILABLE", null);
        assertProblem(send(move(0, "2026-11-10T10:37:00+02:00", null)), 409, "APPOINTMENT_SLOT_UNAVAILABLE", null);
        assertThat(row()).isEqualTo(before);

        // An Appointment that started an hour before the clock instant (2026-10-02 11:00 Sofia).
        UUID started = appointment(tenant, Instant.parse("2026-10-02T07:00:00Z"), 30);
        assertProblem(send(owner, started, move(0, "2026-11-10T11:00:00+02:00", null), true),
                409, "APPOINTMENT_STATE_CONFLICT", null);
        assertThat(neighbour).isNotNull();
    }

    // ---- privacy -------------------------------------------------------------------------------------------------------------

    @Test
    void neitherTheNoteNorAnyPrivateValueIsLoggedOrEchoedInAnyProblem(CapturedOutput output) throws Exception {
        send(move(0, LATER, null)).andExpect(status().isOk());
        ResultActions stale = send(move(0, LATER, null));
        ResultActions missing = send(owner, UUID.randomUUID(), move(0, LATER, null), true);
        ResultActions invalid = send("{\"expectedVersion\":0,\"" + NOTE + "\":1}");
        ResultActions slot = send(move(1, "2026-11-10T13:00:00+02:00", null));

        for (ResultActions problem : List.of(stale, missing, invalid, slot)) {
            assertThat(body(problem)).doesNotContain(NOTE).doesNotContain(CUSTOMER).doesNotContain(STAFF)
                    .doesNotContain(appointment.toString()).doesNotContain(tenant.customer().toString());
        }
        assertThat(output.getAll()).doesNotContain(NOTE).doesNotContain(CUSTOMER);
    }

    // ---- statement counts ---------------------------------------------------------------------------------------------------------

    private AuthenticatedBusinessContext context() {
        return new Context(owner.userId(), owner.businessId());
    }

    private record Context(UUID userId, UUID businessId) implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(businessId);
        }
    }

    private int statementsOfMove(UUID id, long version, Instant start, UUID staff) {
        CalendarSqlRecording.clear();
        administration.reschedule(context(), new RescheduleAppointmentCommand(id, version, start, staff));
        return CalendarSqlRecording.statementsOfCurrentThread().size();
    }

    @Test
    void theNumberOfStatementsOfAMoveDoesNotDependOnTheNumberOfServicesAndOfAReassignmentNeitherDoes() {
        List<String> names = new ArrayList<>(List.of("A", "B", "C", "D", "E"));
        UUID five = visit(tenant, Instant.parse("2026-11-11T07:00:00Z"), names.toArray(String[]::new));
        UUID one = visit(tenant, Instant.parse("2026-11-12T07:00:00Z"), "A");
        for (UUID service : jdbc.sql("SELECT service_id FROM appointment_service WHERE appointment_id = :id")
                .param("id", five).query(UUID.class).list()) {
            if (!service.equals(tenant.service())) {
                availability.assign(owner.businessId(), second, service);
            }
        }

        int singleMove = statementsOfMove(one, 0L, Instant.parse("2026-11-12T07:30:00Z"), null);
        int severalMove = statementsOfMove(five, 0L, Instant.parse("2026-11-11T07:30:00Z"), null);
        int singleReassign = statementsOfMove(
                one, 1L, Instant.parse("2026-11-12T07:30:00Z"), second);
        int severalReassign = statementsOfMove(
                five, 1L, Instant.parse("2026-11-11T07:30:00Z"), second);

        assertThat(severalMove).isEqualTo(singleMove);
        assertThat(severalReassign).isEqualTo(singleReassign);
        // Business lock, Membership lock, Appointment row and lines, StaffMember lock, revision lock, four
        // availability reads, the conditional update and its lines read, the read-your-write row and lines,
        // and the verification row and lines after the commit.
        assertThat(singleMove).isEqualTo(16);
        for (String sql : CalendarSqlRecording.statementsOfCurrentThread()) {
            assertThat(sql).doesNotContain(CUSTOMER).doesNotContain(NOTE);
        }
    }
}
