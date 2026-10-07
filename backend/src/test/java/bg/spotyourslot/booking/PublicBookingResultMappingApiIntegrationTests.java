package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The HTTP mapping of every {@link BookingResult} through the complete filter chain, with the
 * orchestration replaced by a mock that returns each outcome.
 *
 * <p><b>Evidence boundary.</b> The injected outcomes prove only the status, code, message, headers,
 * and allowlist of the HTTP contract. What actually happens in a transaction (commit, rollback,
 * retry, replay, and the classification of an uncertain commit) is proven by the Phase 4 tests, and
 * {@code PublicBookingCreationApiIntegrationTests} shows real PostgreSQL faults producing the two 503
 * results.
 */
class PublicBookingResultMappingApiIntegrationTests extends PublicBookingApiIntegrationTest {
    @MockitoBean GuestBooking orchestration;

    private static final Instant START = Instant.parse("2026-10-01T07:00:00Z");

    private static BookedAppointment appointment(BookedAppointment.Status status) {
        return new BookedAppointment(
                "ABCDEFGHJK",
                status,
                "Подстригване",
                30,
                new BigDecimal("25.00"),
                "Мария Тестова",
                START,
                START.plusSeconds(1800),
                "Europe/Sofia");
    }

    private MvcResult post(BookingResult result) throws Exception {
        when(orchestration.book(any())).thenReturn(result);
        return book(req());
    }

