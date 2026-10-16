package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentAdministration;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.ReviewedService;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * {@code POST /api/business/appointments}: the exact request shape, the exact success body and
 * headers, CSRF, the role and lifecycle matrix, tenant isolation (byte-identical foreign and missing
 * Customers, Services, and StaffMembers), the fixed problems with their Bulgarian messages, the
 * validation layering, and privacy. Every value is fictional. The clock is 2026-10-02 11:00 in
 * Sofia; the visit is Tuesday 2026-11-10 09:00+02:00.
 */
@ExtendWith(OutputCaptureExtension.class)
class BusinessAppointmentCreateApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String START = "2026-11-10T09:00:00+02:00";
    private static final String PATH = "/api/business/appointments";

    @Autowired AppointmentAdministration administration;

    private Actor owner;
    private Tenant tenant;
    private String serviceName;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", CUSTOMER);
        availability.everyDay(owner.businessId(), tenant.staff(), "09:00", "12:00");
        serviceName = jdbc.sql("SELECT name FROM service WHERE id = :id").param("id", tenant.service())
                .query(String.class).single();
    }

    // ---- requests --------------------------------------------------------------------------------

    private String service(UUID id, String name, Object minutes, Object cents) {
        return "{\"serviceId\":\"%s\",\"reviewedServiceName\":\"%s\",\"reviewedDurationMinutes\":%s,\"reviewedPriceCents\":%s}"
                .formatted(id, name, minutes, cents);
    }

    private String standardService() {
        return service(tenant.service(), serviceName, 30, 1000);
    }

    private String body(UUID customer, UUID staff, String start, String services, String note) {
        return "{\"customerId\":\"%s\",\"staffMemberId\":\"%s\",\"start\":%s,\"services\":[%s]%s}".formatted(
                customer, staff, start == null ? "null" : "\"" + start + "\"", services,
                note == null ? "" : ",\"internalNote\":\"" + note + "\"");
    }

    private String valid() {
        return body(tenant.customer(), tenant.staff(), START, standardService(), null);
    }

    /** The valid body with one more property at the root (not inside a Service element). */
    private String withRoot(String property) {
        String valid = valid();
        return valid.substring(0, valid.length() - 1) + "," + property + "}";
    }

    private ResultActions send(Actor actor, String content, boolean withCsrf) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(content);
        if (actor != null) {
            request.cookie(actor.session());
        }
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions send(String content) throws Exception {
        return send(owner, content, true);
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
        }
        assertThat(result.andReturn().getResponse().getContentType()).contains("application/problem+json");
        assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    private long appointments() {
        return jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
    }

    // ---- success -----------------------------------------------------------------------------------------

    @Test
    void aValidRequestIsCreatedWithTheExactBodyHeadersAndLocation() throws Exception {
        ResultActions result = send(body(tenant.customer(), tenant.staff(), START, standardService(), NOTE));

        result.andExpect(status().isCreated());
        JsonNode created = json(result);
        assertThat(keys(created)).containsExactly(
                "id", "reference", "status", "source", "start", "end", "timezone", "staffMemberId",
                "staffDisplayName", "customerId", "services", "durationMinutes", "price", "internalNote",
                "version", "createdAt");
        assertThat(keys(created.get("services").get(0))).containsExactly("position", "name", "durationMinutes", "price");
        assertThat(created.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(created.get("source").asString()).isEqualTo("MANUAL");
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("start").asString()).isEqualTo(START);
        assertThat(created.get("end").asString()).isEqualTo("2026-11-10T09:30:00+02:00");
        assertThat(created.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(created.get("customerId").asString()).isEqualTo(tenant.customer().toString());
        assertThat(created.get("staffMemberId").asString()).isEqualTo(tenant.staff().toString());
        assertThat(created.get("staffDisplayName").asString()).isEqualTo("Мария Петрова");
        assertThat(created.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(created.get("price").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(created.get("internalNote").asString()).isEqualTo(NOTE);
        assertThat(created.get("services").get(0).get("position").asInt()).isEqualTo(1);
        assertThat(created.get("services").get(0).get("name").asString()).isEqualTo(serviceName);
        assertThat(body(result)).doesNotContain(CUSTOMER).doesNotContain("customerNote").doesNotContain("phone")
                .doesNotContain("email");
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("Location")).isEqualTo(PATH + "/" + created.get("id").asString());
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getHeader("Set-Cookie")).isNull();

        // The same visit through the owner-only detail route: MANUAL, with the stored note and version 0.
        JsonNode detail = json(detail(owner, created.get("id").asString()).andExpect(status().isOk()));
        assertThat(detail.get("source").asString()).isEqualTo("MANUAL");
        assertThat(detail.get("version").asLong()).isZero();
        assertThat(detail.get("internalNote").asString()).isEqualTo(NOTE);
        assertThat(detail.get("customerNote").isNull()).isTrue();
    }

    @Test
    void severalServicesAreOneVisitAndTheNoteIsOptional() throws Exception {
        UUID second = availability.service(owner.businessId(), 20, true);
        availability.assign(owner.businessId(), tenant.staff(), second);
        jdbc.sql("UPDATE service SET name = 'Aa-first' WHERE id = :id").param("id", second).update();
        String secondName = "Aa-first";

        ResultActions result = send(body(tenant.customer(), tenant.staff(), START,
                standardService() + "," + service(second, secondName, 20, 1000), null));

        result.andExpect(status().isCreated());
        JsonNode created = json(result);
        assertThat(created.get("durationMinutes").asInt()).isEqualTo(50);
        assertThat(created.get("price").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(created.get("internalNote").isNull()).isTrue();
        assertThat(created.get("services").get(0).get("name").asString()).isEqualTo(secondName);
        assertThat(created.get("services").get(0).get("position").asInt()).isEqualTo(1);
        assertThat(created.get("services").get(1).get("name").asString()).isEqualTo(serviceName);
        assertThat(appointments()).isEqualTo(1);
    }

    // ---- CSRF and authorization --------------------------------------------------------------------------------

    @Test
    void aRequestWithoutACsrfTokenIsRefusedAndWritesNothing() throws Exception {
        send(owner, valid(), false).andExpect(status().isForbidden());

        assertThat(appointments()).isZero();
    }

    @Test
    void anonymousIsUnauthenticated() throws Exception {
        assertProblem(send(null, valid(), true), 401, "AUTH_REQUIRED", null);
        assertThat(appointments()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void managerAndStaffAreDeniedAndWriteNothing(String role) throws Exception {
        Actor denied = api.actor("ACTIVE", role, true, false, true);
        Tenant own = tenant(denied.businessId(), "Служител", "Клиент");
        availability.everyDay(denied.businessId(), own.staff(), "09:00", "12:00");

        assertProblem(send(denied, body(own.customer(), own.staff(), START,
                service(own.service(), serviceName, 30, 1000), null), true),
                403, "ACCESS_DENIED", "Нямате достъп до тази операция.");
        assertThat(appointments()).isZero();
    }

    @Test
    void noSelectionInactiveForeignAndPlatformAuthorityAloneAreActiveBusinessRequired() throws Exception {
        List<Actor> rejected = List.of(
                api.actor("ACTIVE", "BUSINESS_OWNER", true, false, false),
                api.actor("ACTIVE", "BUSINESS_OWNER", false, false, true),
                api.actor("ACTIVE", null, false, false, true),
                api.actorWithForeignMembership(),
                api.actor("ACTIVE", null, false, true, true));
        for (Actor actor : rejected) {
            assertProblem(send(actor, valid(), true), 403, "ACTIVE_BUSINESS_REQUIRED", null);
        }
        assertThat(appointments()).isZero();
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    @Test
    void aDraftBusinessIsBusinessNotActiveAndASuspendedOneIsBusinessSuspendedBeforeAnyDomainValidation() throws Exception {
        String invalidDomain = body(UUID.randomUUID(), tenant.staff(), START, "", null);
        jdbc.sql("UPDATE business SET status = 'DRAFT' WHERE id = :id").param("id", owner.businessId()).update();
        assertProblem(send(valid()), 409, "BUSINESS_NOT_ACTIVE",
                "Бизнесът не е активен. Часове могат да се създават само в активен бизнес.");
        assertProblem(send(invalidDomain), 409, "BUSINESS_NOT_ACTIVE", null);
        jdbc.sql("UPDATE business SET status = 'SUSPENDED' WHERE id = :id").param("id", owner.businessId()).update();
        assertProblem(send(valid()), 409, "BUSINESS_SUSPENDED", "Спрян бизнес може само да преглежда данните си.");
        assertProblem(send(invalidDomain), 409, "BUSINESS_SUSPENDED", null);

        assertThat(appointments()).isZero();
    }

    // ---- validation layering ------------------------------------------------------------------------------------------

    @Test
    void everyShapeViolationIsTheSameGenericValidationErrorThatEchoesNothing() throws Exception {
        String secret = "СЕНТИНЕЛ-СТОЙНОСТ-5531";
        String ok = standardService();
        List<String> invalid = List.of(
                "",
                "not json",
                "[]",
                "null",
                "\"" + secret + "\"",
                withRoot("\"businessId\":\"" + owner.businessId() + "\""),
                valid().replace("\"customerId\"", "\"" + secret + "\""),
                body(tenant.customer(), tenant.staff(), START, ok, null).replace("\"services\":[" + ok + "]", "\"services\":{}"),
                body(tenant.customer(), tenant.staff(), START, ok.replace("}", ",\"x\":1}"), null),
                body(tenant.customer(), tenant.staff(), START, ok.replace("\"reviewedPriceCents\":1000", ""), null).replace(",}", "}"),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, "30.5", 1000), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, "\"30\"", 1000), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, "3e1", 1000), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, 30, "10.5"), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, 99999999999L, 1000), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, 30, "99999999999999999999"), null),
                body(tenant.customer(), tenant.staff(), START, ok.replace("\"" + tenant.service() + "\"", "\"1\""), null),
                body(tenant.customer(), tenant.staff(), START, ok.replace("\"" + tenant.service() + "\"", "\"+" + tenant.service().toString().substring(1) + "\""), null),
                body(tenant.customer(), tenant.staff(), "2026-11-10T09:00:00", ok, null),
                body(tenant.customer(), tenant.staff(), "2026-11-10", ok, null),
                body(tenant.customer(), tenant.staff(), "10 Nov 2026 09:00 +02:00", ok, null),
                body(tenant.customer(), tenant.staff(), null, ok, null),
                valid().replace("\"customerId\":\"" + tenant.customer() + "\"", "\"customerId\":null"),
                valid().replace("\"customerId\":\"" + tenant.customer() + "\"", "\"customerId\":5"),
                withRoot("\"internalNote\":5"),
                withRoot("\"internalNote\":[]"),
                body(tenant.customer(), tenant.staff(), START, ok.replace("\"" + serviceName + "\"", "5"), null));
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
        assertThat(appointments()).isZero();
    }

    @Test
    void everyDomainBoundViolationIsTheSameValidationErrorAfterAuthorization() throws Exception {
        List<UUID> six = new ArrayList<>();
        StringBuilder sixServices = new StringBuilder();
        for (int index = 0; index < 6; index++) {
            six.add(UUID.randomUUID());
            sixServices.append(index == 0 ? "" : ",").append(service(six.get(index), "x", 1, 1));
        }
        String note501 = "я".repeat(501);
        List<String> invalid = List.of(
                body(tenant.customer(), tenant.staff(), START, "", null),
                body(tenant.customer(), tenant.staff(), START, standardService() + "," + standardService(), null),
                body(tenant.customer(), tenant.staff(), START, sixServices.toString(), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, 0, 1000), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, 481, 1000), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), serviceName, 30, -1), null),
                body(tenant.customer(), tenant.staff(), START, service(tenant.service(), "", 30, 1000), null),
                body(tenant.customer(), tenant.staff(), START, standardService(), note501),
                body(tenant.customer(), tenant.staff(), "1999-12-31T00:00:00+00:00", standardService(), null),
                body(tenant.customer(), tenant.staff(), "2101-01-01T12:00:00+00:00", standardService(), null));
        String shapeBody = body(send("{}"));
        for (String content : invalid) {
            ResultActions result = send(content);
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(body(result)).isEqualTo(shapeBody);
        }
        assertThat(appointments()).isZero();
    }

    @Test
    void anUnsupportedMediaTypeAndAWrongVerbAreRefusedWithoutEchoing() throws Exception {
        ResultActions text = mvc.perform(post(PATH).cookie(owner.session()).with(csrf())
                .contentType(MediaType.TEXT_PLAIN).content(valid()));
        assertProblem(text, 415, "UNSUPPORTED_MEDIA_TYPE", "Заявката не може да бъде изпълнена.");
        assertThat(body(text)).doesNotContain(tenant.customer().toString());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(PATH)
                .cookie(owner.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(valid()))
                .andExpect(status().isMethodNotAllowed());
        assertThat(appointments()).isZero();
    }

    // ---- tenant isolation and the fixed problems ----------------------------------------------------------------------------

    @Test
    void aForeignAndAMissingCustomerAreByteIdenticalNotFoundResponses() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");

        ResultActions missing = send(body(UUID.randomUUID(), tenant.staff(), START, standardService(), null));
        ResultActions foreignResult = send(body(foreign.customer(), tenant.staff(), START, standardService(), null));

        assertProblem(missing, 404, "CUSTOMER_NOT_FOUND", "Клиентът не е намерен.");
        assertThat(body(foreignResult)).isEqualTo(body(missing));
        assertThat(body(missing)).doesNotContain(foreign.customer().toString());
        assertThat(appointments()).isZero();
    }

    @Test
    void aForeignAMissingAndAnInactiveServiceAreByteIdenticalConflicts() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        UUID inactive = availability.service(owner.businessId(), 30, false);
        availability.assign(owner.businessId(), tenant.staff(), inactive);
        List<String> services = List.of(
                service(UUID.randomUUID(), "x", 30, 1000),
                service(foreign.service(), serviceName, 30, 1000),
                service(inactive, "x", 30, 1000));

        String first = null;
        for (String service : services) {
            ResultActions result = send(body(tenant.customer(), tenant.staff(), START, service, null));
            assertProblem(result, 409, "APPOINTMENT_SERVICE_UNAVAILABLE", "Някоя от избраните услуги вече не е налична.");
            first = first == null ? body(result) : first;
            assertThat(body(result)).isEqualTo(first);
            assertThat(body(result)).doesNotContain(foreign.service().toString());
        }
        assertThat(appointments()).isZero();
    }

    @Test
    void aForeignAMissingAndAnInactiveStaffMemberAreByteIdenticalConflicts() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "Чужд служител", "Чужд клиент");
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        availability.assign(owner.businessId(), inactive, tenant.service());

        String first = null;
        for (UUID staff : List.of(UUID.randomUUID(), foreign.staff(), inactive)) {
            ResultActions result = send(body(tenant.customer(), staff, START, standardService(), null));
            assertProblem(result, 409, "APPOINTMENT_STAFF_UNAVAILABLE",
                    "Избраният член на екипа не е наличен за тези услуги.");
            first = first == null ? body(result) : first;
            assertThat(body(result)).isEqualTo(first);
            assertThat(body(result)).doesNotContain(foreign.staff().toString());
        }
        assertThat(appointments()).isZero();
    }

    @Test
    void aChangedNameDurationOrPriceIsReviewChangedWithoutSayingWhich() throws Exception {
        List<String> stale = List.of(
                service(tenant.service(), serviceName + "!", 30, 1000),
                service(tenant.service(), serviceName, 31, 1000),
                service(tenant.service(), serviceName, 30, 1001));
        String first = null;
        for (String service : stale) {
            ResultActions result = send(body(tenant.customer(), tenant.staff(), START, service, null));
            assertProblem(result, 409, "APPOINTMENT_REVIEW_CHANGED",
                    "Услугите са променени. Прегледайте ги отново и опитайте пак.");
            first = first == null ? body(result) : first;
            assertThat(body(result)).isEqualTo(first);
        }
        assertThat(appointments()).isZero();
    }

    @Test
    void anUnofferedStartIsSlotUnavailableAndATakenOneToo() throws Exception {
        assertProblem(send(body(tenant.customer(), tenant.staff(), "2026-11-10T13:00:00+02:00", standardService(), null)),
                409, "APPOINTMENT_SLOT_UNAVAILABLE", "Избраният час вече не е свободен.");
        assertProblem(send(body(tenant.customer(), tenant.staff(), "2026-11-10T09:07:00+02:00", standardService(), null)),
                409, "APPOINTMENT_SLOT_UNAVAILABLE", null);
        send(valid()).andExpect(status().isCreated());
        assertProblem(send(valid()), 409, "APPOINTMENT_SLOT_UNAVAILABLE", null);
        assertThat(appointments()).isEqualTo(1);
    }

    @Test
    void theStartMayBeGivenInAnyOffsetAndIsStoredAsTheSameInstant() throws Exception {
        ResultActions result = send(body(tenant.customer(), tenant.staff(), "2026-11-10T07:00:00Z", standardService(), null));

        result.andExpect(status().isCreated());
        assertThat(json(result).get("start").asString()).isEqualTo(START);
        assertThat(jdbc.sql("SELECT start_at FROM appointment").query(java.time.OffsetDateTime.class).single().toInstant())
                .isEqualTo(Instant.parse("2026-11-10T07:00:00Z"));
    }

    @Test
    void aStaleClientSuppliedTotalIsNeverTrustedBecauseTheRequestHasNone() throws Exception {
        ResultActions result = send(withRoot("\"totalPriceCents\":1,\"totalDurationMinutes\":1"));

        assertProblem(result, 400, "VALIDATION_ERROR", null);
        assertThat(appointments()).isZero();
    }

    // ---- privacy -------------------------------------------------------------------------------------------------------------

    @Test
    void neitherTheNoteNorAnyPrivateValueIsLoggedOrEchoedInAnyProblem(CapturedOutput output) throws Exception {
        send(body(tenant.customer(), tenant.staff(), START, standardService(), NOTE)).andExpect(status().isCreated());
        send(body(UUID.randomUUID(), tenant.staff(), START, standardService(), NOTE)).andExpect(status().isNotFound());
        ResultActions conflict = send(body(tenant.customer(), tenant.staff(), START, standardService(), NOTE));
        ResultActions invalid = send(withRoot("\"" + NOTE + "\":1"));

        assertThat(body(conflict)).doesNotContain(NOTE).doesNotContain(CUSTOMER);
        assertThat(body(invalid)).doesNotContain(NOTE);
        assertThat(output.getAll()).doesNotContain(NOTE).doesNotContain(CUSTOMER).doesNotContain(serviceName);
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

    private ReviewedService reviewed(UUID service, String name) {
        return new ReviewedService(service, name, 30, 1000);
    }

    private int statementsOfCreate(List<ReviewedService> services, int minutesPerService, Instant start) {
        CalendarSqlRecording.clear();
        administration.create(context(), new CreateAppointmentCommand(
                tenant.customer(), tenant.staff(), start, services, null));
        return CalendarSqlRecording.statementsOfCurrentThread().size();
    }

    @Test
    void theNumberOfStatementsOfACreationDoesNotDependOnTheNumberOfServices() {
        List<ReviewedService> one = List.of(reviewed(tenant.service(), serviceName));
        List<ReviewedService> five = new ArrayList<>(one);
        for (int index = 1; index < 5; index++) {
            UUID extra = availability.service(owner.businessId(), 30, true);
            availability.assign(owner.businessId(), tenant.staff(), extra);
            String name = jdbc.sql("SELECT name FROM service WHERE id = :id").param("id", extra)
                    .query(String.class).single();
            five.add(reviewed(extra, name));
        }

        int single = statementsOfCreate(one, 30, Instant.parse("2026-11-10T07:00:00Z"));
        int several = statementsOfCreate(five, 30, Instant.parse("2026-11-11T07:00:00Z"));

        assertThat(several).isEqualTo(single);
        // owner context lock, Membership lock, Customer read, StaffMember lock, revision lock, Service lock,
        // four availability reads, the Appointment and lines inserts, the read-your-write (row and lines),
        // and the verification read after the commit (row and lines): fixed whatever the Services.
        assertThat(single).isEqualTo(16);
        for (String sql : CalendarSqlRecording.statementsOfCurrentThread()) {
            assertThat(sql).doesNotContain(CUSTOMER);
        }
    }
}
