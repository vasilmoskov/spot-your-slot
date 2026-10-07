package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import bg.spotyourslot.booking.BookingTestHooks.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public guest booking endpoint (ADR-0026) over real PostgreSQL through the complete filter
 * chain: creation (201), exact-attempt replay (200), the replay corner cases, the response
 * allowlist, every rejection, field-level validation, and what a failed request leaves behind.
 *
 * <p>Evidence boundary: transaction, commit, retry, and idempotency behavior is proven by the Phase 4
 * tests. The rollback and uncertain cases here produce their faults through the same real
 * mechanisms as {@code GuestBookingCommitFailureIntegrationTests} and prove the HTTP mapping and the
 * guest's ability to repeat the same attempt.
 */
@ExtendWith(OutputCaptureExtension.class)
class PublicBookingCreationApiIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final List<String> RESPONSE_KEYS =
            List.of("reference", "status", "service", "staff", "start", "end", "timezone");

    // ---- creation ----

    @Test
    void anAnonymousGuestCreatesOneConfirmedAppointmentWithExactlyTheApprovedFields() throws Exception {
        Req request = req().staff(tenant.staff()).phone("0888 111 222").note("  Бележка\r\nза часа \n");

        MvcResult result = book(request);

        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = tree(result);
        assertThat(keys(body)).containsExactlyElementsOf(RESPONSE_KEYS);
        assertThat(keys(body.get("service"))).containsExactly("name", "durationMinutes", "price");
        assertThat(keys(body.get("staff"))).containsExactly("displayName");
        assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(body.get("reference").asString()).matches("[0-9A-HJKMNP-TV-Z]{10}");
        assertThat(body.get("service").get("name").asString()).startsWith("Service ");
        assertThat(body.get("service").get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(text(response)).contains("\"price\":10.00");
        assertThat(body.get("staff").get("displayName").asString()).isEqualTo("Availability Staff");
        assertThat(body.get("start").asString()).isEqualTo("2026-10-01T10:00:00+03:00");
        assertThat(body.get("end").asString()).isEqualTo("2026-10-01T10:30:00+03:00");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");

        AppointmentRow row = appointmentRow(body.get("reference").asString());
        assertThat(row.businessId()).isEqualTo(tenant.business());
        assertThat(row.source()).isEqualTo("ONLINE");
        assertThat(row.status()).isEqualTo("CONFIRMED");
        assertThat(row.customerNote()).isEqualTo("Бележка\nза часа");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void theCreationResponseNeverContainsAnInternalIdentifierContactOrAuditValue() throws Exception {
        jdbc.sql("UPDATE service SET name = 'Подстригване' WHERE id = :id").param("id", tenant.service()).update();
        Req request = req().staff(tenant.staff()).phone("0888 111 222").email("guest@example.com")
                .note("СЕНТИНЕЛ-БЕЛЕЖКА");

        MvcResult result = book(request);

        String raw = text(result.getResponse());
        UUID appointmentId = appointmentIdByReference(tree(result).get("reference").asString()).orElseThrow();
        UUID customerId = appointmentRow(tree(result).get("reference").asString()).customerId();
        assertThat(raw)
                .doesNotContain(appointmentId.toString())
                .doesNotContain(customerId.toString())
                .doesNotContain(tenant.business().toString())
                .doesNotContain(tenant.service().toString())
                .doesNotContain(tenant.staff().toString())
                .doesNotContain(request.attemptId)
                .doesNotContain("888111222")
                .doesNotContain("guest@example.com")
                .doesNotContain("Иван Петров")
                .doesNotContain("СЕНТИНЕЛ")
                .doesNotContainIgnoringCase("version")
                .doesNotContainIgnoringCase("createdAt")
                .doesNotContainIgnoringCase("updatedAt")
                .doesNotContainIgnoringCase("source")
                .doesNotContainIgnoringCase("fingerprint")
                .doesNotContainIgnoringCase("membership");
    }

    @Test
    void anUnspecifiedStaffMemberIsAssignedAndShownOnlyInTheConfirmation() throws Exception {
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), second, "09:00", "12:00");
        jdbc.sql("UPDATE staff_member SET display_name = 'Втори Служител' WHERE id = :id")
                .param("id", second).update();

        MvcResult result = book(req());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String assigned = tree(result).get("staff").get("displayName").asString();
        assertThat(assigned).isIn("Availability Staff", "Втори Служител");
        assertThat(text(result.getResponse())).doesNotContain(second.toString())
                .doesNotContain(tenant.staff().toString());
    }

    // ---- replay ----

    @Test
    void anExactReloadEquivalentReplayIsOkWithTheIdenticalBodyAndWritesNothing() throws Exception {
        Req request = req().staff(tenant.staff());
        MvcResult created = book(request);
        long customers = customerCount(tenant.business());

        MvcResult replayed = book(request.copy());

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(replayed.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(replayed.getResponse())).isEqualTo(text(created.getResponse()));
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(customers);
    }

    @Test
    void aCosmeticallyDifferentButEquivalentRetryIsTheSameRequestAndReplays() throws Exception {
        String phone = freshPhone();
        Req request = req().phone(phone).email("Guest@Example.com").name("Иван Петров");
        MvcResult created = book(request);

        MvcResult replayed = book(request.copy().phone(phone.replace("+359", "0")).email("guest@example.COM")
                .name("  Иван   Петров "));

        assertThat(replayed.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(replayed).get("reference").asString())
                .isEqualTo(tree(created).get("reference").asString());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void theSameAttemptWithADifferentPayloadIsAMismatchAndChangesNothing() throws Exception {
        Req request = req();
        book(request);
        List<Req> changed = List.of(
                request.copy().start("10:30"),
                request.copy().name("Друго Име"),
                request.copy().email("another@example.com"),
                request.copy().note("друга бележка"));

        for (Req variant : changed) {
            MvcResult result = book(variant);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            JsonNode body = tree(result);
            assertThat(body.get("code").asString()).isEqualTo("BOOKING_ATTEMPT_MISMATCH");
            assertThat(body.get("detail").asString())
                    .isEqualTo("Тази заявка вече е използвана с други данни. Започнете резервацията отново.");
            assertThat(text(result.getResponse())).doesNotContain(request.attemptId);
        }
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aDifferentPhoneOrStaffPreferenceUnderTheSameAttemptIsAlsoAMismatch() throws Exception {
        Req request = req();
        book(request);

        for (Req variant : List.of(request.copy().phone(freshPhone()), request.copy().staff(tenant.staff()))) {
            MvcResult result = book(variant);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(tree(result).get("code").asString()).isEqualTo("BOOKING_ATTEMPT_MISMATCH");
        }
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aMatchingReplayAfterBusinessSuspensionIsOkButANewAttemptIsUnavailable() throws Exception {
        Req request = req();
        MvcResult created = book(request);
        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");

        MvcResult replayed = book(request.copy());
        MvcResult fresh = book(req().start("11:00"));

        assertThat(replayed.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(replayed.getResponse())).isEqualTo(text(created.getResponse()));
        assertThat(fresh.getResponse().getStatus()).isEqualTo(404);
        assertThat(tree(fresh).get("code").asString()).isEqualTo("BUSINESS_PAGE_UNAVAILABLE");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aReplayReturnsTheOriginalSnapshotsAfterTheServiceAndStaffMemberChange() throws Exception {
        Req request = req().staff(tenant.staff());
        MvcResult created = book(request);
        jdbc.sql("UPDATE service SET name = 'Преименувана', price = 99.00, duration_minutes = 60, active = false"
                + " WHERE id = :id").param("id", tenant.service()).update();
        jdbc.sql("UPDATE staff_member SET display_name = 'Преименуван', active = false WHERE id = :id")
                .param("id", tenant.staff()).update();

        MvcResult replayed = book(request.copy());

        assertThat(replayed.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(replayed.getResponse())).isEqualTo(text(created.getResponse()));
        assertThat(text(replayed.getResponse())).doesNotContain("Преименувана").doesNotContain("99");
    }

    @Test
    void aReplayOfALaterCancelledAppointmentIsOkWithTheCancelledStatus() throws Exception {
        Req request = req();
        MvcResult created = book(request);
        // Integration fixture only: the public API and this phase have no cancellation capability.
        jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE public_reference = :reference")
                .param("reference", tree(created).get("reference").asString()).update();

        MvcResult replayed = book(request.copy());

        assertThat(replayed.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = tree(replayed);
        assertThat(body.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(body.get("reference").asString()).isEqualTo(tree(created).get("reference").asString());
        assertThat(keys(body)).containsExactlyElementsOf(RESPONSE_KEYS);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    // ---- rejections that are not validation ----

    @Test
    void aTakenSlotIsASlotConflictWithoutRevealingWhoHoldsIt() throws Exception {
        book(req().phone("+359888111001").name("Първи Гост"));
        long customers = customerCount(tenant.business());

        MvcResult result = book(req().phone("+359888111002").name("Втори Гост"));

        assertProblem(result, 409, "BOOKING_SLOT_UNAVAILABLE",
                "Избраният час вече не е свободен. Изберете друг час.");
        assertThat(text(result.getResponse())).doesNotContain("Първи").doesNotContain("111001");
        assertThat(customerCount(tenant.business())).isEqualTo(customers);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aStartThatWasNeverOfferedIsTheSameSlotConflict() throws Exception {
        for (String start : List.of("2026-10-01T08:00:00+03:00", "2026-10-01T10:07:00+03:00",
                "2026-10-01T13:00:00+03:00", "2026-09-29T11:00:00+03:00", "2031-10-01T10:00:00+03:00")) {
            Req request = req();
            ObjectNode body = body(request);
            body.put("start", start);

            MvcResult result = postJson(bookingsUrl(request.slug), body.toString());

            assertProblem(result, 409, "BOOKING_SLOT_UNAVAILABLE",
                    "Избраният час вече не е свободен. Изберете друг час.");
        }
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void aMissingInactiveForeignOrUnassignedServiceIsTheSameGenericConflict() throws Exception {
        var other = openTenant();
        UUID inactive = availabilityFixtures.service(tenant.business(), 30, false);

        for (UUID service : List.of(UUID.randomUUID(), inactive, other.service())) {
            MvcResult result = book(req().service(service));
            assertProblem(result, 409, "BOOKING_SERVICE_UNAVAILABLE",
                    "Избраната услуга вече не е налична. Изберете друга услуга.");
            assertThat(text(result.getResponse())).doesNotContain(service.toString());
        }
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void aMissingInactiveForeignOrUnassignedStaffMemberIsTheSameGenericConflict() throws Exception {
        var other = openTenant();
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.setStaffMemberActive(inactive, false);
        UUID unassigned = availabilityFixtures.staffMember(tenant.business(), true);

        for (UUID staff : List.of(UUID.randomUUID(), inactive, other.staff(), unassigned)) {
            MvcResult result = book(req().staff(staff));
            assertProblem(result, 409, "BOOKING_STAFF_UNAVAILABLE",
                    "Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.");
            assertThat(text(result.getResponse())).doesNotContain(staff.toString());
        }
        assertThat(totalAppointments()).isZero();
    }

    @ParameterizedTest
    @MethodSource("hiddenSlugs")
    void aDraftSuspendedUnknownMalformedOrReservedBusinessIsOneCollapsedNotFound(String status, String slug)
            throws Exception {
        if (status != null) {
            availabilityFixtures.setBusinessStatus(tenant.business(), status);
        }
        String effective = slug == null ? slugOf(tenant.business()) : slug;

        MvcResult result = book(req().slug(effective));

        assertProblem(result, 404, "BUSINESS_PAGE_UNAVAILABLE", "Страницата не е налична.");
        assertThat(text(result.getResponse())).doesNotContain("DRAFT").doesNotContain("SUSPENDED");
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> hiddenSlugs() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("DRAFT", null),
                org.junit.jupiter.params.provider.Arguments.of("SUSPENDED", null),
                org.junit.jupiter.params.provider.Arguments.of(null, "unknown-studio"),
                org.junit.jupiter.params.provider.Arguments.of(null, "bad_slug"),
                org.junit.jupiter.params.provider.Arguments.of(null, "booking"),
                org.junit.jupiter.params.provider.Arguments.of(null, "x".repeat(101)));
    }

    @Test
    void aSplitOrPartialIdentityIsTheGenericNotCompletedOnlineConflict() throws Exception {
        book(req().phone("+359888555001").start("09:00"));
        book(req().phone(null).email("held@example.com").start("09:30"));
        long customers = customerCount(tenant.business());

        MvcResult split = book(req().phone("+359888555001").email("held@example.com").start("10:00"));
        MvcResult partial = book(req().phone("+359888555001").email("fresh@example.com").start("10:30"));

        for (MvcResult result : List.of(split, partial)) {
            assertProblem(result, 409, "BOOKING_NOT_COMPLETED_ONLINE",
                    "Не можем да завършим резервацията онлайн. Моля, свържете се с бизнеса.");
            assertThat(text(result.getResponse())).doesNotContain("555001").doesNotContain("held@");
        }
        assertThat(text(split.getResponse()))
                .isEqualTo(text(partial.getResponse()));
        assertThat(customerCount(tenant.business())).isEqualTo(customers);
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
    }

    @Test
    void anotherBusinessesServiceStaffAndCustomersAreNeverUsed() throws Exception {
        var other = openTenant();
        String phone = "+359888777001";
        assertThat(book(req().forTenant(other).phone(phone)).getResponse().getStatus()).isEqualTo(201);

        // The same guest at Business A is a new Customer there; Business B's data is untouched.
        MvcResult atA = book(req().phone(phone));

        assertThat(atA.getResponse().getStatus()).isEqualTo(201);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(other.business())).isEqualTo(1);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(appointmentCount(other.business())).isEqualTo(1);
        // A booking at A naming B's Service or StaffMember is rejected as unavailable, never booked at B.
        assertThat(book(req().service(other.service())).getResponse().getStatus()).isEqualTo(409);
        assertThat(book(req().staff(other.staff()).start("11:00")).getResponse().getStatus()).isEqualTo(409);
        assertThat(appointmentCount(other.business())).isEqualTo(1);
    }

    // ---- validation ----

    @Test
    void everyContactFieldHasItsOwnFieldLevelErrorAndNothingIsWritten() throws Exception {
        Map<String, Req> cases = new java.util.LinkedHashMap<>();
        cases.put("displayName", req().name("   "));
        cases.put("phone", req().phone("12"));
        cases.put("email", req().phone(null).email("not-an-email"));
        cases.put("contact", req().phone(null).email(null));
        cases.put("note", req().note("я".repeat(501)));
        Map<String, String> messages = Map.of(
                "displayName", "Въведете име до 200 знака.",
                "phone", "Въведеният телефонен номер не е валиден.",
                "email", "Въведеният имейл адрес не е валиден.",
                "contact", "Въведете телефон или имейл.",
                "note", "Бележката може да съдържа най-много 500 знака.");

        for (var entry : cases.entrySet()) {
            MvcResult result = book(entry.getValue());

            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            JsonNode fields = tree(result).get("fieldErrors");
            assertThat(keys(fields)).as(entry.getKey()).containsExactly(entry.getKey());
            assertThat(fields.get(entry.getKey()).asString()).isEqualTo(messages.get(entry.getKey()));
        }
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void severalInvalidFieldsAreReportedTogetherWithoutEchoingAnyValue() throws Exception {
        Req request = req().name(" ").phone("999").email("bad");

        MvcResult result = book(request);

        assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        assertThat(keys(tree(result).get("fieldErrors")))
                .containsExactly("displayName", "phone", "email");
        assertThat(text(result.getResponse())).doesNotContain("999").doesNotContain("bad\"");
    }

    @Test
    void theAttemptIdentifierAndStartAreGenericValidationErrorsWithoutAFieldError() throws Exception {
        for (String attempt : List.of("", "not-a-uuid", UUID.randomUUID().toString().toUpperCase(),
                "00000000-0000-0000-0000-000000000000")) {
            MvcResult result = book(req().attempt(attempt));
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(tree(result).has("fieldErrors")).isFalse();
        }
        Req request = req();
        ObjectNode body = body(request);
        body.put("start", "2026-10-01T10:00:00.5+03:00");
        MvcResult subSecond = postJson(bookingsUrl(request.slug), body.toString());
        assertProblem(subSecond, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        // An instant the orchestration cannot represent is the same generic validation error.
        body.put("start", "+999999999-12-31T23:59:59+18:00");
        assertProblem(postJson(bookingsUrl(request.slug), body.toString()), 400, "VALIDATION_ERROR",
                "Проверете въведените данни.");
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void structurallyInvalidBodiesAreFixedValidationErrors() throws Exception {
        Req request = req();
        String slug = request.slug;
        List<String> bodies = new ArrayList<>();
        ObjectNode missingService = body(request);
        missingService.remove("serviceId");
        bodies.add(missingService.toString());
        ObjectNode missingStart = body(request);
        missingStart.remove("start");
        bodies.add(missingStart.toString());
        ObjectNode badStart = body(request);
        badStart.put("start", "tomorrow morning");
        bodies.add(badStart.toString());
        ObjectNode noOffset = body(request);
        noOffset.put("start", "2026-10-01T10:00:00");
        bodies.add(noOffset.toString());
        ObjectNode badService = body(request);
        badService.put("serviceId", "not-a-uuid");
        bodies.add(badService.toString());
        ObjectNode badStaff = body(request);
        badStaff.put("staffMemberId", "not-a-uuid");
        bodies.add(badStaff.toString());
        ObjectNode customerAsText = body(request);
        customerAsText.put("customer", "Иван");
        bodies.add(customerAsText.toString());
        ObjectNode missingCustomer = body(request);
        missingCustomer.remove("customer");
        bodies.add(missingCustomer.toString());
        ObjectNode nameAsObject = body(request);
        ((ObjectNode) nameAsObject.get("customer")).putObject("displayName");
        bodies.add(nameAsObject.toString());
        bodies.add("{}");
        bodies.add("[]");
        bodies.add("null");
        bodies.add("");
        bodies.add("{\"attemptId\":");
        bodies.add("not json at all");

        for (String body : bodies) {
            MvcResult result = postJson(bookingsUrl(slug), body);
            assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
            assertThat(tree(result).get("code").asString()).as(body).isEqualTo("VALIDATION_ERROR");
            assertThat(tree(result).get("instance").asString()).as(body).isEqualTo(FIXED_INSTANCE);
            assertThat(text(result.getResponse())).as(body)
                    .doesNotContain(slug)
                    .doesNotContain(request.attemptId)
                    .doesNotContain("tomorrow")
                    .doesNotContain("not-a-uuid")
                    .doesNotContain("Jackson")
                    .doesNotContain("Exception");
        }
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void unknownPropertiesAnywhereInTheBodyAreAValidationError() throws Exception {
        Req request = req();
        for (String forbidden : List.of("businessId", "price", "durationMinutes", "end", "status",
                "source", "timezone", "customerId", "extra")) {
            ObjectNode top = body(request);
            top.put(forbidden, "x");
            ObjectNode nested = body(request);
            ((ObjectNode) nested.get("customer")).put(forbidden, "x");

            for (ObjectNode body : List.of(top, nested)) {
                MvcResult result = postJson(bookingsUrl(request.slug), body.toString());
                assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
                assertThat(tree(result).has("fieldErrors")).isFalse();
            }
        }
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void aNonJsonContentTypeIsAnUnsupportedMediaTypeWithAFixedBody() throws Exception {
        Req request = req();
        for (MediaType type : List.of(MediaType.TEXT_PLAIN, MediaType.APPLICATION_FORM_URLENCODED,
                MediaType.APPLICATION_XML)) {
            MockHttpServletResponse response = mvc.perform(from(
                            post(bookingsUrl(request.slug)).contentType(type).content(body(request).toString()),
                            freshAddress()))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as(type.toString()).isEqualTo(415);
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(json.readTree(text(response)).get("instance").asString()).isEqualTo(FIXED_INSTANCE);
        }
        // No body at all is a malformed request, not a media-type problem.
        MockHttpServletResponse none = mvc.perform(from(post(bookingsUrl(request.slug)), freshAddress()))
                .andReturn().getResponse();
        assertThat(none.getStatus()).isEqualTo(400);
        assertThat(json.readTree(text(none)).get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(totalAppointments()).isZero();
    }

    // ---- known rollback versus uncertain commit (real faults, repeated attempt) ----

    @Test
    void aKnownRollbackIs503WithTheApprovedMessageAndTheSameAttemptCanBeRepeated() throws Exception {
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            throw new IllegalStateException("collaborator failed with SECRET-DIAGNOSTIC detail");
        });
        Req request = req();

        MvcResult rolledBack = onBookingThread("rollback", () -> book(request));

        assertProblem(rolledBack, 503, "BOOKING_TEMPORARILY_UNAVAILABLE",
                "Резервацията не беше направена. Опитайте отново.");
        assertThat(rolledBack.getResponse().getHeader("Retry-After")).isEqualTo("2");
        assertThat(text(rolledBack.getResponse())).doesNotContain("SECRET-DIAGNOSTIC");
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();

        hooks.reset();
        MvcResult repeated = book(request.copy());

        assertThat(repeated.getResponse().getStatus()).isEqualTo(201);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void anUncertainCommitIs503WithTheApprovedDistinctMessageAndTheSameAttemptReplays() throws Exception {
        hooks.on(Point.AFTER_CUSTOMER, invocation -> TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        throw new IllegalStateException("callback failed with SECRET-DIAGNOSTIC detail");
                    }
                }));
        Req request = req();

        MvcResult uncertain = onBookingThread("uncertain", () -> book(request));

        assertProblem(uncertain, 503, "BOOKING_OUTCOME_UNCERTAIN",
                "Не получихме потвърждение за резервацията. Опитайте отново.");
        assertThat(uncertain.getResponse().getHeader("Retry-After")).isEqualTo("2");
        assertThat(text(uncertain.getResponse())).doesNotContain("SECRET-DIAGNOSTIC");
        // The commit really happened, which is exactly what the guest cannot know.
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);

        hooks.reset();
        MvcResult repeated = book(request.copy());

        assertThat(repeated.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(repeated).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(tree(uncertain).get("code").asString()).isNotEqualTo("BOOKING_TEMPORARILY_UNAVAILABLE");
    }

    // ---- no-store, no cookie, fixed instances, privacy ----

    @Test
    void everyBookingResponseIsNoStoreWithoutACookieAndEveryProblemHasTheFixedInstance() throws Exception {
        Req request = req();
        List<MvcResult> results = List.of(
                book(request),
                book(request.copy()),
                book(request.copy().start("10:30")),
                book(req().start("10:00").phone(freshPhone())),
                book(req().service(UUID.randomUUID())),
                book(req().staff(UUID.randomUUID())),
                book(req().slug("unknown-studio")),
                book(req().name(" ")),
                postJson(bookingsUrl(request.slug), "{"));

        for (MvcResult result : results) {
            MockHttpServletResponse response = result.getResponse();
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getHeaderValues("Set-Cookie")).isEmpty();
            if (response.getStatus() >= 400) {
                assertThat(response.getContentType()).startsWith("application/problem+json");
                assertThat(tree(result).get("instance").asString()).isEqualTo(FIXED_INSTANCE);
            }
        }
    }

    @Test
    void personalDataAndAttemptIdentifiersNeverAppearInErrorsOrLogs(CapturedOutput output) throws Exception {
        String name = "СЕНТИНЕЛ-ИМЕ-5521";
        String phone = "+359 88 5551234";
        String email = "sentinel.person.5521@example.com";
        String note = "СЕНТИНЕЛ-БЕЛЕЖКА-5521";
        Req request = req().name(name).phone(phone).email(email).note(note);
        List<MvcResult> results = new ArrayList<>();
        results.add(book(request));
        results.add(book(request.copy().note("променена бележка СЕНТИНЕЛ-БЕЛЕЖКА-5521")));
        results.add(book(req().name(name).phone(phone).email(email).note(note)));
        results.add(book(req().name(name).phone(phone).email(email).slug("unknown-studio")));
        results.add(book(req().name(name).phone("x").email(email).note(note)));
        results.add(book(req().name(name).phone(phone).email(email).service(UUID.randomUUID())));

        for (MvcResult result : results.subList(1, results.size())) {
            assertThat(text(result.getResponse()))
                    .doesNotContain("СЕНТИНЕЛ")
                    .doesNotContain("5521")
                    .doesNotContain("885551234")
                    .doesNotContain("sentinel.person")
                    .doesNotContain(request.attemptId);
        }
        assertThat(output.getAll())
                .doesNotContain("СЕНТИНЕЛ")
                .doesNotContain("5521")
                .doesNotContain("885551234")
                .doesNotContain("sentinel.person")
                .doesNotContain(request.attemptId);
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
        assertThat(body.get("title").asString()).isEqualTo("Заявката не може да бъде изпълнена.");
        // The default "about:blank" type is omitted; nothing beyond the fixed members may appear.
        assertThat(keys(body)).containsExactlyInAnyOrderElementsOf(
                body.has("fieldErrors")
                        ? List.of("title", "status", "detail", "instance", "code", "fieldErrors")
                        : List.of("title", "status", "detail", "instance", "code"));
    }
}