    private void assertProblem(MvcResult result, int status, String code, String detail) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode body = tree(result);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("detail").asString()).isEqualTo(detail);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("title").asString()).isEqualTo("Заявката не може да бъде изпълнена.");
        assertThat(body.get("instance").asString()).isEqualTo(FIXED_INSTANCE);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeaderValues("Set-Cookie")).isEmpty();
    }

    @Test
    void aCreatedAppointmentIs201WithTheAllowlistedConfirmation() throws Exception {
        MvcResult result = post(new BookingResult.Created(appointment(BookedAppointment.Status.CONFIRMED)));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(text(result.getResponse())).isEqualTo(
                "{\"reference\":\"ABCDEFGHJK\",\"status\":\"CONFIRMED\","
                        + "\"service\":{\"name\":\"Подстригване\",\"durationMinutes\":30,\"price\":25.00},"
                        + "\"staff\":{\"displayName\":\"Мария Тестова\"},"
                        + "\"start\":\"2026-10-01T10:00:00+03:00\",\"end\":\"2026-10-01T10:30:00+03:00\","
                        + "\"timezone\":\"Europe/Sofia\"}");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("Location")).isNull();
        assertThat(result.getResponse().getHeader("Retry-After")).isNull();
    }

    @Test
    void aReplayIs200IncludingACancelledAppointment() throws Exception {
        MvcResult confirmed = post(new BookingResult.Replayed(appointment(BookedAppointment.Status.CONFIRMED)));
        MvcResult cancelled = post(new BookingResult.Replayed(appointment(BookedAppointment.Status.CANCELLED)));

        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(confirmed).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(cancelled).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(keys(tree(cancelled)))
                .containsExactly("reference", "status", "service", "staff", "start", "end", "timezone");
    }

    @Test
    void aRepeatedWallClockHourKeepsItsOwnOffset() throws Exception {
        BookedAppointment second = new BookedAppointment(
                "ABCDEFGHJK", BookedAppointment.Status.CONFIRMED, "Услуга", 60, new BigDecimal("1.00"),
                "Мария", Instant.parse("2026-10-25T01:00:00Z"), Instant.parse("2026-10-25T02:00:00Z"),
                "Europe/Sofia");

        MvcResult result = post(new BookingResult.Created(second));

        // 01:00Z is the second 03:00 of the day (+02:00), after the first at 00:00Z (+03:00).
        assertThat(tree(result).get("start").asString()).isEqualTo("2026-10-25T03:00:00+02:00");
        assertThat(tree(result).get("end").asString()).isEqualTo("2026-10-25T04:00:00+02:00");
    }

    @Test
    void everyRejectionHasItsOwnPreciseStatusCodeAndSafeBulgarianMessage() throws Exception {
        Map<BookingResult, String[]> expected = new LinkedHashMap<>();
        expected.put(new BookingResult.BusinessUnavailable(), new String[] {
            "404", "BUSINESS_PAGE_UNAVAILABLE", "Страницата не е налична."});
        expected.put(new BookingResult.ServiceUnavailable(), new String[] {
            "409", "BOOKING_SERVICE_UNAVAILABLE", "Избраната услуга вече не е налична. Изберете друга услуга."});
        expected.put(new BookingResult.StaffMemberUnavailable(), new String[] {
            "409", "BOOKING_STAFF_UNAVAILABLE",
            "Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“."});
        expected.put(new BookingResult.SlotUnavailable(), new String[] {
            "409", "BOOKING_SLOT_UNAVAILABLE", "Избраният час вече не е свободен. Изберете друг час."});
        expected.put(new BookingResult.IdentityConflict(), new String[] {
            "409", "BOOKING_NOT_COMPLETED_ONLINE",
            "Не можем да завършим резервацията онлайн. Моля, свържете се с бизнеса."});
        expected.put(new BookingResult.AttemptMismatch(), new String[] {
            "409", "BOOKING_ATTEMPT_MISMATCH",
            "Тази заявка вече е използвана с други данни. Започнете резервацията отново."});
        expected.put(new BookingResult.TemporarilyUnavailable(), new String[] {
            "503", "BOOKING_TEMPORARILY_UNAVAILABLE", "Резервацията не беше направена. Опитайте отново."});
        expected.put(new BookingResult.OutcomeUncertain(), new String[] {
            "503", "BOOKING_OUTCOME_UNCERTAIN",
            "Не получихме потвърждение за резервацията. Опитайте отново."});

        for (var entry : expected.entrySet()) {
            MvcResult result = post(entry.getKey());
            assertProblem(result, Integer.parseInt(entry.getValue()[0]), entry.getValue()[1], entry.getValue()[2]);
            assertThat(tree(result).has("fieldErrors")).isFalse();
        }
    }

    @Test
    void theKnownRollbackAndTheUncertainOutcomeAreExplicitlyDistinguishable() throws Exception {
        MvcResult rollback = post(new BookingResult.TemporarilyUnavailable());
        MvcResult uncertain = post(new BookingResult.OutcomeUncertain());

        assertThat(rollback.getResponse().getStatus()).isEqualTo(503);
        assertThat(uncertain.getResponse().getStatus()).isEqualTo(503);
        assertThat(tree(rollback).get("code").asString()).isEqualTo("BOOKING_TEMPORARILY_UNAVAILABLE");
        assertThat(tree(uncertain).get("code").asString()).isEqualTo("BOOKING_OUTCOME_UNCERTAIN");
        assertThat(tree(rollback).get("detail").asString()).isNotEqualTo(tree(uncertain).get("detail").asString());
        // Both ask the client to wait briefly before repeating the same attempt.
        assertThat(rollback.getResponse().getHeader("Retry-After")).isEqualTo("2");
        assertThat(uncertain.getResponse().getHeader("Retry-After")).isEqualTo("2");
    }

    @Test
    void onlyTheRetryableOutcomesCarryRetryAfter() throws Exception {
        List<BookingResult> withoutRetryAfter = List.of(
                new BookingResult.BusinessUnavailable(),
                new BookingResult.ServiceUnavailable(),
                new BookingResult.StaffMemberUnavailable(),
                new BookingResult.SlotUnavailable(),
                new BookingResult.IdentityConflict(),
                new BookingResult.AttemptMismatch(),
                new BookingResult.InvalidRequest(EnumSet.of(BookingField.DISPLAY_NAME)),
                new BookingResult.Created(appointment(BookedAppointment.Status.CONFIRMED)),
                new BookingResult.Replayed(appointment(BookedAppointment.Status.CONFIRMED)));

        for (BookingResult result : withoutRetryAfter) {
            assertThat(post(result).getResponse().getHeader("Retry-After")).as(result.toString()).isNull();
        }
    }

    @Test
    void everyInvalidFieldHasItsOwnApprovedFieldErrorAndTheTechnicalFieldsHaveNone() throws Exception {
        Map<BookingField, String[]> expected = new LinkedHashMap<>();
        expected.put(BookingField.DISPLAY_NAME, new String[] {"displayName", "Въведете име до 200 знака."});
        expected.put(BookingField.PHONE, new String[] {"phone", "Въведеният телефонен номер не е валиден."});
        expected.put(BookingField.EMAIL, new String[] {"email", "Въведеният имейл адрес не е валиден."});
        expected.put(BookingField.CONTACT, new String[] {"contact", "Въведете телефон или имейл."});
        expected.put(BookingField.NOTE, new String[] {
            "note", "Бележката може да съдържа най-много 500 знака."});

        for (var entry : expected.entrySet()) {
            MvcResult result = post(new BookingResult.InvalidRequest(EnumSet.of(entry.getKey())));
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            JsonNode fields = tree(result).get("fieldErrors");
            assertThat(keys(fields)).containsExactly(entry.getValue()[0]);
            assertThat(fields.get(entry.getValue()[0]).asString()).isEqualTo(entry.getValue()[1]);
        }
        for (BookingField technical : List.of(BookingField.ATTEMPT_ID, BookingField.START)) {
            MvcResult result = post(new BookingResult.InvalidRequest(EnumSet.of(technical)));
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(tree(result).has("fieldErrors")).isFalse();
        }
        MvcResult all = post(new BookingResult.InvalidRequest(EnumSet.allOf(BookingField.class)));
        assertThat(keys(tree(all).get("fieldErrors")))
                .containsExactly("displayName", "phone", "email", "contact", "note");
    }

    @Test
    void anUnexpectedFailureIsAFixedInternalErrorThatEchoesNothing() throws Exception {
        for (RuntimeException failure : List.of(
                new IllegalStateException("SQL: select * from customer where phone = '+359888111222'"),
                new BookingOrchestrationFailure(),
                new NullPointerException("SECRET-NPE"))) {
            org.mockito.Mockito.doThrow(failure).when(orchestration).book(any());

            MvcResult result = book(req());

            assertProblem(result, 500, "INTERNAL_ERROR", "Възникна неочаквана грешка.");
            assertThat(text(result.getResponse())).doesNotContain("SECRET").doesNotContain("select")
                    .doesNotContain("111222").doesNotContain("Guest");
        }
    }

    @Test
    void theControllerPassesTheRouteAndTheBodyThroughUnchangedAndAddsNothing() throws Exception {
        when(orchestration.book(any())).thenReturn(new BookingResult.SlotUnavailable());
        UUID staff = UUID.randomUUID();
        Req request = req().staff(staff).name("  Иван   Петров ").phone("0888 111 222").email("A@B.bg")
                .note("бележка");

        MvcResult result = postJson(PREFIX + "/Studio-A/bookings", body(request).toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        ArgumentCaptor<GuestBookingRequest> captured = ArgumentCaptor.forClass(GuestBookingRequest.class);
        verify(orchestration).book(captured.capture());
        GuestBookingRequest sent = captured.getValue();
        // Normalization and validation belong to the orchestration; the adapter forwards raw values.
        assertThat(sent.businessSlug()).isEqualTo("Studio-A");
        assertThat(sent.attemptId()).isEqualTo(request.attemptId);
        assertThat(sent.serviceId()).isEqualTo(request.service);
        assertThat(sent.staffMemberId()).isEqualTo(staff);
        assertThat(sent.start()).isEqualTo(request.start);
        assertThat(sent.displayName()).isEqualTo("  Иван   Петров ");
        assertThat(sent.phone()).isEqualTo("0888 111 222");
        assertThat(sent.email()).isEqualTo("A@B.bg");
        assertThat(sent.note()).isEqualTo("бележка");
    }

    @Test
    void anAbsentStaffPreferenceAndAbsentOptionalFieldsReachTheOrchestrationAsNull() throws Exception {
        when(orchestration.book(any())).thenReturn(new BookingResult.SlotUnavailable());
        Req request = req().phone(null).email("only@example.com");
        var body = body(request);
        body.remove("note");
        ((tools.jackson.databind.node.ObjectNode) body.get("customer")).remove("phone");

        postJson(bookingsUrl(request.slug), body.toString());

        ArgumentCaptor<GuestBookingRequest> captured = ArgumentCaptor.forClass(GuestBookingRequest.class);
        verify(orchestration).book(captured.capture());
        assertThat(captured.getValue().staffMemberId()).isNull();
        assertThat(captured.getValue().phone()).isNull();
        assertThat(captured.getValue().note()).isNull();
        assertThat(captured.getValue().email()).isEqualTo("only@example.com");
    }

    @Test
    void aStructurallyInvalidBodyNeverReachesTheOrchestration() throws Exception {
        Req request = req();
        var body = body(request);
        body.put("unexpected", "x");

        MvcResult result = postJson(bookingsUrl(request.slug), body.toString());

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        org.mockito.Mockito.verifyNoInteractions(orchestration);
    }
}
