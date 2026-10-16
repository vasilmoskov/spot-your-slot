package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.time.Instant;
import java.util.ArrayList;
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
 * {@code GET /api/business/appointment-options} and {@code …/availability}: the exact request and
 * response contracts, the StaffMember intersection, the explicit StaffMember rule, the summed
 * duration and its 480-minute limit, zero notice, busy time, daylight-saving days, the lifecycle,
 * role, and tenant matrices, the fixed problems, and privacy. Every value is fictional. The clock is
 * 2026-10-02 11:00 in Sofia (UTC+03:00); the working day used is Tuesday 2026-11-10 09:00 to 12:00 (UTC+02:00).
 */
@ExtendWith(OutputCaptureExtension.class)
class BusinessAppointmentOptionsApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String OPTIONS = "/api/business/appointment-options";
    private static final String AVAILABILITY = OPTIONS + "/availability";
    private static final String DAY = "2026-11-10";

    private Actor owner;
    private Tenant tenant;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", CUSTOMER);
        availability.everyDay(owner.businessId(), tenant.staff(), "09:00", "12:00");
    }

    // ---- requests --------------------------------------------------------------------------------

    private static String services(UUID... ids) {
        List<String> parts = new ArrayList<>();
        for (UUID id : ids) {
            parts.add("serviceId=" + id);
        }
        return String.join("&", parts);
    }

    private ResultActions options(Actor actor, String query) throws Exception {
        var request = get(OPTIONS + (query == null ? "" : "?" + query));
        if (actor != null) {
            request.cookie(actor.session());
        }
        return mvc.perform(request);
    }

    private ResultActions slots(Actor actor, String query) throws Exception {
        var request = get(AVAILABILITY + (query == null ? "" : "?" + query));
        if (actor != null) {
            request.cookie(actor.session());
        }
        return mvc.perform(request);
    }

    private ResultActions slots(UUID staff, String date, UUID... serviceIds) throws Exception {
        return slots(owner, services(serviceIds) + "&staffMemberId=" + staff + "&date=" + date);
    }

    private static void assertProblem(ResultActions result, int status, String code, String detail) throws Exception {
        result.andExpect(status().is(status));
        JsonNode body = json(result);
        assertThat(body.get("code").asString()).isEqualTo(code);
        if (detail != null) {
            assertThat(body.get("detail").asString()).isEqualTo(detail);
        }
        if (status != 401) {
            assertThat(body.get("instance").asString()).isEqualTo(OPTIONS);
        }
        assertThat(result.andReturn().getResponse().getContentType()).contains("application/problem+json");
        assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    private static List<String> starts(JsonNode response) {
        List<String> starts = new ArrayList<>();
        for (JsonNode slot : response.get("slots")) {
            starts.add(slot.get("start").asString());
        }
        return starts;
    }

    private UUID service(int minutes) {
        return availability.service(owner.businessId(), minutes, true);
    }

    // ---- options: success --------------------------------------------------------------------------------

    @Test
    void oneServiceYieldsTheExactBodyHeadersAndTheEligibleStaffMember() throws Exception {
        ResultActions result = options(owner, services(tenant.service()));

        result.andExpect(status().isOk());
        JsonNode body = json(result);
        assertThat(keys(body)).containsExactly("timezone", "durationMinutes", "staffMembers");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(body.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(body.get("staffMembers")).hasSize(1);
        assertThat(keys(body.get("staffMembers").get(0))).containsExactly("id", "displayName");
        assertThat(body.get("staffMembers").get(0).get("id").asString()).isEqualTo(tenant.staff().toString());
        assertThat(body.get("staffMembers").get(0).get("displayName").asString()).isEqualTo("Мария Петрова");
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(body(result)).doesNotContain(CUSTOMER);
    }

    @Test
    void fiveServicesSumTheirDurationsAndTheOrderOfTheSelectionDoesNotMatter() throws Exception {
        List<UUID> ids = new ArrayList<>();
        int[] minutes = {15, 20, 25, 30, 45};
        for (int duration : minutes) {
            UUID service = service(duration);
            availability.assign(owner.businessId(), tenant.staff(), service);
            ids.add(service);
        }

        String forward = body(options(owner, services(ids.toArray(UUID[]::new))).andExpect(status().isOk()));
        List<UUID> reversed = new ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        String backward = body(options(owner, services(reversed.toArray(UUID[]::new))).andExpect(status().isOk()));

        assertThat(backward).isEqualTo(forward);
        assertThat(BusinessCalendarApiIntegrationTest.json(options(owner, services(ids.toArray(UUID[]::new))))
                .get("durationMinutes").asInt()).isEqualTo(135);
    }

    @Test
    void theStaffMembersAreTheIntersectionNeverTheUnion() throws Exception {
        UUID second = service(20);
        UUID both = staff(owner.businessId(), "Анна Бойчева", true);
        availability.assign(owner.businessId(), both, tenant.service());
        availability.assign(owner.businessId(), both, second);
        availability.assign(owner.businessId(), tenant.staff(), second);
        UUID onlyFirst = staff(owner.businessId(), "Борис Георгиев", true);
        availability.assign(owner.businessId(), onlyFirst, tenant.service());

        JsonNode both2 = json(options(owner, services(tenant.service(), second)).andExpect(status().isOk()));
        List<String> names = new ArrayList<>();
        both2.get("staffMembers").forEach(member -> names.add(member.get("displayName").asString()));
        // Ordered by canonical name then identifier; only the two assigned to both Services.
        assertThat(names).containsExactly("Анна Бойчева", "Мария Петрова");

        JsonNode single = json(options(owner, services(tenant.service())).andExpect(status().isOk()));
        assertThat(single.get("staffMembers")).hasSize(3);
    }

    @Test
    void nobodyWhoSupportsTheWholeSelectionIsAnEmptyListNotAnError() throws Exception {
        UUID second = service(20);
        UUID other = staff(owner.businessId(), "Анна Бойчева", true);
        availability.assign(owner.businessId(), other, second);

        JsonNode body = json(options(owner, services(tenant.service(), second)).andExpect(status().isOk()));

        assertThat(body.get("staffMembers")).isEmpty();
        assertThat(body.get("durationMinutes").asInt()).isEqualTo(50);
    }

    @Test
    void inactiveAndUnassignedStaffMembersAreNotOffered() throws Exception {
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        availability.assign(owner.businessId(), inactive, tenant.service());
        staff(owner.businessId(), "Без услуги", true);

        JsonNode body = json(options(owner, services(tenant.service())).andExpect(status().isOk()));

        assertThat(body.get("staffMembers")).hasSize(1);
    }

    @Test
    void theDurationLimitIsFourHundredEightyMinutesInclusive() throws Exception {
        UUID first = service(300);
        UUID exact = service(180);
        UUID over = service(181);
        for (UUID service : List.of(first, exact, over)) {
            availability.assign(owner.businessId(), tenant.staff(), service);
        }

        assertThat(json(options(owner, services(first, exact)).andExpect(status().isOk()))
                .get("durationMinutes").asInt()).isEqualTo(480);
        assertProblem(options(owner, services(first, over)), 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        assertProblem(slots(tenant.staff(), DAY, first, over), 400, "VALIDATION_ERROR", null);
    }

    // ---- options: Services -------------------------------------------------------------------------------

    @Test
    void aMissingForeignAndInactiveServiceAreByteIdentical() throws Exception {
        Actor other = api.owner("ACTIVE");
        UUID foreign = availability.service(other.businessId(), 30, true);
        UUID inactive = availability.service(owner.businessId(), 30, false);

        String missing = body(options(owner, services(tenant.service(), UUID.randomUUID())).andExpect(status().isConflict()));
        String foreignBody = body(options(owner, services(tenant.service(), foreign)).andExpect(status().isConflict()));
        String inactiveBody = body(options(owner, services(tenant.service(), inactive)).andExpect(status().isConflict()));

        assertThat(foreignBody).isEqualTo(missing);
        assertThat(inactiveBody).isEqualTo(missing);
        JsonNode problem = BusinessCustomerApiTestSupport.parse(missing);
        assertThat(problem.get("code").asString()).isEqualTo("APPOINTMENT_SERVICE_UNAVAILABLE");
        assertThat(problem.get("detail").asString()).isEqualTo("Някоя от избраните услуги вече не е налична.");
        assertThat(missing).doesNotContain(foreign.toString(), inactive.toString());
        // The availability route reports the same result for the same reason.
        assertThat(body(slots(tenant.staff(), DAY, tenant.service(), foreign))).isEqualTo(missing);
    }

    // ---- availability: success ---------------------------------------------------------------------------

    @Test
    void anEligibleStaffMemberGetsTheExactBodyAndEveryFifteenMinuteStart() throws Exception {
        ResultActions result = slots(tenant.staff(), DAY, tenant.service());

        result.andExpect(status().isOk());
        JsonNode body = json(result);
        assertThat(keys(body)).containsExactly("timezone", "date", "durationMinutes", "slots");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(body.get("date").asString()).isEqualTo(DAY);
        assertThat(body.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(keys(body.get("slots").get(0))).containsExactly("start", "end");
        assertThat(starts(body)).containsExactly(
                "2026-11-10T09:00:00+02:00", "2026-11-10T09:15:00+02:00", "2026-11-10T09:30:00+02:00",
                "2026-11-10T09:45:00+02:00", "2026-11-10T10:00:00+02:00", "2026-11-10T10:15:00+02:00",
                "2026-11-10T10:30:00+02:00", "2026-11-10T10:45:00+02:00", "2026-11-10T11:00:00+02:00",
                "2026-11-10T11:15:00+02:00", "2026-11-10T11:30:00+02:00");
        assertThat(body.get("slots").get(0).get("end").asString()).isEqualTo("2026-11-10T09:30:00+02:00");
        assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(body(result)).doesNotContain(CUSTOMER).doesNotContain(tenant.staff().toString());
    }

    @Test
    void theSlotsOfAVisitOccupyTheSummedDurationOfItsServices() throws Exception {
        UUID second = service(60);
        availability.assign(owner.businessId(), tenant.staff(), second);

        JsonNode body = json(slots(tenant.staff(), DAY, tenant.service(), second).andExpect(status().isOk()));

        assertThat(body.get("durationMinutes").asInt()).isEqualTo(90);
        // 09:00 to 12:00 holds a 90-minute visit starting at 09:00 through 10:30.
        assertThat(starts(body)).hasSize(7).startsWith("2026-11-10T09:00:00+02:00").endsWith("2026-11-10T10:30:00+02:00");
        assertThat(body.get("slots").get(0).get("end").asString()).isEqualTo("2026-11-10T10:30:00+02:00");
    }

    @Test
    void noMinimumNoticeIsApplied_theFirstStartIsTheClockInstantItselfOnTheGrid() throws Exception {
        // 2026-10-02 11:00 in Sofia is the clock; the public two-hour notice would start at 13:00.
        availability.everyDay(owner.businessId(), tenant.staff(), "13:00", "17:00");

        JsonNode today = json(slots(tenant.staff(), "2026-10-02", tenant.service()).andExpect(status().isOk()));
        // The first start of a day whose window already began is the clock instant itself.
        assertThat(starts(today)).first().isEqualTo("2026-10-02T11:00:00+03:00");
        assertThat(starts(today)).doesNotContain("2026-10-02T10:45:00+03:00");

        clock.set(Instant.parse("2026-10-02T07:07:00Z"));
        JsonNode offGrid = json(slots(tenant.staff(), "2026-10-02", tenant.service()).andExpect(status().isOk()));
        assertThat(starts(offGrid)).first().isEqualTo("2026-10-02T10:15:00+03:00");
    }

    @Test
    void aPastDateOffersNothing() throws Exception {
        JsonNode body = json(slots(tenant.staff(), "2026-09-30", tenant.service()).andExpect(status().isOk()));

        assertThat(body.get("slots")).isEmpty();
    }

    @Test
    void busyTimeBlocksOnlyOverlapAndAdjacencyIsFree() throws Exception {
        appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 30); // 10:00 to 10:30 local

        List<String> offered = starts(json(slots(tenant.staff(), DAY, tenant.service()).andExpect(status().isOk())));

        assertThat(offered).contains("2026-11-10T09:30:00+02:00", "2026-11-10T10:30:00+02:00")
                .doesNotContain("2026-11-10T09:45:00+02:00", "2026-11-10T10:00:00+02:00", "2026-11-10T10:15:00+02:00");
    }

    @Test
    void aCancelledAppointmentAndAnotherStaffMembersAppointmentDoNotBlock() throws Exception {
        appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 30, AppointmentFixtures::cancelled);
        UUID other = staff(owner.businessId(), "Анна Бойчева", true);
        availability.assign(owner.businessId(), other, tenant.service());
        appointment(withStaff(tenant, other), Instant.parse("2026-11-10T07:00:00Z"), 120);

        List<String> offered = starts(json(slots(tenant.staff(), DAY, tenant.service()).andExpect(status().isOk())));

        assertThat(offered).hasSize(11);
    }

    @Test
    void theWorkingScheduleBoundsTheDayAndADayOffIsEmpty() throws Exception {
        jdbc.sql("DELETE FROM staff_working_period WHERE staff_member_id = :id AND weekday = 2")
                .param("id", tenant.staff()).update(); // Tuesday

        assertThat(json(slots(tenant.staff(), DAY, tenant.service()).andExpect(status().isOk())).get("slots")).isEmpty();
    }

    @Test
    void eligibleStaffWithoutFreeTimeIsDistinctFromNobodyEligible() throws Exception {
        UUID second = service(20);
        availability.assign(owner.businessId(), tenant.staff(), second);

        JsonNode noTime = json(slots(tenant.staff(), "2026-09-30", tenant.service(), second).andExpect(status().isOk()));
        assertThat(noTime.get("slots")).isEmpty();
        assertThat(json(options(owner, services(tenant.service(), second))).get("staffMembers")).hasSize(1);
    }

    // ---- availability: the explicit StaffMember ------------------------------------------------------------------

    @Test
    void anUnassignedInactiveForeignAndMissingStaffMemberAreByteIdentical() throws Exception {
        UUID second = service(20);
        UUID unassigned = staff(owner.businessId(), "Без втората", true);
        availability.assign(owner.businessId(), unassigned, tenant.service());
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        availability.assign(owner.businessId(), inactive, tenant.service());
        Actor other = api.owner("ACTIVE");
        UUID foreign = availability.staffMember(other.businessId(), true);
        availability.assign(other.businessId(), foreign, availability.service(other.businessId(), 30, true));
        availability.assign(owner.businessId(), tenant.staff(), second);

        String missing = body(slots(UUID.randomUUID(), DAY, tenant.service(), second).andExpect(status().isConflict()));
        for (UUID staff : List.of(unassigned, inactive, foreign)) {
            assertThat(body(slots(staff, DAY, tenant.service(), second).andExpect(status().isConflict())))
                    .isEqualTo(missing);
        }
        JsonNode problem = BusinessCustomerApiTestSupport.parse(missing);
        assertThat(problem.get("code").asString()).isEqualTo("APPOINTMENT_STAFF_UNAVAILABLE");
        assertThat(problem.get("detail").asString()).isEqualTo("Избраният член на екипа не е наличен за тези услуги.");
        assertThat(problem.get("instance").asString()).isEqualTo(OPTIONS);
        assertThat(missing).doesNotContain(unassigned.toString(), foreign.toString(), inactive.toString());
    }

    @Test
    void noStaffMemberIsEverChosenAutomatically() throws Exception {
        assertProblem(slots(owner, services(tenant.service()) + "&date=" + DAY), 400, "VALIDATION_ERROR", null);
    }

    // ---- availability: daylight saving ----------------------------------------------------------------------------

    @Test
    void bothOccurrencesOfARepeatedLocalTimeAreOfferedWithTheirOffsets() throws Exception {
        // A later clock outdates the session of the default owner, so a new owner is created at that time.
        Instant late = Instant.parse("2026-10-20T00:00:00Z");
        clock.set(late);
        Actor lateOwner = new BusinessCustomerApiTestSupport(mvc, jdbc, tokens, late).owner("ACTIVE");
        Tenant lateTenant = tenant(lateOwner.businessId(), "Мария Петрова", CUSTOMER);
        availability.everyDay(lateOwner.businessId(), lateTenant.staff(), "02:00", "06:00");

        List<String> offered = starts(json(slots(lateOwner,
                services(lateTenant.service()) + "&staffMemberId=" + lateTenant.staff() + "&date=2026-10-25")
                .andExpect(status().isOk())));

        assertThat(offered).contains(
                "2026-10-25T03:30:00+03:00", "2026-10-25T03:30:00+02:00",
                "2026-10-25T03:00:00+03:00", "2026-10-25T03:00:00+02:00");
        assertThat(offered).doesNotHaveDuplicates();
        // The instants, not the wall-clock text, are ordered: the +03:00 occurrence comes first.
        assertThat(offered.indexOf("2026-10-25T03:30:00+03:00")).isLessThan(offered.indexOf("2026-10-25T03:30:00+02:00"));
        List<Instant> instants = offered.stream().map(java.time.OffsetDateTime::parse)
                .map(java.time.OffsetDateTime::toInstant).toList();
        assertThat(instants).isSorted();
    }

    @Test
    void aSpringGapHasNoStartInTheHourThatDoesNotExist() throws Exception {
        clock.set(Instant.parse("2026-03-01T00:00:00Z"));
        availability.everyDay(owner.businessId(), tenant.staff(), "02:00", "05:00");

        List<String> offered = starts(json(slots(tenant.staff(), "2026-03-29", tenant.service()).andExpect(status().isOk())));

        assertThat(offered).isNotEmpty();
        assertThat(offered).noneMatch(start -> start.startsWith("2026-03-29T03:"));
        assertThat(offered).contains("2026-03-29T02:30:00+02:00", "2026-03-29T04:00:00+03:00");
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Test
    void onlyAnActiveBusinessOffersChoices() throws Exception {
        Actor draft = api.owner("DRAFT");
        Tenant draftTenant = tenant(draft.businessId(), "Служител", "Клиент");
        Actor suspended = api.owner("SUSPENDED");
        Tenant suspendedTenant = tenant(suspended.businessId(), "Служител", "Клиент");

        for (ResultActions result : List.of(
                options(draft, services(draftTenant.service())),
                slots(draft, services(draftTenant.service()) + "&staffMemberId=" + draftTenant.staff() + "&date=" + DAY))) {
            assertProblem(result, 409, "BUSINESS_NOT_ACTIVE",
                    "Бизнесът не е активен. Часове могат да се създават само в активен бизнес.");
        }
        for (ResultActions result : List.of(
                options(suspended, services(suspendedTenant.service())),
                slots(suspended, services(suspendedTenant.service()) + "&staffMemberId=" + suspendedTenant.staff() + "&date=" + DAY))) {
            assertProblem(result, 409, "BUSINESS_SUSPENDED", "Спрян бизнес може само да преглежда данните си.");
        }
    }

    // ---- authentication, roles, isolation ------------------------------------------------------------------------

    private List<ResultActions> everyRoute(Actor actor) throws Exception {
        return List.of(
                options(actor, services(tenant.service())),
                slots(actor, services(tenant.service()) + "&staffMemberId=" + tenant.staff() + "&date=" + DAY));
    }

    @Test
    void anonymousRequestsAreUnauthenticated() throws Exception {
        for (ResultActions result : everyRoute(null)) {
            assertProblem(result, 401, "AUTH_REQUIRED", null);
        }
    }

    @Test
    void anAbsentBusinessSelectionIsRequired() throws Exception {
        Actor noSelection = api.actor("ACTIVE", "BUSINESS_OWNER", true, false, false);
        for (ResultActions result : everyRoute(noSelection)) {
            assertProblem(result, 403, "ACTIVE_BUSINESS_REQUIRED", null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void managerAndStaffAreDenied(String role) throws Exception {
        Actor denied = api.actor("ACTIVE", role, true, false, true);
        for (ResultActions result : everyRoute(denied)) {
            assertProblem(result, 403, "ACCESS_DENIED", "Нямате достъп до тази операция.");
        }
    }

    @Test
    void inactiveMissingForeignAndPlatformAuthorityAloneAreRejected() throws Exception {
        List<Actor> rejected = List.of(
                api.actor("ACTIVE", "BUSINESS_OWNER", false, false, true),
                api.actor("ACTIVE", null, false, false, true),
                api.actorWithForeignMembership(),
                api.actor("ACTIVE", null, false, true, true));
        for (Actor actor : rejected) {
            for (ResultActions result : everyRoute(actor)) {
                assertProblem(result, 403, "ACTIVE_BUSINESS_REQUIRED", null);
            }
        }
    }

    @Test
    void aPlatformAdministratorWhoIsAlsoAnOwnerIsAllowedAsAnOwner() throws Exception {
        Actor both = api.actor("ACTIVE", "BUSINESS_OWNER", true, true, true);
        Tenant data = tenant(both.businessId(), "Служител", "Клиент");

        options(both, services(data.service())).andExpect(status().isOk());
    }

    @Test
    void anotherBusinessesDataNeverAppearsAndAClientSuppliedBusinessIdIsIgnored() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "ЧУЖД-СЛУЖИТЕЛ-5521", "Чужд клиент");

        JsonNode own = json(options(owner, services(tenant.service()) + "&businessId=" + other.businessId())
                .andExpect(status().isOk()));
        assertThat(own.get("staffMembers")).hasSize(1);
        assertThat(own.toString()).doesNotContain("ЧУЖД-СЛУЖИТЕЛ-5521");
        // The other Business's identifiers are indistinguishable from missing ones.
        assertProblem(options(owner, services(foreign.service())), 409, "APPOINTMENT_SERVICE_UNAVAILABLE", null);
        assertProblem(slots(foreign.staff(), DAY, tenant.service()), 409, "APPOINTMENT_STAFF_UNAVAILABLE", null);
    }

    // ---- request shape -------------------------------------------------------------------------------------------

    @Test
    void everyMalformedRequestIsAGenericValidationErrorThatEchoesNothing() throws Exception {
        UUID id = tenant.service();
        UUID staff = tenant.staff();
        String upper = id.toString().toUpperCase();
        List<String> badOptions = List.of(
                "",
                "serviceId=",
                "serviceId=not-a-uuid",
                "serviceId=" + id + "," + UUID.randomUUID(),
                "serviceId=" + id + "&serviceId=" + id,
                "serviceId=" + id.toString().replace("-", ""),
                "serviceId=+" + id.toString().substring(1),
                services(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID()),
                "service=" + id);
        for (String query : badOptions) {
            ResultActions result = options(owner, query);
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(body(result)).doesNotContain("not-a-uuid").doesNotContain(id.toString());
        }
        String base = services(id) + "&staffMemberId=" + staff;
        List<String> badSlots = List.of(
                services(id),
                services(id) + "&staffMemberId=" + staff,
                base + "&date=",
                base + "&date=2026-13-01",
                base + "&date=2026-02-30",
                base + "&date=2026-1-1",
                base + "&date=20261110",
                base + "&date=1999-12-31",
                base + "&date=2101-01-01",
                base + "&date=" + DAY + "&date=" + DAY,
                services(id) + "&staffMemberId=x&date=" + DAY,
                services(id) + "&staffMemberId=" + staff + "&staffMemberId=" + staff + "&date=" + DAY,
                "staffMemberId=" + staff + "&date=" + DAY);
        for (String query : badSlots) {
            assertProblem(slots(owner, query), 400, "VALIDATION_ERROR", null);
        }
        // An upper-case canonical identifier is the same identifier.
        options(owner, "serviceId=" + upper).andExpect(status().isOk());
        // Unknown parameters carry no meaning and are ignored.
        options(owner, services(id) + "&unknown=1").andExpect(status().isOk());
    }

    @Test
    void theLatestAndEarliestSupportedDatesAreAccepted() throws Exception {
        slots(tenant.staff(), "2000-01-01", tenant.service()).andExpect(status().isOk());
        slots(tenant.staff(), "2100-12-31", tenant.service()).andExpect(status().isOk());
    }

    // ---- headers, verbs, privacy ---------------------------------------------------------------------------------

    @Test
    void readsNeedNoCsrfTokenAndEveryOtherVerbIsRefusedWithoutWriting() throws Exception {
        for (ResultActions result : everyRoute(owner)) {
            result.andExpect(status().isOk());
            assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
        }
        long before = jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
        for (String path : List.of(OPTIONS, AVAILABILITY)) {
            mvc.perform(post(path).cookie(owner.session())).andExpect(status().isForbidden());
            mvc.perform(put(path).cookie(owner.session())).andExpect(status().isForbidden());
            mvc.perform(delete(path).cookie(owner.session())).andExpect(status().isForbidden());
            for (int code : List.of(
                    mvc.perform(post(path).cookie(owner.session()).with(csrf())).andReturn().getResponse().getStatus(),
                    mvc.perform(put(path).cookie(owner.session()).with(csrf())).andReturn().getResponse().getStatus(),
                    mvc.perform(delete(path).cookie(owner.session()).with(csrf())).andReturn().getResponse().getStatus())) {
                assertThat(code).isIn(404, 405);
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void noCustomerContactOrNoteDatumIsInAnyResponseProblemOrLog(CapturedOutput output) throws Exception {
        appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 30, row -> {
            row.put("internal_note", "СЕНТИНЕЛ-БЕЛЕЖКА-9917");
            row.put("customer_note", "СЕНТИНЕЛ-ГОСТ-БЕЛЕЖКА-5521");
        });
        List<String> bodies = new ArrayList<>();
        for (ResultActions result : everyRoute(owner)) {
            bodies.add(body(result));
        }
        bodies.add(body(options(owner, "serviceId=bad")));
        bodies.add(body(slots(owner, services(tenant.service()) + "&staffMemberId=" + UUID.randomUUID() + "&date=" + DAY)));

        for (String raw : bodies) {
            assertThat(raw).doesNotContain(CUSTOMER, "СЕНТИНЕЛ", "@example.test", "+359", tenant.customer().toString(),
                    owner.businessId().toString(), "SELECT", "constraint", "Exception");
        }
        assertThat(output.getAll()).doesNotContain("СЕНТИНЕЛ", CUSTOMER, "@example.test", "+359");
    }
}
