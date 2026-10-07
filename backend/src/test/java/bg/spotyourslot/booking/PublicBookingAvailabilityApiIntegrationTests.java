package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The public booking options and availability routes (ADR-0026) over real PostgreSQL: exact key
 * sets, the Business-local window, deterministic ordering, tenant isolation, and the collapsed
 * unavailable cases, for the anonymous caller through the complete filter chain.
 */
class PublicBookingAvailabilityApiIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final List<String> OPTIONS_KEYS = List.of("timezone", "firstDate", "lastDate", "staff");
    private static final List<String> STAFF_KEYS = List.of("id", "displayName");
    private static final List<String> AVAILABILITY_KEYS = List.of("date", "timezone", "availableDates", "slots");
    private static final List<String> SLOT_KEYS = List.of("start", "end");

    // ---- booking options ----

    @Test
    void anonymousBookingOptionsHaveExactlyTheApprovedKeysAndTheBusinessLocalWindow() throws Exception {
        MvcResult result = getJson(optionsUrl(slugOf(tenant.business()), tenant.service()));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        JsonNode body = tree(result);
        assertThat(keys(body)).containsExactlyElementsOf(OPTIONS_KEYS);
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        // The clock is 2026-09-29 11:00 in Sofia; the window is thirty Business-local dates.
        assertThat(body.get("firstDate").asString()).isEqualTo("2026-09-29");
        assertThat(body.get("lastDate").asString()).isEqualTo("2026-10-28");
        assertThat(body.get("staff")).hasSize(1);
        assertThat(keys(body.get("staff").get(0))).containsExactlyElementsOf(STAFF_KEYS);
        assertThat(body.get("staff").get(0).get("id").asString()).isEqualTo(tenant.staff().toString());
        assertThat(body.get("staff").get(0).get("displayName").asString()).isEqualTo("Availability Staff");
    }

    @Test
    void bookingOptionsListOnlyActiveAssignedStaffInTheAdministrationNameOrder() throws Exception {
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        UUID third = fixtures.staffMember(tenant.business(), tenant.service());
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        UUID unassigned = availabilityFixtures.staffMember(tenant.business(), true);
        availabilityFixtures.setStaffMemberActive(inactive, false);
        rename(tenant.staff(), "Мария");
        rename(second, "александър");
        rename(third, "Борис");
        rename(inactive, "Виктор");
        rename(unassigned, "Георги");

        JsonNode staff = tree(getJson(optionsUrl(slugOf(tenant.business()), tenant.service()))).get("staff");

        assertThat(names(staff)).containsExactly("александър", "Борис", "Мария");
        assertThat(ids(staff)).containsExactly(second.toString(), third.toString(), tenant.staff().toString());
    }

    @Test
    void bookingOptionsAreScopedToTheServiceAndTheBusiness() throws Exception {
        UUID otherService = availabilityFixtures.service(tenant.business(), 30, true);
        UUID onlyForOther = availabilityFixtures.staffMember(tenant.business(), true);
        availabilityFixtures.assign(tenant.business(), onlyForOther, otherService);

        JsonNode forFirst = tree(getJson(optionsUrl(slugOf(tenant.business()), tenant.service()))).get("staff");
        JsonNode forOther = tree(getJson(optionsUrl(slugOf(tenant.business()), otherService))).get("staff");

        assertThat(ids(forFirst)).containsExactly(tenant.staff().toString());
        assertThat(ids(forOther)).containsExactly(onlyForOther.toString());
    }

    @Test
    void aServiceWithoutAnyBookableStaffHasAnEmptyStaffListNotAnError() throws Exception {
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), tenant.service());

        MvcResult result = getJson(optionsUrl(slugOf(tenant.business()), tenant.service()));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(result).get("staff")).isEmpty();
    }

    @Test
    void theRealStaffPrivateDataNeverAppearsInThePublicResponses() throws Exception {
        jdbc.sql("""
                        UPDATE staff_member
                        SET contact_email = 'sentinel-staff-private@example.invalid',
                            contact_phone = '+359889998877',
                            version = 41
                        WHERE id = :id
                        """)
                .param("id", tenant.staff())
                .update();
        String slug = slugOf(tenant.business());

        String options = text(getJson(optionsUrl(slug, tenant.service())).getResponse());
        String availability = text(
                getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01").getResponse());

        for (String raw : List.of(options, availability)) {
            assertThat(raw)
                    .doesNotContain("sentinel-staff-private")
                    .doesNotContain("889998877")
                    .doesNotContain(tenant.business().toString())
                    .doesNotContain(tenant.customer().toString())
                    .doesNotContainIgnoringCase("version")
                    .doesNotContainIgnoringCase("createdAt")
                    .doesNotContainIgnoringCase("contact")
                    .doesNotContainIgnoringCase("schedule");
        }
    }

    // ---- availability ----

    @Test
    void availabilityReturnsTheOfferedSlotsOfTheDateAndTheAvailableDates() throws Exception {
        MvcResult result = getJson(availabilityUrl(slugOf(tenant.business()), tenant.service())
                + "?date=2026-10-01");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = tree(result);
        assertThat(keys(body)).containsExactlyElementsOf(AVAILABILITY_KEYS);
        assertThat(body.get("date").asString()).isEqualTo("2026-10-01");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        List<String> dates = strings(body.get("availableDates"));
        // Today has no slot left (09:00 to 12:00 hours are over at 11:00 plus two hours of notice).
        assertThat(dates).doesNotContain("2026-09-29").startsWith("2026-09-30", "2026-10-01");
        assertThat(dates).hasSize(29).isSorted().doesNotHaveDuplicates();
        assertThat(dates.get(dates.size() - 1)).isEqualTo("2026-10-28");
        JsonNode slots = body.get("slots");
        assertThat(slots).hasSize(11);
        assertThat(keys(slots.get(0))).containsExactlyElementsOf(SLOT_KEYS);
        // A slot is an instant with its offset (ADR-0013) and carries no StaffMember.
        assertThat(slots.get(0).get("start").asString()).isEqualTo("2026-10-01T09:00:00+03:00");
        assertThat(slots.get(0).get("end").asString()).isEqualTo("2026-10-01T09:30:00+03:00");
        assertThat(slots.get(10).get("start").asString()).isEqualTo("2026-10-01T11:30:00+03:00");
        assertThat(slots.get(10).get("end").asString()).isEqualTo("2026-10-01T12:00:00+03:00");
        assertThat(text(result.getResponse())).doesNotContain(tenant.staff().toString());
    }

    @Test
    void aSpecificStaffMemberNarrowsTheSlotsAndNoPreferenceUnionsThem() throws Exception {
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.period(tenant.business(), second, java.time.DayOfWeek.THURSDAY, "14:00", "15:00");
        String slug = slugOf(tenant.business());
        String url = availabilityUrl(slug, tenant.service()) + "?date=2026-10-01";

        List<String> first = starts(tree(getJson(url + "&staffMemberId=" + tenant.staff())));
        List<String> other = starts(tree(getJson(url + "&staffMemberId=" + second)));
        List<String> any = starts(tree(getJson(url)));

        assertThat(first).hasSize(11).first().isEqualTo("2026-10-01T09:00:00+03:00");
        assertThat(other).hasSize(3).containsExactly(
                "2026-10-01T14:00:00+03:00", "2026-10-01T14:15:00+03:00", "2026-10-01T14:30:00+03:00");
        assertThat(any).hasSize(14);
        assertThat(any).isSorted();  // one fixed offset on this date, so the text order is the time order
    }

    @Test
    void anAppointmentRemovesTheBookedStartsFromTheNextRead() throws Exception {
        String slug = slugOf(tenant.business());
        String url = availabilityUrl(slug, tenant.service()) + "?date=2026-10-01";
        assertThat(starts(tree(getJson(url)))).contains("2026-10-01T10:00:00+03:00");

        assertThat(book(req().start("10:00")).getResponse().getStatus()).isEqualTo(201);

        assertThat(starts(tree(getJson(url)))).doesNotContain(
                "2026-10-01T09:45:00+03:00", "2026-10-01T10:00:00+03:00", "2026-10-01T10:15:00+03:00");
    }

    @Test
    void aDateWithoutSlotsAndADateOutsideTheWindowAreEmptyNotErrors() throws Exception {
        String url = availabilityUrl(slugOf(tenant.business()), tenant.service());

        for (String date : List.of("2026-09-29", "2026-10-29", "2026-01-01", "2030-01-01")) {
            MvcResult result = getJson(url + "?date=" + date);
            assertThat(result.getResponse().getStatus()).as(date).isEqualTo(200);
            assertThat(tree(result).get("slots")).as(date).isEmpty();
            assertThat(tree(result).get("date").asString()).isEqualTo(date);
        }
    }

    @Test
    void theRepeatedAutumnHourKeepsBothOffsetsDistinct() throws Exception {
        // 2026-10-25 04:00 +03:00 falls back to 03:00 +02:00, so 03:00 to 04:00 local happens twice.
        clock.set(java.time.Instant.parse("2026-10-24T10:00:00Z"));
        UUID business = availabilityFixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = availabilityFixtures.service(business, 60, true);
        UUID staff = availabilityFixtures.staffMember(business, true);
        availabilityFixtures.assign(business, staff, service);
        availabilityFixtures.period(business, staff, java.time.DayOfWeek.SUNDAY, "02:00", "06:00");

        JsonNode body = tree(getJson(availabilityUrl(slugOf(business), service) + "?date=2026-10-25"));

        List<String> starts = starts(body);
        assertThat(starts).contains("2026-10-25T03:00:00+03:00", "2026-10-25T03:00:00+02:00");
        // Ordered by instant, not by wall-clock text: the second 03:00 follows the first 03:45.
        assertThat(starts).doesNotHaveDuplicates();
        assertThat(starts.stream().map(java.time.OffsetDateTime::parse).map(java.time.OffsetDateTime::toInstant))
                .isSorted();
        assertThat(starts.indexOf("2026-10-25T03:00:00+02:00"))
                .isGreaterThan(starts.indexOf("2026-10-25T03:45:00+03:00"));
    }

    // ---- tenant isolation and unavailable cases ----

    @Test
    void businessAAndBNeverSeeEachOthersServicesOrStaff() throws Exception {
        var other = openTenant();
        String slugA = slugOf(tenant.business());
        String slugB = slugOf(other.business());

        assertThat(ids(tree(getJson(optionsUrl(slugA, tenant.service()))).get("staff")))
                .containsExactly(tenant.staff().toString());
        assertThat(ids(tree(getJson(optionsUrl(slugB, other.service()))).get("staff")))
                .containsExactly(other.staff().toString());
        // A Service of the other Business is indistinguishable from a missing one.
        for (var url : List.of(optionsUrl(slugA, other.service()),
                availabilityUrl(slugA, other.service()) + "?date=2026-10-01")) {
            MvcResult result = getJson(url);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(tree(result).get("code").asString()).isEqualTo("BOOKING_SERVICE_UNAVAILABLE");
            assertThat(text(result.getResponse())).doesNotContain(other.service().toString());
        }
        // A foreign StaffMember is an unavailable StaffMember, not a Service problem.
        MvcResult foreignStaff = getJson(availabilityUrl(slugA, tenant.service())
                + "?date=2026-10-01&staffMemberId=" + other.staff());
        assertThat(foreignStaff.getResponse().getStatus()).isEqualTo(409);
        assertThat(tree(foreignStaff).get("code").asString()).isEqualTo("BOOKING_STAFF_UNAVAILABLE");
    }

    @Test
    void aMissingInactiveForeignOrUnassignedTargetIsTheSameGenericConflict() throws Exception {
        String slug = slugOf(tenant.business());
        UUID inactiveService = availabilityFixtures.service(tenant.business(), 30, false);
        UUID inactiveStaff = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.setStaffMemberActive(inactiveStaff, false);
        UUID unassigned = availabilityFixtures.staffMember(tenant.business(), true);
        List<MvcResult> services = List.of(
                getJson(optionsUrl(slug, UUID.randomUUID())),
                getJson(optionsUrl(slug, inactiveService)),
                getJson(availabilityUrl(slug, UUID.randomUUID()) + "?date=2026-10-01"),
                getJson(availabilityUrl(slug, inactiveService) + "?date=2026-10-01"));
        List<MvcResult> staff = List.of(
                getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01&staffMemberId="
                        + UUID.randomUUID()),
                getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01&staffMemberId="
                        + inactiveStaff),
                getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01&staffMemberId="
                        + unassigned));

        for (MvcResult result : services) {
            assertProblem(result, 409, "BOOKING_SERVICE_UNAVAILABLE",
                    "Избраната услуга вече не е налична. Изберете друга услуга.");
        }
        for (MvcResult result : staff) {
            assertProblem(result, 409, "BOOKING_STAFF_UNAVAILABLE",
                    "Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.");
        }
        assertThat(text(services.get(0).getResponse())).isEqualTo(text(services.get(1).getResponse()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "SUSPENDED"})
    void aNonActiveBusinessIsTheSameCollapsedNotFoundAsAnUnknownOne(String status) throws Exception {
        availabilityFixtures.setBusinessStatus(tenant.business(), status);
        String slug = slugOf(tenant.business());
        List<MvcResult> hidden = List.of(
                getJson(optionsUrl(slug, tenant.service())),
                getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01"),
                getJson(optionsUrl("unknown-studio", tenant.service())),
                getJson(optionsUrl("Bad_Slug", tenant.service())),
                getJson(availabilityUrl("booking", tenant.service()) + "?date=2026-10-01"));

        for (MvcResult result : hidden) {
            assertProblem(result, 404, "BUSINESS_PAGE_UNAVAILABLE", "Страницата не е налична.");
            assertThat(tree(result).get("instance").asString()).isEqualTo(FIXED_INSTANCE);
        }
        assertThat(text(hidden.get(0).getResponse())).isEqualTo(text(hidden.get(2).getResponse()));
    }

    @Test
    void aSlugIsCanonicalizedForTheReadRoutes() throws Exception {
        String slug = slugOf(tenant.business());

        MvcResult result = getJson(optionsUrl(slug.toUpperCase(), tenant.service()));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    // ---- invalid input ----

    @Test
    void invalidReadInputIsAFixedValidationError() throws Exception {
        String slug = slugOf(tenant.business());
        String availability = availabilityUrl(slug, tenant.service());
        List<MvcResult> invalid = List.of(
                getJson(availability),
                getJson(availability + "?date="),
                getJson(availability + "?date=2026-13-01"),
                getJson(availability + "?date=2026-1-1"),
                getJson(availability + "?date=01.10.2026"),
                getJson(availability + "?date=2026-10-01T10:00:00"),
                getJson(availability + "?date=2026-10-01&staffMemberId=not-a-uuid"),
                getJson(PREFIX + "/" + slug + "/services/not-a-uuid/booking-options"),
                getJson(PREFIX + "/" + slug + "/services/not-a-uuid/availability?date=2026-10-01"));

        for (MvcResult result : invalid) {
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(tree(result).has("fieldErrors")).isFalse();
            assertThat(tree(result).get("instance").asString()).isEqualTo(FIXED_INSTANCE);
            assertThat(text(result.getResponse())).doesNotContain(slug).doesNotContain("not-a-uuid");
        }
    }

    // ---- unsupported routes and verbs ----

    @Test
    void unsupportedVerbsAndSiblingOrDeeperRoutesRemainDenied() throws Exception {
        String slug = slugOf(tenant.business());
        UUID service = tenant.service();
        List<String> getRoutes = List.of(
                PREFIX + "/" + slug + "/bookings",
                PREFIX + "/" + slug + "/services",
                PREFIX + "/" + slug + "/services/" + service,
                PREFIX + "/" + slug + "/services/" + service + "/slots",
                PREFIX + "/" + slug + "/services/" + service + "/availability/extra",
                PREFIX + "/" + slug + "/services/" + service + "/booking-options/",
                PREFIX + "/" + slug + "/staff",
                PREFIX + "/" + slug + "/appointments/ABCDEFGHJK",
                PREFIX + "/" + slug + "/bookings/ABCDEFGHJK",
                PREFIX + "/" + slug + "/bookings/" + UUID.randomUUID(),
                PREFIX + "/" + slug + "/customers");

        for (String route : getRoutes) {
            MockHttpServletResponse response = mvc.perform(from(get(route), freshAddress())).andReturn().getResponse();
            assertThat(response.getStatus()).as(route).isEqualTo(401);
            assertThat(response.getContentAsString()).as(route).contains("AUTH_REQUIRED");
        }
        for (var request : List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        optionsUrl(slug, service)),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        availabilityUrl(slug, service)),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(bookingsUrl(slug)),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(bookingsUrl(slug)),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(bookingsUrl(slug)),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(
                        optionsUrl(slug, service)))) {
            MockHttpServletResponse response = mvc.perform(from(request, freshAddress())).andReturn().getResponse();
            assertThat(response.getStatus()).as(request.toString()).isIn(401, 403);
        }
    }

    @Test
    void thePublicReadsCreateOrChangeNoDomainDataAndNoSession() throws Exception {
        String slug = slugOf(tenant.business());
        List<Long> before = counts();
        List<String> sessions = sessionRows();

        getJson(optionsUrl(slug, tenant.service()));
        getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01");
        getJson(availabilityUrl(slug, UUID.randomUUID()) + "?date=2026-10-01");
        getJson(optionsUrl("unknown-studio", tenant.service()));

        assertThat(counts()).isEqualTo(before);
        assertThat(sessionRows()).isEqualTo(sessions);
    }

    @Test
    void everyReadResponseIsNoStoreWithoutACookieAndFixedProblemInstances() throws Exception {
        String slug = slugOf(tenant.business());
        List<MvcResult> responses = List.of(
                getJson(optionsUrl(slug, tenant.service())),
                getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01"),
                getJson(optionsUrl(slug, UUID.randomUUID())),
                getJson(optionsUrl("unknown", tenant.service())),
                getJson(availabilityUrl(slug, tenant.service())));

        for (MvcResult result : responses) {
            MockHttpServletResponse response = result.getResponse();
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getHeaderValues("Set-Cookie")).isEmpty();
            assertThat(response.getHeader("Retry-After")).isNull();
        }
    }

    // ---- helpers ----

    private void assertProblem(MvcResult result, int status, String code, String detail) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode body = tree(result);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("detail").asString()).isEqualTo(detail);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("instance").asString()).isEqualTo(FIXED_INSTANCE);
    }

    private void rename(UUID staff, String name) {
        jdbc.sql("UPDATE staff_member SET display_name = :name WHERE id = :id")
                .param("name", name)
                .param("id", staff)
                .update();
    }

    private static List<String> names(JsonNode staff) {
        List<String> names = new ArrayList<>();
        staff.forEach(member -> names.add(member.get("displayName").asString()));
        return names;
    }

    private static List<String> ids(JsonNode staff) {
        List<String> ids = new ArrayList<>();
        staff.forEach(member -> ids.add(member.get("id").asString()));
        return ids;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static List<String> starts(JsonNode availability) {
        List<String> starts = new ArrayList<>();
        availability.get("slots").forEach(slot -> starts.add(slot.get("start").asString()));
        return starts;
    }

    private List<Long> counts() {
        List<Long> counts = new ArrayList<>();
        for (String table : List.of(
                "business", "service", "staff_member", "customer", "appointment", "membership",
                "app_user", "user_session")) {
            counts.add(jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }
}
