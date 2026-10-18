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
 * {@code GET /api/business/appointments/{id}/reschedule-options} and {@code …/availability}: the exact
 * request and response contracts, the stored duration and Service identities, the current versus the
 * replacement StaffMember rules, the ignored own interval, zero notice, busy time, daylight-saving
 * days, the lifecycle, state, role, and tenant matrices, the fixed problems, and privacy. Every value
 * is fictional. The clock is 2026-10-02 11:00 in Sofia (UTC+03:00); the Appointment is Tuesday
 * 2026-11-10 09:00 to 09:30 in Sofia (UTC+02:00); the StaffMember works 09:00 to 12:00 every day.
 */
@ExtendWith(OutputCaptureExtension.class)
class BusinessAppointmentRescheduleOptionsApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String CUSTOMER = "СЕНТИНЕЛ-КЛИЕНТ-ИМЕ-7702";
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-9917";
    private static final String GUEST_NOTE = "СЕНТИНЕЛ-ГОСТ-БЕЛЕЖКА-5521";
    private static final String BASE = "/api/business/appointments";
    private static final String DAY = "2026-11-10";

    private Actor owner;
    private Tenant tenant;
    private UUID appointment;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", CUSTOMER);
        availability.everyDay(owner.businessId(), tenant.staff(), "09:00", "12:00");
        appointment = appointment(tenant, NOV_10, 30, row -> {
            row.put("internal_note", NOTE);
            row.put("customer_note", GUEST_NOTE);
        });
    }

    // ---- requests --------------------------------------------------------------------------------

    private static String optionsPath(Object id) {
        return BASE + "/" + id + "/reschedule-options";
    }

    private ResultActions options(Actor actor, Object id) throws Exception {
        var request = get(optionsPath(id));
        if (actor != null) {
            request.cookie(actor.session());
        }
        return mvc.perform(request);
    }

    private ResultActions slots(Actor actor, Object id, String query) throws Exception {
        var request = get(optionsPath(id) + "/availability" + (query == null ? "" : "?" + query));
        if (actor != null) {
            request.cookie(actor.session());
        }
        return mvc.perform(request);
    }

    private ResultActions slots(UUID id, UUID staff, String date) throws Exception {
        return slots(owner, id, "staffMemberId=" + staff + "&date=" + date);
    }

    private ResultActions slots(UUID staff, String date) throws Exception {
        return slots(appointment, staff, date);
    }

    private static void assertProblem(ResultActions result, int status, String code, String detail) throws Exception {
        result.andExpect(status().is(status));
        JsonNode body = json(result);
        assertThat(body.get("code").asString()).isEqualTo(code);
        if (detail != null) {
            assertThat(body.get("detail").asString()).isEqualTo(detail);
        }
        if (status != 401) {
            assertThat(body.get("instance").asString()).isEqualTo(BASE);
            assertThat(keys(body)).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
        }
        var response = result.andReturn().getResponse();
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }

    private static List<String> starts(JsonNode response) {
        List<String> starts = new ArrayList<>();
        for (JsonNode slot : response.get("slots")) {
            starts.add(slot.get("start").asString());
        }
        return starts;
    }

    private List<String> offered(UUID staff, String date) throws Exception {
        return starts(json(slots(staff, date).andExpect(status().isOk())));
    }

    private UUID second() {
        UUID second = staff(owner.businessId(), "Анна Бойчева", true);
        availability.assign(owner.businessId(), second, tenant.service());
        availability.everyDay(owner.businessId(), second, "09:00", "12:00");
        return second;
    }

    // ---- options: success ------------------------------------------------------------------------

    @Test
    void theOptionsHaveTheExactBodyHeadersAndNoPrivateDatum() throws Exception {
        UUID other = second();

        ResultActions result = options(owner, appointment);

        result.andExpect(status().isOk());
        JsonNode body = json(result);
        assertThat(keys(body)).containsExactly("timezone", "durationMinutes", "currentStaffMember", "staffMembers");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(body.get("durationMinutes").asInt()).isEqualTo(30);
        JsonNode current = body.get("currentStaffMember");
        assertThat(keys(current)).containsExactly("id", "displayName", "available");
        assertThat(current.get("id").asString()).isEqualTo(tenant.staff().toString());
        // The stored snapshot name, as the Appointment detail shows it.
        assertThat(current.get("displayName").asString()).isEqualTo(AppointmentFixtures.STAFF_NAME);
        assertThat(current.get("available").asBoolean()).isTrue();
        assertThat(body.get("staffMembers")).hasSize(1);
        assertThat(keys(body.get("staffMembers").get(0))).containsExactly("id", "displayName");
        assertThat(body.get("staffMembers").get(0).get("id").asString()).isEqualTo(other.toString());
        assertThat(body.get("staffMembers").get(0).get("displayName").asString()).isEqualTo("Анна Бойчева");
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(response.getHeader("Location")).isNull();
        assertThat(body(result)).doesNotContain(CUSTOMER, NOTE, GUEST_NOTE, "version", "price", "serviceId",
                tenant.customer().toString(), appointment.toString());
    }

    @Test
    void theStoredDurationNotTheCurrentServiceDurationsIsReportedAndOffered() throws Exception {
        UUID visit = visit(tenant, NOV_10.plusSeconds(3600), "Първа", "Втора");
        // Services changed after the booking: neither the new durations nor an inactive Service counts.
        jdbc.sql("UPDATE service SET duration_minutes = 15").update();
        jdbc.sql("UPDATE service SET active = false").update();

        JsonNode body = json(options(owner, visit).andExpect(status().isOk()));
        JsonNode slots = json(slots(owner, visit, "staffMemberId=" + tenant.staff() + "&date=" + DAY)
                .andExpect(status().isOk()));

        assertThat(body.get("durationMinutes").asInt()).isEqualTo(60);
        assertThat(slots.get("durationMinutes").asInt()).isEqualTo(60);
        assertThat(slots.get("slots").get(0).get("start").asString()).isEqualTo("2026-11-10T09:30:00+02:00");
        assertThat(slots.get("slots").get(0).get("end").asString()).isEqualTo("2026-11-10T10:30:00+02:00");
        assertThat(body(options(owner, visit))).doesNotContain("Първа", "Втора", "serviceId");
    }

    // ---- options: StaffMember rules -----------------------------------------------------------------

    @Test
    void replacementsAreTheActiveStaffMembersAssignedToEveryStoredServiceAndNeverTheCurrentOne() throws Exception {
        UUID visit = visit(tenant, NOV_10.plusSeconds(3600), "Първа", "Втора");
        List<UUID> services = jdbc.sql("SELECT service_id FROM appointment_service WHERE appointment_id = :id ORDER BY position")
                .param("id", visit).query(UUID.class).list();
        UUID both = staff(owner.businessId(), "Борис Георгиев", true);
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        UUID partial = staff(owner.businessId(), "Частичен", true);
        UUID alsoBoth = staff(owner.businessId(), "Анна Бойчева", true);
        for (UUID service : services) {
            availability.assign(owner.businessId(), both, service);
            availability.assign(owner.businessId(), inactive, service);
            availability.assign(owner.businessId(), alsoBoth, service);
        }
        availability.assign(owner.businessId(), tenant.staff(), services.getLast());
        availability.assign(owner.businessId(), partial, services.getFirst());
        staff(owner.businessId(), "Без услуги", true);

        JsonNode body = json(options(owner, visit).andExpect(status().isOk()));

        List<String> names = new ArrayList<>();
        body.get("staffMembers").forEach(member -> names.add(member.get("displayName").asString()));
        assertThat(names).containsExactly("Анна Бойчева", "Борис Георгиев");
        assertThat(body.get("currentStaffMember").get("id").asString()).isEqualTo(tenant.staff().toString());
    }

    @Test
    void aServiceDeactivatedLaterDoesNotChangeTheReplacements() throws Exception {
        UUID other = second();
        availability.setServiceActive(tenant.service(), false);

        JsonNode body = json(options(owner, appointment).andExpect(status().isOk()));

        assertThat(body.get("staffMembers")).hasSize(1);
        assertThat(body.get("staffMembers").get(0).get("id").asString()).isEqualTo(other.toString());
    }

    @Test
    void aCurrentStaffMemberWhoLostTheAssignmentCanStillBeKeptButNotChosenAsAReplacement() throws Exception {
        availability.unassign(owner.businessId(), tenant.staff(), tenant.service());

        JsonNode body = json(options(owner, appointment).andExpect(status().isOk()));
        assertThat(body.get("currentStaffMember").get("available").asBoolean()).isTrue();
        assertThat(body.get("staffMembers")).isEmpty();

        // A time-only move with the current StaffMember is still offered ...
        assertThat(offered(tenant.staff(), DAY)).contains("2026-11-10T09:15:00+02:00");
        // ... but another StaffMember without the assignment is not an eligible replacement.
        UUID unassigned = staff(owner.businessId(), "Без назначение", true);
        availability.everyDay(owner.businessId(), unassigned, "09:00", "12:00");
        assertProblem(slots(unassigned, DAY), 409, "APPOINTMENT_STAFF_UNAVAILABLE",
                "Членът на екипа не е наличен за тази промяна.");
    }

    @Test
    void anInactiveCurrentStaffMemberIsNotAvailableAndOffersNothingToKeep() throws Exception {
        UUID other = second();
        availability.setStaffMemberActive(tenant.staff(), false);

        JsonNode body = json(options(owner, appointment).andExpect(status().isOk()));
        assertThat(body.get("currentStaffMember").get("available").asBoolean()).isFalse();
        assertThat(body.get("staffMembers").get(0).get("id").asString()).isEqualTo(other.toString());

        assertProblem(slots(tenant.staff(), DAY), 409, "APPOINTMENT_STAFF_UNAVAILABLE", null);
        assertThat(offered(other, DAY)).isNotEmpty();
    }

    @Test
    void aMissingForeignInactiveAndUnassignedStaffMemberAreByteIdentical() throws Exception {
        Actor foreignOwner = api.owner("ACTIVE");
        Tenant foreign = tenant(foreignOwner.businessId(), "ЧУЖД-СЛУЖИТЕЛ-5521", "Чужд клиент");
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        availability.assign(owner.businessId(), inactive, tenant.service());
        UUID unassigned = staff(owner.businessId(), "Без услуги", true);

        List<String> bodies = new ArrayList<>();
        for (UUID candidate : List.of(UUID.randomUUID(), foreign.staff(), inactive, unassigned)) {
            ResultActions result = slots(candidate, DAY);
            assertProblem(result, 409, "APPOINTMENT_STAFF_UNAVAILABLE", "Членът на екипа не е наличен за тази промяна.");
            bodies.add(body(result));
        }

        assertThat(bodies).allMatch(bodies.getFirst()::equals);
        assertThat(bodies.getFirst()).doesNotContain("ЧУЖД", foreign.staff().toString());
    }

    @Test
    void noStaffMemberIsEverChosenAutomatically() throws Exception {
        assertProblem(slots(owner, appointment, "date=" + DAY), 400, "VALIDATION_ERROR", null);
    }

    // ---- availability ---------------------------------------------------------------------------------

    @Test
    void theAvailabilityHasTheExactBodyAndTheAppointmentDoesNotConflictWithItself() throws Exception {
        ResultActions result = slots(tenant.staff(), DAY);

        result.andExpect(status().isOk());
        JsonNode body = json(result);
        assertThat(keys(body)).containsExactly("timezone", "date", "durationMinutes", "slots");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(body.get("date").asString()).isEqualTo(DAY);
        assertThat(body.get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(keys(body.get("slots").get(0))).containsExactly("start", "end");
        // The own 09:00 to 09:30 interval is not busy, so 09:15 and 09:30 overlap only itself and are
        // offered; the unchanged current start itself is not (a no-change request is refused).
        assertThat(starts(body)).containsExactly(
                "2026-11-10T09:15:00+02:00", "2026-11-10T09:30:00+02:00", "2026-11-10T09:45:00+02:00",
                "2026-11-10T10:00:00+02:00", "2026-11-10T10:15:00+02:00", "2026-11-10T10:30:00+02:00",
                "2026-11-10T10:45:00+02:00", "2026-11-10T11:00:00+02:00", "2026-11-10T11:15:00+02:00",
                "2026-11-10T11:30:00+02:00");
        var response = result.andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Set-Cookie")).isNull();
        assertThat(response.getHeader("Location")).isNull();
        assertThat(body(result)).doesNotContain(CUSTOMER, NOTE, GUEST_NOTE, appointment.toString());
    }

    @Test
    void aReplacementIsOfferedTheCurrentStartBecauseThatIsAReassignmentOnly() throws Exception {
        UUID other = second();

        assertThat(offered(other, DAY)).startsWith("2026-11-10T09:00:00+02:00", "2026-11-10T09:15:00+02:00");
    }

    @Test
    void aDifferentOverlappingAppointmentStillBlocksAndAdjacencyIsFree() throws Exception {
        appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 30); // 10:00 to 10:30 local

        List<String> offered = offered(tenant.staff(), DAY);

        assertThat(offered).contains("2026-11-10T09:15:00+02:00", "2026-11-10T09:30:00+02:00", "2026-11-10T10:30:00+02:00")
                .doesNotContain("2026-11-10T09:45:00+02:00", "2026-11-10T10:00:00+02:00", "2026-11-10T10:15:00+02:00");
    }

    @Test
    void aCancelledAppointmentAndAnotherStaffMembersAppointmentDoNotBlock() throws Exception {
        UUID other = second();
        appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 30, AppointmentFixtures::cancelled);
        appointment(withStaff(tenant, other), Instant.parse("2026-11-10T08:30:00Z"), 30);

        assertThat(offered(tenant.staff(), DAY)).contains("2026-11-10T10:00:00+02:00", "2026-11-10T10:30:00+02:00");
    }

    @Test
    void noMinimumNoticeIsAppliedAndTheFirstStartIsTheClockInstantOnTheGrid() throws Exception {
        // A later clock outdates the session of the default owner, so a new owner is created at that time.
        Instant late = Instant.parse("2026-11-10T07:30:00Z");
        clock.set(late);
        Actor lateOwner = new BusinessCustomerApiTestSupport(mvc, jdbc, tokens, late).owner("ACTIVE");
        Tenant lateTenant = tenant(lateOwner.businessId(), "Мария Петрова", CUSTOMER);
        availability.everyDay(lateOwner.businessId(), lateTenant.staff(), "09:00", "12:00");
        UUID booked = appointment(lateTenant, Instant.parse("2026-11-10T09:00:00Z"), 30);
        String query = "staffMemberId=" + lateTenant.staff() + "&date=" + DAY;

        // The public two-hour notice would first offer 09:30 UTC; the Business side offers the instant.
        List<String> offered = starts(json(slots(lateOwner, booked, query).andExpect(status().isOk())));
        assertThat(offered).first().isEqualTo("2026-11-10T09:30:00+02:00");
        assertThat(offered).contains("2026-11-10T11:15:00+02:00");

        clock.set(Instant.parse("2026-11-10T07:37:00Z"));
        assertThat(starts(json(slots(lateOwner, booked, query).andExpect(status().isOk()))))
                .first().isEqualTo("2026-11-10T09:45:00+02:00");
    }

    @Test
    void aPastDateOffersNothing() throws Exception {
        assertThat(json(slots(tenant.staff(), "2026-09-30").andExpect(status().isOk())).get("slots")).isEmpty();
    }

    @Test
    void theWorkingScheduleBoundsTheDayAndADayOffIsEmpty() throws Exception {
        jdbc.sql("DELETE FROM staff_working_period WHERE staff_member_id = :id").param("id", tenant.staff()).update();

        assertThat(json(slots(tenant.staff(), DAY).andExpect(status().isOk())).get("slots")).isEmpty();
    }

    @Test
    void bothOccurrencesOfARepeatedLocalTimeAreOfferedWithTheirOffsets() throws Exception {
        Instant late = Instant.parse("2026-10-20T00:00:00Z");
        clock.set(late);
        Actor lateOwner = new BusinessCustomerApiTestSupport(mvc, jdbc, tokens, late).owner("ACTIVE");
        Tenant lateTenant = tenant(lateOwner.businessId(), "Мария Петрова", CUSTOMER);
        availability.everyDay(lateOwner.businessId(), lateTenant.staff(), "02:00", "06:00");
        UUID booked = appointment(lateTenant, Instant.parse("2026-10-26T07:00:00Z"), 30);

        List<String> offered = starts(json(slots(lateOwner, booked,
                "staffMemberId=" + lateTenant.staff() + "&date=2026-10-25").andExpect(status().isOk())));

        assertThat(offered).contains(
                "2026-10-25T03:30:00+03:00", "2026-10-25T03:30:00+02:00",
                "2026-10-25T03:00:00+03:00", "2026-10-25T03:00:00+02:00");
        assertThat(offered).doesNotHaveDuplicates();
        List<Instant> instants = offered.stream().map(java.time.OffsetDateTime::parse)
                .map(java.time.OffsetDateTime::toInstant).toList();
        assertThat(instants).isSorted();
    }

    @Test
    void aSpringGapHasNoStartInTheHourThatDoesNotExist() throws Exception {
        // The clock precedes the default fixtures, so the Appointment is created even earlier than it.
        UUID early = appointment(tenant, Instant.parse("2026-04-01T07:00:00Z"), 30, row -> {
            row.put("created_at", AppointmentFixtures.utc(Instant.parse("2026-02-01T00:00:00Z")));
            row.put("updated_at", AppointmentFixtures.utc(Instant.parse("2026-02-01T00:00:00Z")));
        });
        clock.set(Instant.parse("2026-03-01T00:00:00Z"));
        availability.everyDay(owner.businessId(), tenant.staff(), "02:00", "05:00");

        List<String> offered = starts(json(slots(early, tenant.staff(), "2026-03-29").andExpect(status().isOk())));

        assertThat(offered).isNotEmpty();
        assertThat(offered).noneMatch(start -> start.startsWith("2026-03-29T03:"));
        assertThat(offered).contains("2026-03-29T02:30:00+02:00", "2026-03-29T04:00:00+03:00");
    }

    // ---- state ----------------------------------------------------------------------------------------

    private List<ResultActions> everyRoute(Actor actor, UUID id) throws Exception {
        return List.of(options(actor, id), slots(actor, id, "staffMemberId=" + tenant.staff() + "&date=" + DAY));
    }

    @Test
    void aCancelledAndAStartedAppointmentExposeNoOptions() throws Exception {
        UUID cancelled = appointment(tenant, NOV_10.plusSeconds(7200), 30, AppointmentFixtures::cancelled);
        UUID started = appointment(tenant, Instant.parse("2026-10-02T07:00:00Z"), 30);
        UUID starting = appointment(tenant, Instant.parse("2026-10-02T08:00:00Z"), 30); // exactly the clock

        for (UUID id : List.of(cancelled, started, starting)) {
            for (ResultActions result : everyRoute(owner, id)) {
                assertProblem(result, 409, "APPOINTMENT_STATE_CONFLICT",
                        "Часът не може да бъде променен, защото е отменен или вече е започнал.");
                assertThat(body(result)).doesNotContain("slots", "staffMembers", "durationMinutes");
            }
        }
    }

    @Test
    void anAppointmentThatStartsOneMicrosecondLaterCanStillBeMoved() throws Exception {
        UUID later = appointment(tenant, Instant.parse("2026-10-02T08:00:00.000001Z"), 30);

        options(owner, later).andExpect(status().isOk());
    }

    @Test
    void aMissingAndAForeignAppointmentAreByteIdentical() throws Exception {
        Actor foreignOwner = api.owner("ACTIVE");
        Tenant foreign = tenant(foreignOwner.businessId(), "ЧУЖД-СЛУЖИТЕЛ-5521", "Чужд клиент");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30);

        List<String> bodies = new ArrayList<>();
        for (UUID id : List.of(UUID.randomUUID(), foreignAppointment)) {
            for (ResultActions result : everyRoute(owner, id)) {
                assertProblem(result, 404, "APPOINTMENT_NOT_FOUND", "Часът не е намерен.");
                bodies.add(body(result));
            }
        }

        assertThat(bodies).allMatch(bodies.getFirst()::equals);
        assertThat(bodies.getFirst()).doesNotContain(foreignAppointment.toString(), "ЧУЖД");
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------

    @Test
    void onlyAnActiveBusinessOffersChoices() throws Exception {
        Actor draft = api.owner("DRAFT");
        Tenant draftTenant = tenant(draft.businessId(), "Служител", "Клиент");
        UUID draftAppointment = appointment(draftTenant, NOV_10, 30);
        Actor suspended = api.owner("SUSPENDED");
        Tenant suspendedTenant = tenant(suspended.businessId(), "Служител", "Клиент");
        UUID suspendedAppointment = appointment(suspendedTenant, NOV_10, 30);

        for (ResultActions result : List.of(
                options(draft, draftAppointment),
                slots(draft, draftAppointment, "staffMemberId=" + draftTenant.staff() + "&date=" + DAY))) {
            assertProblem(result, 409, "BUSINESS_NOT_ACTIVE",
                    "Бизнесът не е активен. Часовете могат да се променят само в активен бизнес.");
        }
        for (ResultActions result : List.of(
                options(suspended, suspendedAppointment),
                slots(suspended, suspendedAppointment, "staffMemberId=" + suspendedTenant.staff() + "&date=" + DAY))) {
            assertProblem(result, 409, "BUSINESS_SUSPENDED", "Спрян бизнес може само да преглежда данните си.");
        }
    }

    // ---- authentication, roles, isolation ------------------------------------------------------------------

    @Test
    void anonymousRequestsAreUnauthenticated() throws Exception {
        for (ResultActions result : everyRoute(null, appointment)) {
            assertProblem(result, 401, "AUTH_REQUIRED", null);
        }
    }

    @Test
    void anAbsentBusinessSelectionIsRequired() throws Exception {
        Actor noSelection = api.actor("ACTIVE", "BUSINESS_OWNER", true, false, false);
        for (ResultActions result : everyRoute(noSelection, appointment)) {
            assertProblem(result, 403, "ACTIVE_BUSINESS_REQUIRED", null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void managerAndStaffAreDenied(String role) throws Exception {
        Actor denied = api.actor("ACTIVE", role, true, false, true);
        for (ResultActions result : everyRoute(denied, appointment)) {
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
            for (ResultActions result : everyRoute(actor, appointment)) {
                assertProblem(result, 403, "ACTIVE_BUSINESS_REQUIRED", null);
            }
        }
    }

    @Test
    void aPlatformAdministratorWhoIsAlsoAnOwnerIsAllowedAsAnOwner() throws Exception {
        Actor both = api.actor("ACTIVE", "BUSINESS_OWNER", true, true, true);
        Tenant data = tenant(both.businessId(), "Служител", "Клиент");
        UUID own = appointment(data, NOV_10, 30);

        options(both, own).andExpect(status().isOk());
    }

    @Test
    void aClientSuppliedBusinessDurationServiceOrIgnoredAppointmentIsNeverTrusted() throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "ЧУЖД-СЛУЖИТЕЛ-5521", "Чужд клиент");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30);
        String forged = "&businessId=" + other.businessId() + "&durationMinutes=240&duration=240&price=1"
                + "&serviceId=" + UUID.randomUUID() + "&ignoredAppointmentId=" + foreignAppointment
                + "&ignoreAppointmentId=" + foreignAppointment + "&appointmentId=" + foreignAppointment;
        String query = "staffMemberId=" + tenant.staff() + "&date=" + DAY;
        // A second, different Appointment of the same StaffMember must stay busy whatever is forged.
        appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 30);

        String plain = body(slots(owner, appointment, query).andExpect(status().isOk()));
        String forgedBody = body(slots(owner, appointment, query + forged).andExpect(status().isOk()));
        String plainOptions = body(options(owner, appointment).andExpect(status().isOk()));
        String forgedOptions = body(mvc.perform(get(optionsPath(appointment) + "?" + forged.substring(1))
                .cookie(owner.session())).andExpect(status().isOk()));

        assertThat(forgedBody).isEqualTo(plain);
        assertThat(forgedOptions).isEqualTo(plainOptions);
        assertThat(plain).contains("\"durationMinutes\":30").doesNotContain("\"start\":\"2026-11-10T10:00:00+02:00\"");
        assertThat(plainOptions).doesNotContain("ЧУЖД");
    }

    // ---- request shape ------------------------------------------------------------------------------------

    @Test
    void everyMalformedRequestIsAGenericValidationErrorThatEchoesNothing() throws Exception {
        UUID staff = tenant.staff();
        for (String id : List.of("not-a-uuid", appointment.toString().replace("-", ""),
                "+" + appointment.toString().substring(1), appointment + "0", "1")) {
            for (ResultActions result : List.of(options(owner, id), slots(owner, id, "staffMemberId=" + staff + "&date=" + DAY))) {
                assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
                assertThat(body(result)).doesNotContain("not-a-uuid").doesNotContain(appointment.toString());
            }
        }
        String base = "staffMemberId=" + staff;
        List<String> badSlots = List.of(
                "",
                base,
                base + "&date=",
                base + "&date=2026-13-01",
                base + "&date=2026-02-30",
                base + "&date=2026-1-1",
                base + "&date=20261110",
                base + "&date=1999-12-31",
                base + "&date=2101-01-01",
                base + "&date=" + DAY + "&date=" + DAY,
                "staffMemberId=x&date=" + DAY,
                "staffMemberId=" + staff.toString().replace("-", "") + "&date=" + DAY,
                "staffMemberId=" + staff + "&staffMemberId=" + staff + "&date=" + DAY,
                "staffMemberId=&date=" + DAY,
                "date=" + DAY);
        for (String query : badSlots) {
            assertProblem(slots(owner, appointment, query), 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        }
        // An upper-case canonical identifier is the same identifier; unknown parameters are ignored,
        // as on every other read of the private calendar API.
        options(owner, appointment.toString().toUpperCase()).andExpect(status().isOk());
        slots(owner, appointment, base + "&date=" + DAY + "&unknown=1").andExpect(status().isOk());
    }

    @Test
    void theLatestAndEarliestSupportedDatesAreAccepted() throws Exception {
        slots(tenant.staff(), "2000-01-01").andExpect(status().isOk());
        slots(tenant.staff(), "2100-12-31").andExpect(status().isOk());
    }

    // ---- headers, verbs, privacy -----------------------------------------------------------------------------

    @Test
    void readsNeedNoCsrfTokenAndEveryOtherVerbIsRefusedWithoutWriting() throws Exception {
        for (ResultActions result : everyRoute(owner, appointment)) {
            result.andExpect(status().isOk());
            assertThat(result.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
        }
        long before = jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
        String version = jdbc.sql("SELECT version || ':' || updated_at FROM appointment WHERE id = :id")
                .param("id", appointment).query(String.class).single();
        for (String path : List.of(optionsPath(appointment), optionsPath(appointment) + "/availability")) {
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
        assertThat(jdbc.sql("SELECT version || ':' || updated_at FROM appointment WHERE id = :id")
                .param("id", appointment).query(String.class).single()).isEqualTo(version);
    }

    @Test
    void noCustomerContactNoteOrForeignDatumIsInAnyResponseProblemOrLog(CapturedOutput output) throws Exception {
        Actor other = api.owner("ACTIVE");
        Tenant foreign = tenant(other.businessId(), "ЧУЖД-СЛУЖИТЕЛ-5521", "Чужд клиент");
        UUID foreignAppointment = appointment(foreign, NOV_10, 30);
        List<String> bodies = new ArrayList<>();
        for (ResultActions result : everyRoute(owner, appointment)) {
            bodies.add(body(result));
        }
        bodies.add(body(options(owner, "bad")));
        bodies.add(body(options(owner, foreignAppointment)));
        bodies.add(body(slots(owner, appointment, "staffMemberId=" + UUID.randomUUID() + "&date=" + DAY)));
        bodies.add(body(slots(owner, appointment, "staffMemberId=" + foreign.staff() + "&date=" + DAY)));

        for (String raw : bodies) {
            assertThat(raw).doesNotContain(CUSTOMER, "СЕНТИНЕЛ", "@example.test", "+359", tenant.customer().toString(),
                    owner.businessId().toString(), foreignAppointment.toString(), "ЧУЖД", "SELECT", "constraint",
                    "Exception", "SQLState");
        }
        assertThat(output.getAll()).doesNotContain("СЕНТИНЕЛ", CUSTOMER, "@example.test", "+359", "SQLState");
    }
}
