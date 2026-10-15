package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The four private read routes against real PostgreSQL: exact success key sets, field values and
 * formats, the overlap semantics at both edges, deterministic ordering, the inactive-StaffMember
 * rule, working-time facts, daylight-saving dates, paging, and window validation.
 */
class BusinessCalendarReadApiIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-5521";
    private static final String GUEST_NOTE = "СЕНТИНЕЛ-ГОСТ-БЕЛЕЖКА-8830";
    private static final String DAY = "from=2026-11-10&to=2026-11-10";

    @Autowired ScheduleExceptionStore exceptions;

    private Actor owner;
    private Tenant tenant;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        tenant = tenant(owner.businessId(), "Мария Петрова", "Анна Иванова");
        availability.everyDay(owner.businessId(), tenant.staff(), "09:00", "18:00");
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    // ---- exact shapes ----------------------------------------------------------------------------

    @Test
    void theCalendarHasExactlyTheApprovedKeysAndFormats() throws Exception {
        UUID id = appointment(tenant, NOV_10.plusSeconds(3600), 45, row -> {
            row.put("internal_note", NOTE);
            row.put("customer_note", GUEST_NOTE);
        });

        var result = calendar(owner, DAY).andExpect(status().isOk());
        JsonNode body = json(result);
        String raw = body(result);

        assertThat(keys(body)).containsExactly(
                "businessStatus", "timezone", "from", "to", "days", "staffMembers", "appointments");
        assertThat(body.get("businessStatus").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(body.get("from").asString()).isEqualTo("2026-11-10");
        assertThat(body.get("to").asString()).isEqualTo("2026-11-10");
        JsonNode day = body.get("days").get(0);
        assertThat(keys(day)).containsExactly("date", "start", "end");
        assertThat(day.get("start").asString()).isEqualTo("2026-11-10T00:00:00+02:00");
        assertThat(day.get("end").asString()).isEqualTo("2026-11-11T00:00:00+02:00");
        JsonNode staff = body.get("staffMembers").get(0);
        assertThat(keys(staff)).containsExactly("id", "displayName", "active", "workingIntervals", "closures");
        assertThat(keys(staff.get("workingIntervals").get(0))).containsExactly("start", "end");
        assertThat(staff.get("workingIntervals").get(0).get("start").asString())
                .isEqualTo("2026-11-10T09:00:00+02:00");
        JsonNode row = body.get("appointments").get(0);
        assertThat(keys(row)).containsExactly(
                "id", "status", "source", "start", "end", "staffMemberId", "staffDisplayName",
                "customerId", "customerDisplayName", "durationMinutes", "services");
        assertThat(row.get("id").asString()).isEqualTo(id.toString());
        assertThat(row.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(row.get("source").asString()).isEqualTo("MANUAL");
        assertThat(row.get("start").asString()).isEqualTo("2026-11-10T10:00:00+02:00");
        assertThat(row.get("end").asString()).isEqualTo("2026-11-10T10:45:00+02:00");
        assertThat(row.get("customerDisplayName").asString()).isEqualTo("Анна Иванова");
        assertThat(keys(row.get("services").get(0))).containsExactly("name", "durationMinutes");
        // The private facts never appear in a calendar row.
        assertThat(raw).doesNotContain(NOTE, GUEST_NOTE, "internalNote", "customerNote", "cancel",
                "reference", "phone", "email", "version", "price", "booking_attempt", "fingerprint");
        assertThat(raw).doesNotContain("@example.test", "+359888");
    }

    @Test
    void theAgendaHasExactlyTheApprovedKeysAndTheSameRows() throws Exception {
        appointment(tenant, NOV_10, 30);

        var result = agenda(owner, DAY).andExpect(status().isOk());
        JsonNode body = json(result);

        assertThat(keys(body)).containsExactly(
                "businessStatus", "timezone", "from", "to", "items", "page", "size", "total");
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(10);
        assertThat(body.get("total").asLong()).isEqualTo(1);
        assertThat(keys(body.get("items").get(0))).containsExactly(
                "id", "status", "source", "start", "end", "staffMemberId", "staffDisplayName",
                "customerId", "customerDisplayName", "durationMinutes", "services");
    }

    @Test
    void theDetailHasExactlyTheApprovedKeysWithTheOwnerOnlyFactsAndExactPrices() throws Exception {
        UUID id = appointment(tenant, NOV_10, 45, row -> {
            row.put("internal_note", NOTE);
            row.put("customer_note", GUEST_NOTE);
            row.put("price_eur", new BigDecimal("37.50"));
        });

        var result = detail(owner, id).andExpect(status().isOk());
        JsonNode body = json(result);
        String raw = body(result);

        assertThat(keys(body)).containsExactly(
                "businessStatus", "timezone", "id", "reference", "status", "source", "start", "end",
                "staffMemberId", "staffDisplayName", "customer", "services", "durationMinutes", "price",
                "customerNote", "internalNote", "cancellation", "version", "createdAt", "updatedAt");
        assertThat(keys(body.get("customer"))).containsExactly("id", "displayName", "phone", "email");
        assertThat(body.get("customer").get("displayName").asString()).isEqualTo("Анна Иванова");
        assertThat(keys(body.get("services").get(0))).containsExactly("position", "name", "durationMinutes", "price");
        assertThat(body.get("internalNote").asString()).isEqualTo(NOTE);
        assertThat(body.get("customerNote").asString()).isEqualTo(GUEST_NOTE);
        assertThat(body.get("cancellation").isNull()).isTrue();
        assertThat(body.get("version").asLong()).isZero();
        assertThat(body.get("reference").asString()).hasSize(10);
        assertThat(raw).contains("\"price\":37.50").contains("\"price\":37.50");
        assertThat(body.get("start").asString()).isEqualTo("2026-11-10T09:00:00+02:00");
        assertThat(raw).doesNotContain("booking_attempt", "fingerprint", "attemptHash", "requestFingerprint");
    }

    @Test
    void aBusinessCancelledDetailCarriesTheAttributionAndALegacyOneAnUnknownActorWithoutATime() throws Exception {
        UUID business = appointment(tenant, NOV_10, 30);
        UUID legacy = appointment(tenant, NOV_10.plusSeconds(7200), 30);
        fixtures.cancel(business);
        fixtures.cancelLegacy(legacy);

        JsonNode cancelled = json(detail(owner, business));
        JsonNode unknown = json(detail(owner, legacy));

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(keys(cancelled.get("cancellation"))).containsExactly("cancelledAt", "cancelledBy");
        assertThat(cancelled.get("cancellation").get("cancelledBy").asString()).isEqualTo("BUSINESS");
        assertThat(cancelled.get("cancellation").get("cancelledAt").asString()).isEqualTo("2026-10-01T11:00:00+03:00");
        assertThat(unknown.get("cancellation").get("cancelledBy").asString()).isEqualTo("UNKNOWN");
        assertThat(unknown.get("cancellation").get("cancelledAt").isNull()).isTrue();
    }

    @Test
    void cancelledAppointmentsStayVisibleAndAreMarkedInTheCalendarAndTheAgenda() throws Exception {
        UUID confirmed = appointment(tenant, NOV_10, 30);
        UUID cancelled = appointment(tenant, NOV_10.plusSeconds(3600), 30);
        fixtures.cancel(cancelled);

        JsonNode calendar = json(calendar(owner, DAY)).get("appointments");
        JsonNode agenda = json(agenda(owner, DAY)).get("items");

        assertThat(ids(calendar)).containsExactly(confirmed.toString(), cancelled.toString());
        assertThat(calendar.get(0).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(calendar.get(1).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(ids(agenda)).isEqualTo(ids(calendar));
        assertThat(body(calendar(owner, DAY))).doesNotContain("cancelledBy", "UNKNOWN", "BUSINESS");
    }

    @Test
    void oneToFiveServiceLinesAreReturnedInStoredPositionOrder() throws Exception {
        UUID one = visit(tenant, NOV_10, "Първа");
        UUID five = visit(tenant, NOV_10.plusSeconds(4 * 3600L), "А", "Б", "В", "Г", "Д");

        JsonNode rows = json(calendar(owner, DAY)).get("appointments");
        JsonNode detail = json(detail(owner, five));

        assertThat(rows.get(0).get("id").asString()).isEqualTo(one.toString());
        assertThat(rows.get(0).get("services")).hasSize(1);
        assertThat(rows.get(1).get("services").size()).isEqualTo(5);
        List<String> names = new ArrayList<>();
        rows.get(1).get("services").forEach(service -> names.add(service.get("name").asString()));
        assertThat(names).containsExactly("А", "Б", "В", "Г", "Д");
        assertThat(rows.get(1).get("durationMinutes").asInt()).isEqualTo(150);
        assertThat(detail.get("services").get(4).get("position").asInt()).isEqualTo(5);
        assertThat(detail.get("price").decimalValue()).isEqualByComparingTo("50.00");
    }

    // ---- overlap semantics -----------------------------------------------------------------------

    @Test
    void anAppointmentIsIncludedWhenItsOccupiedTimeOverlapsTheWindowAtEitherEdgeAndNotWhenItOnlyTouches() throws Exception {
        // The window 2026-11-10 is [2026-11-09T22:00Z, 2026-11-10T22:00Z) in Sofia (UTC+2).
        Instant from = Instant.parse("2026-11-09T22:00:00Z");
        Instant to = Instant.parse("2026-11-10T22:00:00Z");
        // Each Appointment has its own StaffMember, so the exclusion constraint never interferes.
        UUID touchesStart = appointment(separate(), from.minusSeconds(30 * 60L), 30);
        UUID straddlesStart = appointment(separate(), from.minusSeconds(15 * 60L), 30);
        UUID inside = appointment(separate(), from.plusSeconds(6 * 3600L), 30);
        UUID straddlesEnd = appointment(separate(), to.minusSeconds(15 * 60L), 30);
        UUID touchesEnd = appointment(separate(), to, 30);
        UUID longBefore = appointment(separate(), from.minusSeconds(479 * 60L), 480);    // ends 1 minute after from
        UUID longJustBefore = appointment(separate(), from.minusSeconds(480 * 60L), 480); // ends exactly at from

        List<String> ids = ids(json(calendar(owner, DAY)).get("appointments"));

        assertThat(ids).containsExactlyInAnyOrder(
                straddlesStart.toString(), inside.toString(), straddlesEnd.toString(), longBefore.toString());
        assertThat(ids).doesNotContain(touchesStart.toString(), touchesEnd.toString(), longJustBefore.toString());
        assertThat(ids(json(agenda(owner, DAY)).get("items"))).containsExactlyElementsOf(ids);
    }

    @Test
    void theOrderIsStartThenIdentifierEvenWhenTheStartsAreEqual() throws Exception {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID middle = UUID.fromString("7fffffff-0000-0000-0000-000000000000");
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
        UUID second = staff(owner.businessId(), "Втори", true);
        Tenant other = withStaff(tenant, second);
        // Written in the opposite order of the expected result; equal start instants.
        appointment(other, NOV_10, 30, row -> row.put("id", high));
        appointment(tenant, NOV_10, 30, row -> row.put("id", low));
        appointment(withStaff(tenant, staff(owner.businessId(), "Трети", true)), NOV_10, 30,
                row -> row.put("id", middle));
        appointment(tenant, NOV_10.minusSeconds(3600), 30);

        List<String> calendarIds = ids(json(calendar(owner, DAY)).get("appointments"));

        assertThat(calendarIds.subList(1, 4)).containsExactly(low.toString(), middle.toString(), high.toString());
        assertThat(ids(json(agenda(owner, DAY)).get("items"))).isEqualTo(calendarIds);
    }

    private Tenant separate() {
        return withStaff(tenant, staff(owner.businessId(), "Служител " + UUID.randomUUID(), true));
    }

    // ---- StaffMembers -----------------------------------------------------------------------------

    @Test
    void theUnfilteredCalendarListsEveryActiveStaffMemberAndAnInactiveOneOnlyWithAnAppointmentInTheRange() throws Exception {
        UUID activeIdle = staff(owner.businessId(), "Активен без часове", true);
        UUID inactiveBusy = staff(owner.businessId(), "Неактивен с час", false);
        UUID inactiveIdle = staff(owner.businessId(), "Неактивен без час", false);
        UUID inactiveElsewhere = staff(owner.businessId(), "Неактивен друг ден", false);
        appointment(withStaff(tenant, inactiveBusy), NOV_10, 30);
        appointment(withStaff(tenant, inactiveElsewhere), NOV_10.plusSeconds(3 * 86_400L), 30);

        JsonNode staff = json(calendar(owner, DAY)).get("staffMembers");
        List<String> listed = ids(staff);

        assertThat(listed).contains(tenant.staff().toString(), activeIdle.toString(), inactiveBusy.toString());
        assertThat(listed).doesNotContain(inactiveIdle.toString(), inactiveElsewhere.toString());
        for (JsonNode member : staff) {
            if (member.get("id").asString().equals(inactiveBusy.toString())) {
                assertThat(member.get("active").asBoolean()).isFalse();
                assertThat(member.get("workingIntervals")).isEmpty();
                assertThat(member.get("closures")).isEmpty();
            }
        }
        // The agenda, which has no StaffMember list, still shows the inactive person's Appointment.
        assertThat(json(agenda(owner, DAY)).get("total").asLong()).isEqualTo(1);
        // Out of the range the same inactive person is not listed.
        assertThat(ids(json(calendar(owner, "from=2026-11-20&to=2026-11-20")).get("staffMembers")))
                .doesNotContain(inactiveBusy.toString());
    }

    @Test
    void anActiveStaffMemberWithoutAppointmentsIsListedWithItsWorkingTime() throws Exception {
        JsonNode body = json(calendar(owner, DAY));

        assertThat(body.get("appointments")).isEmpty();
        assertThat(ids(body.get("staffMembers"))).containsExactly(tenant.staff().toString());
        assertThat(body.get("staffMembers").get(0).get("workingIntervals")).hasSize(1);
    }

    @Test
    void anExplicitFilterMayNameAnInactiveSameBusinessStaffMemberAndShowsOnlyThatPerson() throws Exception {
        UUID inactive = staff(owner.businessId(), "Неактивен", false);
        appointment(withStaff(tenant, inactive), NOV_10, 30);
        UUID mine = appointment(tenant, NOV_10.plusSeconds(3600), 30);
        UUID idleInactive = staff(owner.businessId(), "Неактивен без час", false);

        JsonNode filtered = json(calendar(owner, DAY + "&staffMemberId=" + inactive));
        JsonNode idle = json(calendar(owner, DAY + "&staffMemberId=" + idleInactive));
        JsonNode active = json(calendar(owner, DAY + "&staffMemberId=" + tenant.staff()));

        assertThat(ids(filtered.get("staffMembers"))).containsExactly(inactive.toString());
        assertThat(filtered.get("appointments")).hasSize(1);
        assertThat(filtered.get("appointments").get(0).get("staffMemberId").asString()).isEqualTo(inactive.toString());
        assertThat(ids(idle.get("staffMembers"))).containsExactly(idleInactive.toString());
        assertThat(idle.get("appointments")).isEmpty();
        assertThat(ids(active.get("appointments"))).containsExactly(mine.toString());
        assertThat(json(agenda(owner, DAY + "&staffMemberId=" + inactive)).get("total").asLong()).isEqualTo(1);
    }

    @Test
    void aMissingAndAForeignStaffMemberFilterAreByteIdenticalNotFoundResponses() throws Exception {
        Actor foreignOwner = api.owner("ACTIVE");
        UUID foreignStaff = staff(foreignOwner.businessId(), "Чужд", true);
        String path = DAY + "&staffMemberId=";

        for (boolean agenda : List.of(false, true)) {
            var missing = agenda ? agenda(owner, path + UUID.randomUUID()) : calendar(owner, path + UUID.randomUUID());
            var foreign = agenda ? agenda(owner, path + foreignStaff) : calendar(owner, path + foreignStaff);
            missing.andExpect(status().isNotFound());
            assertThat(body(foreign)).isEqualTo(body(missing));
            assertThat(json(missing).get("code").asString()).isEqualTo("STAFF_MEMBER_NOT_FOUND");
            assertThat(json(missing).get("detail").asString()).isEqualTo("Членът на екипа не е намерен.");
        }
    }

    // ---- working-time facts ----------------------------------------------------------------------

    @Test
    void partialExceptionsAreNetIntervalsAndOnlyFullDayExceptionsAreMarkers() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 10);
        exceptions.insert(new NewScheduleException(UUID.randomUUID(), owner.businessId(),
                ScheduleExceptionContent.staffTimeOffPartial(
                        tenant.staff(), day, List.of(new LocalPeriod(LocalTime.of(12, 0), LocalTime.of(13, 0)))),
                NOW));
        exceptions.insert(new NewScheduleException(UUID.randomUUID(), owner.businessId(),
                ScheduleExceptionContent.businessClosureDays(day.plusDays(1), day.plusDays(1)), NOW));
        exceptions.insert(new NewScheduleException(UUID.randomUUID(), owner.businessId(),
                ScheduleExceptionContent.staffTimeOffDays(tenant.staff(), day.plusDays(2), day.plusDays(2)), NOW));

        JsonNode staff = json(calendar(owner, "from=2026-11-10&to=2026-11-12")).get("staffMembers").get(0);

        assertThat(staff.get("workingIntervals")).hasSize(2);
        assertThat(staff.get("workingIntervals").get(0).get("end").asString()).isEqualTo("2026-11-10T12:00:00+02:00");
        assertThat(staff.get("workingIntervals").get(1).get("start").asString()).isEqualTo("2026-11-10T13:00:00+02:00");
        assertThat(staff.get("closures")).hasSize(2);
        assertThat(staff.get("closures").get(0).get("date").asString()).isEqualTo("2026-11-11");
        assertThat(staff.get("closures").get(0).get("kind").asString()).isEqualTo("BUSINESS_CLOSURE");
        assertThat(staff.get("closures").get(1).get("kind").asString()).isEqualTo("STAFF_TIME_OFF");
    }

    @Test
    void theFallBackDayIsTwentyFiveHoursAndBothRepeatedHoursAreListedWithDistinctOffsets() throws Exception {
        // Sofia moves from +03:00 to +02:00 at 01:00Z on 2026-10-25: 03:00-04:00 happens twice.
        Instant firstThreeThirty = Instant.parse("2026-10-25T00:30:00Z");
        Instant secondThreeThirty = Instant.parse("2026-10-25T01:30:00Z");
        UUID first = appointment(tenant, firstThreeThirty, 15);
        UUID second = appointment(tenant, secondThreeThirty, 15);

        JsonNode body = json(calendar(owner, "from=2026-10-25&to=2026-10-25"));

        assertThat(body.get("days").get(0).get("start").asString()).isEqualTo("2026-10-25T00:00:00+03:00");
        assertThat(body.get("days").get(0).get("end").asString()).isEqualTo("2026-10-26T00:00:00+02:00");
        assertThat(Duration.between(
                Instant.parse("2026-10-24T21:00:00Z"), Instant.parse("2026-10-25T22:00:00Z")).toHours()).isEqualTo(25);
        JsonNode rows = body.get("appointments");
        assertThat(ids(rows)).containsExactly(first.toString(), second.toString());
        assertThat(rows.get(0).get("start").asString()).isEqualTo("2026-10-25T03:30:00+03:00");
        assertThat(rows.get(1).get("start").asString()).isEqualTo("2026-10-25T03:30:00+02:00");
    }

    @Test
    void theSpringForwardDayIsTwentyThreeHoursAndAnAppointmentAfterTheGapKeepsItsOffset() throws Exception {
        // Sofia moves from +02:00 to +03:00 at 01:00Z on 2027-03-28; 03:00-04:00 does not exist.
        UUID after = appointment(tenant, Instant.parse("2027-03-28T01:00:00Z"), 30);

        JsonNode body = json(calendar(owner, "from=2027-03-28&to=2027-03-28"));

        assertThat(body.get("days").get(0).get("start").asString()).isEqualTo("2027-03-28T00:00:00+02:00");
        assertThat(body.get("days").get(0).get("end").asString()).isEqualTo("2027-03-29T00:00:00+03:00");
        assertThat(body.get("appointments").get(0).get("id").asString()).isEqualTo(after.toString());
        assertThat(body.get("appointments").get(0).get("start").asString()).isEqualTo("2027-03-28T04:00:00+03:00");
    }

    // ---- paging -----------------------------------------------------------------------------------

    @Test
    void theAgendaPagesInFixedOrderAndAPageBeyondTheEndIsEmptyWithTheRealTotal() throws Exception {
        List<String> expected = new ArrayList<>();
        UUID second = staff(owner.businessId(), "Втори", true);
        for (int index = 0; index < 12; index++) {
            expected.add(appointment(withStaff(tenant, index % 2 == 0 ? tenant.staff() : second),
                    NOV_10.plusSeconds(index * 1800L), 30).toString());
        }

        JsonNode first = json(agenda(owner, DAY + "&page=0&size=10"));
        JsonNode next = json(agenda(owner, DAY + "&page=1&size=10"));
        JsonNode beyond = json(agenda(owner, DAY + "&page=7&size=10"));
        JsonNode large = json(agenda(owner, DAY + "&size=25"));

        assertThat(ids(first.get("items"))).containsExactlyElementsOf(expected.subList(0, 10));
        assertThat(ids(next.get("items"))).containsExactlyElementsOf(expected.subList(10, 12));
        assertThat(first.get("total").asLong()).isEqualTo(12);
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("total").asLong()).isEqualTo(12);
        assertThat(beyond.get("page").asInt()).isEqualTo(7);
        assertThat(ids(large.get("items"))).containsExactlyElementsOf(expected);
        assertThat(json(agenda(owner, DAY + "&size=50")).get("size").asInt()).isEqualTo(50);
    }

    @Test
    void invalidPageAndSizeAreGenericValidationErrors() throws Exception {
        for (String query : List.of("&page=-1", "&size=11", "&size=0", "&size=100", "&page=x", "&size=x")) {
            var result = agenda(owner, DAY + query).andExpect(status().isBadRequest());
            assertThat(json(result).get("code").asString()).isEqualTo("VALIDATION_ERROR");
            assertThat(body(result)).doesNotContain("page", "size").doesNotContain("fieldErrors");
        }
    }

    // ---- window validation ----------------------------------------------------------------------

    @Test
    void aWindowHoldsAtMostThirtyOneDatesAndEveryMalformedWindowIsAGenericValidationError() throws Exception {
        calendar(owner, "from=2026-11-01&to=2026-12-01").andExpect(status().isOk());
        agenda(owner, "from=2026-11-01&to=2026-12-01").andExpect(status().isOk());
        for (String query : List.of(
                "from=2026-11-01&to=2026-12-02", "from=2026-11-10&to=2026-11-09", "from=2026-11-10",
                "to=2026-11-10", "", "from=2026-1-10&to=2026-11-10", "from=2026-11-31&to=2026-11-31",
                "from=20261110&to=20261110", "from=2026-11-10T00:00:00Z&to=2026-11-10",
                "from=%202026-11-10&to=2026-11-10", "from=1999-12-31&to=1999-12-31",
                "from=2101-01-01&to=2101-01-01", "from=abc&to=def", "from=2026-11-10&to=2026-11-10&staffMemberId=x")) {
            for (boolean agenda : List.of(false, true)) {
                var result = (agenda ? agenda(owner, query) : calendar(owner, query)).andExpect(status().isBadRequest());
                assertThat(json(result).get("code").asString()).as(query).isEqualTo("VALIDATION_ERROR");
                assertThat(body(result)).as(query).doesNotContain("fieldErrors", "2026-11", "abc");
            }
        }
        calendar(owner, "from=2000-01-01&to=2000-01-01").andExpect(status().isOk());
        calendar(owner, "from=2100-12-31&to=2100-12-31").andExpect(status().isOk());
    }

    // ---- Customer history ------------------------------------------------------------------------

    @Test
    void theCustomerHistoryIsNewestFirstWithMinimumFactsAndOnlyThatCustomersAppointments() throws Exception {
        UUID other = customer(owner.businessId(), "Друг Клиент");
        UUID oldest = visit(tenant, NOV_10, "Старо");
        UUID newest = appointment(tenant, NOV_10.plusSeconds(86_400L * 5), 30, row -> {
            row.put("internal_note", NOTE);
            row.put("customer_note", GUEST_NOTE);
        });
        UUID cancelled = appointment(tenant, NOV_10.plusSeconds(86_400L * 2), 30);
        fixtures.cancel(cancelled);
        appointment(withCustomer(tenant, other), NOV_10.plusSeconds(86_400L * 3), 30);

        var result = history(owner, tenant.customer(), null).andExpect(status().isOk());
        JsonNode body = json(result);

        assertThat(keys(body)).containsExactly("timezone", "items", "page", "size", "total");
        assertThat(ids(body.get("items"))).containsExactly(newest.toString(), cancelled.toString(), oldest.toString());
        assertThat(keys(body.get("items").get(0))).containsExactly(
                "id", "status", "source", "start", "end", "staffDisplayName", "serviceNames");
        assertThat(body.get("items").get(1).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(body.get("total").asLong()).isEqualTo(3);
        assertThat(body.get("items").get(2).get("serviceNames").get(0).asString()).isEqualTo("Старо");
        assertThat(body(result)).doesNotContain(NOTE, GUEST_NOTE, "Анна", "Друг Клиент", "phone", "email",
                "reference", "price", "version", "cancel", "customerId", "customerDisplayName");
    }

    @Test
    void theCustomerHistoryPagesNewestFirstWithStableTiesAndABeyondPageIsEmpty() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            UUID id = new UUID(index, 0);
            UUID staff = staff(owner.businessId(), "Служител " + index, true);
            appointment(withStaff(tenant, staff), NOV_10, 30, row -> row.put("id", id));
        }
        for (int index = 12; index >= 0; index--) {
            expected.add(new UUID(index, 0).toString());
        }

        JsonNode first = json(history(owner, tenant.customer(), "page=0&size=10"));
        JsonNode second = json(history(owner, tenant.customer(), "page=1&size=10"));
        JsonNode beyond = json(history(owner, tenant.customer(), "page=9&size=10"));

        assertThat(ids(first.get("items"))).containsExactlyElementsOf(expected.subList(0, 10));
        assertThat(ids(second.get("items"))).containsExactlyElementsOf(expected.subList(10, 13));
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("total").asLong()).isEqualTo(13);
        history(owner, tenant.customer(), "size=7").andExpect(status().isBadRequest());
        history(owner, tenant.customer(), "page=-1").andExpect(status().isBadRequest());
    }

    @Test
    void aCustomerWithoutAppointmentsHasAnEmptyHistory() throws Exception {
        UUID none = customer(owner.businessId(), "Без часове");

        JsonNode body = json(history(owner, none, null).andExpect(status().isOk()));

        assertThat(body.get("items")).isEmpty();
        assertThat(body.get("total").asLong()).isZero();
    }
}
