package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.GuestBookingRequest.ReviewedService;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.FingerprintEncodings;
import bg.spotyourslot.booking.domain.NormalizedBookingRequest;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The public HTTP contract of multi-Service visits (ADR-0027 section 8, phase M4) over real
 * PostgreSQL through the complete filter chain: both request shapes, the visit response, replay
 * (encoding 2 and a genuine historical encoding 1 row), the review-consistency outcome and its
 * precedence, and the two Service-selection read routes. Everything is synthetic.
 *
 * <p>The clock is Tuesday 2026-09-29 11:00 in Sofia and every slot is on Thursday 2026-10-01.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "spotyourslot.booking.rate-limit.booking-per-contact=50",
    "spotyourslot.booking.rate-limit.booking-per-address-business=50",
    "spotyourslot.booking.rate-limit.booking-per-address=50"
})
class PublicBookingVisitApiIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final List<String> VISIT_KEYS = List.of(
            "reference", "status", "services", "totalDurationMinutes", "totalPrice", "staff", "start", "end",
            "timezone");
    private static final List<String> LEGACY_KEYS =
            List.of("reference", "status", "service", "staff", "start", "end", "timezone");
    private static final List<String> REQUEST_KEYS =
            List.of("attemptId", "services", "staffMemberId", "start", "customer", "note");
    private static final String REVIEW_CHANGED =
            "Условията на избраните услуги са променени. Прегледайте ги и потвърдете отново.";
    private static final String TOO_LONG =
            "Избраните услуги са с обща продължителност над 8 часа. Изберете по-малко услуги.";
    private static final String SERVICES_ERROR =
            "Изберете от 1 до 5 различни услуги с обща продължителност до 8 часа.";
    /** The synthetic default test key (version 1), Base64 of a non-secret text. */
    private static final String TEST_KEY = "Tk9OLVNFQ1JFVC1URVNULUtFWTp0ZXN0LXByb2ZpbGUtb25seS1zeW50aGV0aWMtMDAwMQ==";

    @Autowired AppointmentStore store;

    // ---- fixtures ----------------------------------------------------------------------------

    /** An active Service of the tenant's Business, assigned to the tenant's StaffMember. */
    private UUID svc(String name, int minutes, String price) {
        UUID id = fixtures.service(tenant.business(), minutes);
        jdbc.sql("UPDATE service SET name = :name, price = CAST(:price AS numeric) WHERE id = :id")
                .param("name", name).param("price", price).param("id", id).update();
        availabilityFixtures.assign(tenant.business(), tenant.staff(), id);
        return id;
    }

    private ReviewedService claim(UUID service) {
        return jdbc.sql("SELECT duration_minutes, price FROM service WHERE id = :id")
                .param("id", service)
                .query((resultSet, row) -> new ReviewedService(
                        service,
                        resultSet.getInt("duration_minutes"),
                        resultSet.getBigDecimal("price").movePointRight(2).longValueExact()))
                .single();
    }

    private List<ReviewedService> seen(UUID... services) {
        List<ReviewedService> claims = new ArrayList<>();
        for (UUID service : services) {
            claims.add(claim(service));
        }
        return claims;
    }

    private MvcResult visit(Req request, List<ReviewedService> claims) throws Exception {
        return postJson(bookingsUrl(request.slug), visitBody(request, claims).toString());
    }

    private long lineCount() {
        return jdbc.sql("SELECT count(*) FROM appointment_service").query(Long.class).single();
    }

    private void assertProblem(MvcResult result, int status, String code, String detail) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        JsonNode body = tree(result);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("detail").asString()).isEqualTo(detail);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("instance").asString()).isEqualTo(FIXED_INSTANCE);
        assertThat(body.get("title").asString()).isEqualTo("Заявката не може да бъде изпълнена.");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeaderValues("Set-Cookie")).isEmpty();
        assertThat(keys(body)).containsExactlyInAnyOrderElementsOf(
                body.has("fieldErrors")
                        ? List.of("title", "status", "detail", "instance", "code", "fieldErrors")
                        : List.of("title", "status", "detail", "instance", "code"));
    }

    private void assertNothingWritten() {
        assertThat(totalAppointments()).isZero();
        assertThat(lineCount()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    // ---- creation and the exact key sets ----------------------------------------------------------

    @Test
    void aNewShapeVisitIsCreatedWithTheExactKeySetsAndTheLinesInExecutionOrder() throws Exception {
        UUID late = svc("Zulu", 30, "10.00");
        UUID early = svc("Alpha", 45, "20.50");
        Req request = req().staff(tenant.staff());
        // The request lists the Services in the opposite of the profile order.
        var body = visitBody(request, seen(late, early));
        assertThat(keys(body)).containsExactlyInAnyOrderElementsOf(REQUEST_KEYS);
        assertThat(keys(body.get("services").get(0)))
                .containsExactly("serviceId", "reviewedDurationMinutes", "reviewedPriceCents");

        MvcResult created = postJson(bookingsUrl(request.slug), body.toString());

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode visit = tree(created);
        assertThat(keys(visit)).containsExactlyElementsOf(VISIT_KEYS);
        assertThat(visit.get("services")).hasSize(2);
        assertThat(keys(visit.get("services").get(0))).containsExactly("name", "durationMinutes", "price");
        assertThat(visit.get("services").get(0).get("name").asString()).isEqualTo("Alpha");
        assertThat(visit.get("services").get(1).get("name").asString()).isEqualTo("Zulu");
        assertThat(visit.get("totalDurationMinutes").asInt()).isEqualTo(75);
        assertThat(visit.get("totalPrice").decimalValue()).isEqualByComparingTo("30.50");
        assertThat(text(created.getResponse())).contains("\"totalPrice\":30.50");
        assertThat(keys(visit.get("staff"))).containsExactly("displayName");
        assertThat(visit.get("start").asString()).isEqualTo("2026-10-01T10:00:00+03:00");
        assertThat(visit.get("end").asString()).isEqualTo("2026-10-01T11:15:00+03:00");
        assertThat(visit.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(created.getResponse().getHeaderValues("Set-Cookie")).isEmpty();
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(lineCount()).isEqualTo(2);
    }

    @Test
    void theResponseNeverContainsAnInternalIdentifierContactOrAuditValue() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        jdbc.sql("UPDATE service SET name = 'Основна' WHERE id = :id").param("id", tenant.service()).update();
        Req request = req().phone("+359888765432").note("лична бележка 7731").staff(tenant.staff());

        MvcResult created = visit(request, seen(tenant.service(), second));

        String everything = text(created.getResponse());
        for (String forbidden : List.of(
                tenant.service().toString(), second.toString(), tenant.staff().toString(),
                tenant.business().toString(), request.attemptId, "885765432", "7731", "customer", "version",
                "fingerprint", "createdAt", "attempt", "id\"")) {
            assertThat(everything).as(forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    void oneAndFiveServicesAreBookedAndTheMaximumReviewedPriceIsExact() throws Exception {
        UUID staff = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), staff, "00:00", "23:59");
        List<UUID> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            UUID service = fixtures.service(tenant.business(), 96);
            jdbc.sql("UPDATE service SET name = :name, price = 9999999999.99 WHERE id = :id")
                    .param("name", "Premium " + index).param("id", service).update();
            availabilityFixtures.assign(tenant.business(), staff, service);
            five.add(service);
        }
        MvcResult one = visit(req().staff(tenant.staff()).start("09:00"), seen(tenant.service()));
        Req max = req().staff(staff).start("08:00");

        MvcResult created = visit(max, seen(five.toArray(UUID[]::new)));

        assertThat(one.getResponse().getStatus()).isEqualTo(201);
        assertThat(tree(one).get("services")).hasSize(1);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(tree(created).get("services")).hasSize(5);
        assertThat(tree(created).get("totalDurationMinutes").asInt()).isEqualTo(480);
        assertThat(text(created.getResponse())).contains("\"totalPrice\":49999999999.95");
        assertThat(tree(created).get("end").asString()).isEqualTo("2026-10-01T16:00:00+03:00");
        // The same 480-minute visit replays with the exact total.
        MvcResult replay = postJson(bookingsUrl(max.slug), visitBody(max, seen(five.toArray(UUID[]::new))).toString());
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(replay.getResponse())).isEqualTo(text(created.getResponse()));
    }

    @Test
    void theLegacyShapeStillGetsTheLegacyKeysAndIsNotReviewChecked() throws Exception {
        jdbc.sql("UPDATE service SET price = 99.00 WHERE id = :id").param("id", tenant.service()).update();

        MvcResult created = book(req());

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(keys(tree(created))).containsExactlyElementsOf(LEGACY_KEYS);
        assertThat(tree(created).get("service").get("price").decimalValue()).isEqualByComparingTo("99.00");
    }

    // ---- replay and identity ----------------------------------------------------------------------------------

    @Test
    void everyPermutationOfASelectionIsTheSameBookingIdentityAndReplays() throws Exception {
        UUID a = svc("Alpha", 30, "10.00");
        UUID b = svc("Bravo", 30, "11.00");
        UUID c = svc("Charlie", 30, "12.00");
        Req request = req().staff(tenant.staff());
        List<ReviewedService> claims = seen(a, b, c);
        MvcResult created = visit(request, claims);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        int permutations = 0;
        for (int first = 0; first < 3; first++) {
            for (int second = 0; second < 3; second++) {
                for (int third = 0; third < 3; third++) {
                    if (first == second || first == third || second == third) {
                        continue;
                    }
                    MvcResult replay = postJson(bookingsUrl(request.slug), visitBody(
                            request.copy(), List.of(claims.get(first), claims.get(second), claims.get(third)))
                            .toString());
                    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
                    assertThat(text(replay.getResponse())).isEqualTo(text(created.getResponse()));
                    permutations++;
                }
            }
        }
        assertThat(permutations).isEqualTo(6);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(lineCount()).isEqualTo(3);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aChangedClaimOrFieldUnderTheSameAttemptIsAMismatchEvenAfterTheTermsChanged() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        Req request = req().staff(tenant.staff());
        List<ReviewedService> claims = seen(tenant.service(), second);
        assertThat(visit(request, claims).getResponse().getStatus()).isEqualTo(201);
        jdbc.sql("UPDATE service SET price = 99.00 WHERE id = :id").param("id", second).update();

        // The attempt is already used: the replay lookup decides before the review check.
        MvcResult otherClaims = visit(request.copy(), seen(tenant.service(), second));
        MvcResult otherNote = visit(request.copy().note("друга"), claims);
        MvcResult fewer = visit(request.copy(), seen(tenant.service()));

        for (MvcResult result : List.of(otherClaims, otherNote, fewer)) {
            assertProblem(result, 409, "BOOKING_ATTEMPT_MISMATCH",
                    "Тази заявка вече е използвана с други данни. Започнете резервацията отново.");
        }
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        // The original claims, unchanged, replay from the stored snapshots although the terms changed.
        MvcResult replay = visit(request.copy(), claims);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(replay).get("totalPrice").decimalValue()).isEqualByComparingTo("30.50");
    }

    @Test
    void aReplayReturnsTheStoredSnapshotsAfterNameDurationPriceAndActivityChanged() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        Req request = req().staff(tenant.staff());
        List<ReviewedService> claims = seen(tenant.service(), second);
        MvcResult created = visit(request, claims);
        jdbc.sql("UPDATE service SET name = 'Преименувана', duration_minutes = 50, price = 99.99 WHERE id = :id")
                .param("id", second).update();
        availabilityFixtures.setServiceActive(tenant.service(), false);

        MvcResult replay = visit(request.copy(), claims);

        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(replay.getResponse())).isEqualTo(text(created.getResponse()));
        assertThat(text(replay.getResponse())).doesNotContain("Преименувана");
    }

    @Test
    void aCancelledVisitReplaysWithItsLinesAndTheCancelledStatus() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        Req request = req().staff(tenant.staff());
        List<ReviewedService> claims = seen(tenant.service(), second);
        MvcResult created = visit(request, claims);
        fixtures.cancelAllConfirmed();

        MvcResult replay = visit(request.copy(), claims);

        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(replay).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(tree(replay).get("services")).isEqualTo(tree(created).get("services"));
    }

    @Test
    void aHistoricalEncodingOneRowReplaysThroughTheLegacyShapeOnly() throws Exception {
        Req request = req().staff(tenant.staff()).phone("+359888333444").note("Бележка");
        NormalizedBookingRequest normalized = NormalizedBookingRequest.normalize(
                request.attemptId, request.service, request.staff, request.start,
                request.name, request.phone, request.email, request.note);
        byte[] fingerprint = hmac(TEST_KEY,
                FingerprintEncodings.forVersion(1).orElseThrow().encode(tenant.business(), normalized));
        var historical = AppointmentFixtures.appointment(
                new AppointmentFixtures.Tenant(tenant.business(), tenant.service(), tenant.staff(),
                        fixtures.customer(tenant.business())),
                AppointmentSource.ONLINE, request.start, 30,
                new BookingAttempt(normalized.attemptId().hash(), fingerprint, 1, 1));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> store.insert(historical));

        MvcResult legacy = book(request.copy());
        MvcResult changed = book(request.copy().note("Друга"));
        MvcResult reviewedSameService = visit(request.copy(), seen(tenant.service()));
        MvcResult severalServices = visit(request.copy(), seen(tenant.service(), svc("Alpha", 15, "5.00")));

        // The legacy request replays with the legacy shape and its existing semantics.
        assertThat(legacy.getResponse().getStatus()).isEqualTo(200);
        assertThat(keys(tree(legacy))).containsExactlyElementsOf(LEGACY_KEYS);
        assertThat(tree(legacy).get("reference").asString()).isEqualTo(historical.publicReference());
        assertProblem(changed, 409, "BOOKING_ATTEMPT_MISMATCH",
                "Тази заявка вече е използвана с други данни. Започнете резервацията отново.");
        // A reviewed or multi-Service request is a different request than any encoding-1 row.
        assertProblem(reviewedSameService, 409, "BOOKING_ATTEMPT_MISMATCH",
                "Тази заявка вече е използвана с други данни. Започнете резервацията отново.");
        assertProblem(severalServices, 409, "BOOKING_ATTEMPT_MISMATCH",
                "Тази заявка вече е използвана с други данни. Започнете резервацията отново.");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        // The same row, represented as a one-line visit, is valid for the revised response model.
        var stored = store.find(tenant.business(), historical.id()).orElseThrow();
        assertThat(stored.serviceLines()).hasSize(1);
        assertThat(stored.serviceLines().get(0).serviceName()).isEqualTo(stored.serviceName());
        assertThat(stored.serviceLines().get(0).priceEur()).isEqualTo(stored.priceEur());
        assertThat(stored.serviceLines().get(0).durationMinutes()).isEqualTo(stored.durationMinutes());
    }

    @Test
    void aNewLegacyShapedAttemptIsEncodingTwoAndReplaysThroughHttp() throws Exception {
        Req request = req().staff(tenant.staff());
        MvcResult created = book(request);

        MvcResult replay = book(request.copy());

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(replay.getResponse())).isEqualTo(text(created.getResponse()));
        assertThat(jdbc.sql("SELECT fingerprint_encoding_version FROM appointment")
                .query(Integer.class).single()).isEqualTo(2);
    }

    // ---- the review-consistency outcome -----------------------------------------------------------------------

    @Test
    void aChangedDurationOrPriceOrBothOrOneServiceOfASetIsReviewChangedWithNothingWritten() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        List<ReviewedService> reviewed = seen(tenant.service(), second);

        jdbc.sql("UPDATE service SET duration_minutes = 31 WHERE id = :id").param("id", tenant.service()).update();
        MvcResult duration = visit(req().staff(tenant.staff()), reviewed);
        jdbc.sql("UPDATE service SET duration_minutes = 30 WHERE id = :id").param("id", tenant.service()).update();
        jdbc.sql("UPDATE service SET price = 20.51 WHERE id = :id").param("id", second).update();
        MvcResult price = visit(req().staff(tenant.staff()), reviewed);
        jdbc.sql("UPDATE service SET duration_minutes = 46 WHERE id = :id").param("id", second).update();
        MvcResult both = visit(req().staff(tenant.staff()), reviewed);
        // Only one Service of the set differs; the other still matches.
        jdbc.sql("UPDATE service SET duration_minutes = 45, price = 20.50 WHERE id = :id").param("id", second).update();
        jdbc.sql("UPDATE service SET price = 10.01 WHERE id = :id").param("id", tenant.service()).update();
        MvcResult oneOfTwo = visit(req().staff(tenant.staff()), reviewed);

        for (MvcResult result : List.of(duration, price, both, oneOfTwo)) {
            assertProblem(result, 409, "BOOKING_REVIEW_CHANGED", REVIEW_CHANGED);
            assertThat(text(result.getResponse())).doesNotContain("31").doesNotContain("20.5").doesNotContain("46");
            assertThat(result.getResponse().getHeader("Retry-After")).isNull();
        }
        assertNothingWritten();
    }

    @Test
    void aChangedNameOrDescriptionIsNotAReviewChangeAndTheNewNameIsStored() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        List<ReviewedService> reviewed = seen(tenant.service(), second);
        jdbc.sql("UPDATE service SET name = 'Преименувана', description = 'Ново описание' WHERE id = :id")
                .param("id", second).update();

        MvcResult created = visit(req().staff(tenant.staff()), reviewed);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(text(created.getResponse())).contains("Преименувана");
    }

    @Test
    void aMissingForeignOrInactiveServiceTakesPrecedenceOverAChangedTerm() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        var other = openTenant();
        List<ReviewedService> stale = List.of(
                new ReviewedService(tenant.service(), 1, 1), new ReviewedService(second, 1, 1));
        String unavailable = "Избраната услуга вече не е налична. Изберете друга услуга.";

        // Control: every term differs, so the request is a review change.
        assertProblem(visit(req().staff(tenant.staff()), stale), 409, "BOOKING_REVIEW_CHANGED", REVIEW_CHANGED);
        availabilityFixtures.setServiceActive(second, false);
        assertProblem(visit(req().staff(tenant.staff()), stale), 409, "BOOKING_SERVICE_UNAVAILABLE", unavailable);
        assertProblem(visit(req(), List.of(new ReviewedService(tenant.service(), 1, 1),
                new ReviewedService(UUID.randomUUID(), 1, 1))), 409, "BOOKING_SERVICE_UNAVAILABLE", unavailable);
        assertProblem(visit(req(), List.of(new ReviewedService(tenant.service(), 1, 1),
                new ReviewedService(other.service(), 1, 1))), 409, "BOOKING_SERVICE_UNAVAILABLE", unavailable);
        assertNothingWritten();
    }

    @Test
    void aChangedTermTakesPrecedenceOverAVanishedSlotAndOverAnIdentityConflict() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        book(req().phone("+359888555001").start("09:00"));
        book(req().phone(null).email("held@example.com").start("09:30"));
        long customers = customerCount(tenant.business());
        long appointments = appointmentCount(tenant.business());
        List<ReviewedService> reviewed = seen(tenant.service(), second);
        // Control with the right terms: the identity is split, so it is not completed online.
        MvcResult conflict = visit(req().phone("+359888555001").email("held@example.com").start("10:30"), reviewed);
        assertProblem(conflict, 409, "BOOKING_NOT_COMPLETED_ONLINE",
                "Телефонът и имейлът не съответстват. Проверете ги или въведете само единия контакт.");
        jdbc.sql("UPDATE service SET price = 21.00 WHERE id = :id").param("id", second).update();

        MvcResult identity = visit(req().phone("+359888555001").email("held@example.com").start("10:30"), reviewed);
        MvcResult slot = visit(req().start("13:00"), reviewed);

        assertProblem(identity, 409, "BOOKING_REVIEW_CHANGED", REVIEW_CHANGED);
        assertProblem(slot, 409, "BOOKING_REVIEW_CHANGED", REVIEW_CHANGED);
        assertThat(customerCount(tenant.business())).isEqualTo(customers);
        assertThat(appointmentCount(tenant.business())).isEqualTo(appointments);
        assertThat(lineCount()).isEqualTo(appointments);
    }

    @Test
    void aVanishedSlotOrAnUnsupportedStaffMemberIsStillTheirOwnConflictWhenTheTermsMatch() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        UUID partial = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), partial, "09:00", "12:00");
        List<ReviewedService> reviewed = seen(tenant.service(), second);

        assertProblem(visit(req().start("13:00"), reviewed), 409, "BOOKING_SLOT_UNAVAILABLE",
                "Избраният час вече не е свободен. Изберете друг час.");
        assertProblem(visit(req().staff(partial), reviewed), 409, "BOOKING_STAFF_UNAVAILABLE",
                "Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.");
        assertNothingWritten();
    }

    @Test
    void aReviewChangeLeavesTheSameAttemptFreeForAFreshConfirmation() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        Req request = req().staff(tenant.staff());
        List<ReviewedService> stale = seen(tenant.service(), second);
        jdbc.sql("UPDATE service SET price = 21.00 WHERE id = :id").param("id", second).update();

        assertProblem(visit(request, stale), 409, "BOOKING_REVIEW_CHANGED", REVIEW_CHANGED);
        MvcResult refreshed = visit(request.copy(), seen(tenant.service(), second));

        // Nothing was stored for the rejected attempt, so the same identifier may be confirmed anew.
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(201);
        assertThat(tree(refreshed).get("totalPrice").decimalValue()).isEqualByComparingTo("31.00");
    }

    // ---- malformed requests are rejected before any transaction ----------------------------------------

    @Test
    void everyMalformedSelectionIsAServicesValidationErrorBeforeTheBookingTransaction() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        Req base = req();
        String slug = base.slug;
        String valid = visitBody(base, seen(tenant.service())).get("services").get(0).toString();
        List<String> services = new ArrayList<>(List.of(
                "[]", "{}", "\"x\"", "7", "[null]", "[" + valid + ",null]", "[7]", "[\"x\"]",
                "[" + valid + "," + valid + "]",
                "[{\"serviceId\":\"" + tenant.service() + "\"}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":30}]",
                "[{\"serviceId\":null,\"reviewedDurationMinutes\":30,\"reviewedPriceCents\":1000}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":\"30\","
                        + "\"reviewedPriceCents\":1000}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":30.0,"
                        + "\"reviewedPriceCents\":1000}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":30,"
                        + "\"reviewedPriceCents\":10.5}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":0,"
                        + "\"reviewedPriceCents\":1000}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":481,"
                        + "\"reviewedPriceCents\":1000}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":30,"
                        + "\"reviewedPriceCents\":-1}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":30,"
                        + "\"reviewedPriceCents\":1000000000000}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":30,"
                        + "\"reviewedPriceCents\":1000,\"extra\":1}]",
                "[{\"serviceId\":\"" + tenant.service() + "\",\"reviewedDurationMinutes\":240,"
                        + "\"reviewedPriceCents\":0},{\"serviceId\":\"" + second
                        + "\",\"reviewedDurationMinutes\":241,\"reviewedPriceCents\":0}]"));
        StringBuilder six = new StringBuilder("[");
        for (int index = 0; index < 6; index++) {
            six.append(index == 0 ? "" : ",").append("{\"serviceId\":\"").append(UUID.randomUUID())
                    .append("\",\"reviewedDurationMinutes\":10,\"reviewedPriceCents\":100}");
        }
        services.add(six.append("]").toString());
        TransactionLog.clear();

        for (String malformed : services) {
            String body = body(base).toString().replace(
                    "\"serviceId\":\"" + tenant.service() + "\",", "\"services\":" + malformed + ",");
            MvcResult result = onBookingThread("malformed", () -> postJson(bookingsUrl(slug), body));
            assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
            assertThat(tree(result).get("fieldErrors").toString())
                    .as(malformed).isEqualTo("{\"services\":\"" + SERVICES_ERROR + "\"}");
        }
        // Both shapes together are rejected the same way; neither is a plain validation error.
        String both = body(base).toString().replace("\"serviceId\"", "\"services\":[" + valid + "],\"serviceId\"");
        MvcResult bothResult = postJson(bookingsUrl(slug), both);
        assertProblem(bothResult, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        MvcResult neither = postJson(bookingsUrl(slug), body(base).toString().replace(
                "\"serviceId\":\"" + tenant.service() + "\",", ""));
        assertProblem(neither, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        assertThat(TransactionLog.begins(threadOf("malformed"))).isEmpty();
        assertThat(hooks.observations()).isEmpty();
        assertNothingWritten();
    }

    @Test
    void unknownKeysAtEveryLevelAreStillRejected() throws Exception {
        Req request = req();
        String valid = visitBody(request, seen(tenant.service())).toString();

        for (String body : List.of(
                valid.replace("\"note\":null", "\"note\":null,\"extra\":1"),
                valid.replace("\"reviewedPriceCents\"", "\"extra\":1,\"reviewedPriceCents\""),
                valid.replace("\"email\":null", "\"email\":null,\"extra\":1"))) {
            assertProblem(postJson(bookingsUrl(request.slug), body), 400, "VALIDATION_ERROR",
                    "Проверете въведените данни.");
        }
        assertNothingWritten();
    }

    // ---- the two Service-selection read routes ------------------------------------------------------------

    private String selectionOptions(String slug, UUID... services) {
        StringBuilder url = new StringBuilder(PREFIX + "/" + slug + "/booking-options");
        char separator = '?';
        for (UUID service : services) {
            url.append(separator).append("serviceId=").append(service);
            separator = '&';
        }
        return url.toString();
    }

    private String selectionAvailability(String slug, String date, UUID... services) {
        StringBuilder url = new StringBuilder(PREFIX + "/" + slug + "/availability");
        char separator = '?';
        for (UUID service : services) {
            url.append(separator).append("serviceId=").append(service);
            separator = '&';
        }
        return url.append(separator).append("date=").append(date).toString();
    }

    private UUID member(String name, boolean schedule, UUID... services) {
        UUID id = fixtures.staffMember(tenant.business(), services[0]);
        jdbc.sql("UPDATE staff_member SET display_name = :name WHERE id = :id")
                .param("name", name).param("id", id).update();
        for (int index = 1; index < services.length; index++) {
            availabilityFixtures.assign(tenant.business(), id, services[index]);
        }
        if (schedule) {
            availabilityFixtures.everyDay(tenant.business(), id, "09:00", "12:00");
        }
        return id;
    }

    @Test
    void theSelectionBookingOptionsReturnTheIntersectionInNameOrderWithTheSingleServiceShape() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        UUID zoe = member("Zoe", true, tenant.service(), second);
        UUID ana = member("Ana", true, tenant.service(), second);
        member("Boris", true, tenant.service());
        String slug = slugOf(tenant.business());

        MvcResult options = getJson(selectionOptions(slug, second, tenant.service()));
        MvcResult swapped = getJson(selectionOptions(slug, tenant.service(), second));

        assertThat(options.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = tree(options);
        assertThat(keys(body)).containsExactly("timezone", "firstDate", "lastDate", "staff");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Sofia");
        assertThat(body.get("firstDate").asString()).isEqualTo("2026-09-29");
        assertThat(body.get("lastDate").asString()).isEqualTo("2026-10-28");
        List<String> ids = new ArrayList<>();
        body.get("staff").forEach(member -> {
            assertThat(keys(member)).containsExactly("id", "displayName");
            ids.add(member.get("displayName").asString());
        });
        // The tenant's own member is assigned to both Services; Boris performs only one.
        assertThat(ids).containsExactly("Ana", "Availability Staff", "Zoe");
        assertThat(body.get("staff").toString()).contains(ana.toString()).contains(zoe.toString());
        // The order of the identifiers carries no meaning.
        assertThat(text(swapped.getResponse())).isEqualTo(text(options.getResponse()));
        assertThat(options.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(options.getResponse().getHeaderValues("Set-Cookie")).isEmpty();
    }

    @Test
    void aOneServiceSelectionEqualsTheExistingSingleServiceRoutes() throws Exception {
        String slug = slugOf(tenant.business());

        assertThat(text(getJson(selectionOptions(slug, tenant.service())).getResponse()))
                .isEqualTo(text(getJson(optionsUrl(slug, tenant.service())).getResponse()));
        assertThat(text(getJson(selectionAvailability(slug, "2026-10-01", tenant.service())).getResponse()))
                .isEqualTo(text(getJson(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01")
                        .getResponse()));
    }

    @Test
    void noStaffMemberForTheSelectionIsDistinctFromEligibleStaffWithoutTime() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), second);
        member("Only second", true, second);
        String slug = slugOf(tenant.business());

        // Nobody performs both: an empty intersection, and no time at all.
        MvcResult none = getJson(selectionOptions(slug, tenant.service(), second));
        MvcResult noneTime = getJson(selectionAvailability(slug, "2026-10-01", tenant.service(), second));
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(none).get("staff")).isEmpty();
        assertThat(noneTime.getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(noneTime).get("slots")).isEmpty();
        assertThat(tree(noneTime).get("availableDates")).isEmpty();

        // A member performs both but has no working time: staff exist, yet nothing is available.
        availabilityFixtures.assign(tenant.business(), tenant.staff(), second);
        jdbc.sql("DELETE FROM staff_working_period WHERE staff_member_id = :id").param("id", tenant.staff()).update();
        MvcResult eligible = getJson(selectionOptions(slug, tenant.service(), second));
        MvcResult noTime = getJson(selectionAvailability(slug, "2026-10-01", tenant.service(), second));
        assertThat(tree(eligible).get("staff")).hasSize(1);
        assertThat(tree(noTime).get("slots")).isEmpty();
    }

    @Test
    void theSelectionAvailabilityOffersTheSummedDurationAndTheExplicitMemberMustSupportEverything()
            throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        String slug = slugOf(tenant.business());
        UUID partial = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), partial, "09:00", "12:00");

        MvcResult result = getJson(selectionAvailability(slug, "2026-10-01", tenant.service(), second));
        JsonNode slots = tree(result).get("slots");

        assertThat(keys(tree(result))).containsExactly("date", "timezone", "availableDates", "slots");
        assertThat(slots.get(0).get("start").asString()).isEqualTo("2026-10-01T09:00:00+03:00");
        assertThat(slots.get(0).get("end").asString()).isEqualTo("2026-10-01T10:15:00+03:00");
        // 12:00 minus 75 minutes is the last start.
        assertThat(slots.get(slots.size() - 1).get("start").asString()).isEqualTo("2026-10-01T10:45:00+03:00");
        MvcResult explicit = getJson(selectionAvailability(slug, "2026-10-01", tenant.service(), second)
                + "&staffMemberId=" + tenant.staff());
        assertThat(explicit.getResponse().getStatus()).isEqualTo(200);
        MvcResult unsupported = getJson(selectionAvailability(slug, "2026-10-01", tenant.service(), second)
                + "&staffMemberId=" + partial);
        assertProblem(unsupported, 409, "BOOKING_STAFF_UNAVAILABLE",
                "Избраният служител вече не е наличен. Изберете друг или „Без предпочитание“.");
    }

    @Test
    void aMissingForeignOrInactiveServiceInASelectionIsTheServiceConflictOnBothRoutes() throws Exception {
        UUID second = svc("Alpha", 45, "20.50");
        UUID inactive = svc("Stopped", 15, "5.00");
        availabilityFixtures.setServiceActive(inactive, false);
        var other = openTenant();
        String slug = slugOf(tenant.business());
        String unavailable = "Избраната услуга вече не е налична. Изберете друга услуга.";

        for (UUID bad : List.of(inactive, UUID.randomUUID(), other.service())) {
            assertProblem(getJson(selectionOptions(slug, tenant.service(), second, bad)), 409,
                    "BOOKING_SERVICE_UNAVAILABLE", unavailable);
            assertProblem(getJson(selectionAvailability(slug, "2026-10-01", tenant.service(), bad)), 409,
                    "BOOKING_SERVICE_UNAVAILABLE", unavailable);
        }
    }

    @Test
    void aSelectionWhoseCurrentDurationsExceedEightHoursIsTheSelectionTooLongConflict() throws Exception {
        UUID first = svc("Alpha", 300, "10.00");
        UUID second = svc("Bravo", 181, "10.00");
        UUID exactly = svc("Charlie", 180, "10.00");
        String slug = slugOf(tenant.business());

        MvcResult options = getJson(selectionOptions(slug, first, second));
        MvcResult availability = getJson(selectionAvailability(slug, "2026-10-01", first, second));

        assertProblem(options, 409, "BOOKING_SELECTION_TOO_LONG", TOO_LONG);
        assertProblem(availability, 409, "BOOKING_SELECTION_TOO_LONG", TOO_LONG);
        // Exactly 480 minutes is a valid selection; an unavailable Service wins over the length.
        assertThat(getJson(selectionOptions(slug, first, exactly)).getResponse().getStatus()).isEqualTo(200);
        availabilityFixtures.setServiceActive(second, false);
        assertProblem(getJson(selectionOptions(slug, first, second, exactly)), 409,
                "BOOKING_SERVICE_UNAVAILABLE", "Избраната услуга вече не е налична. Изберете друга услуга.");
    }

    @Test
    void everyMalformedReadSelectionIsAServicesValidationError() throws Exception {
        String slug = slugOf(tenant.business());
        String a = tenant.service().toString();
        String base = PREFIX + "/" + slug;
        List<String> bad = new ArrayList<>(List.of(
                "", "?", "?serviceId=", "?serviceId=" + a + "&serviceId=" + a, "?serviceId=not-a-uuid",
                "?serviceId=" + a + "," + UUID.randomUUID(), "?serviceId=%20" + a, "?serviceId=" + a + "%20",
                "?serviceId=+" + a.substring(1), "?serviceId=1-1-1-1-1", "?other=" + a,
                "?serviceId=" + a + "&serviceId="));
        StringBuilder six = new StringBuilder("?");
        for (int index = 0; index < 6; index++) {
            six.append(index == 0 ? "" : "&").append("serviceId=").append(UUID.randomUUID());
        }
        bad.add(six.toString());
        TransactionLog.clear();

        for (String query : bad) {
            String options = base + "/booking-options" + query;
            String availability = base + "/availability" + (query.isEmpty() ? "?date=2026-10-01"
                    : query + "&date=2026-10-01");
            for (String url : List.of(options, availability)) {
                MvcResult result = onBookingThread("read", () -> getJson(url));
                assertProblem(result, 400, "VALIDATION_ERROR", "Проверете въведените данни.");
                assertThat(tree(result).get("fieldErrors").toString())
                        .as(url).isEqualTo("{\"services\":\"" + SERVICES_ERROR + "\"}");
            }
        }
        assertThat(TransactionLog.begins(threadOf("read"))).isEmpty();
        assertThat(hooks.observations()).isEmpty();
    }

    @Test
    void aMissingOrMalformedDateIsStillAPlainValidationErrorOnTheSelectionAvailability() throws Exception {
        String slug = slugOf(tenant.business());
        String url = PREFIX + "/" + slug + "/availability?serviceId=" + tenant.service();

        assertProblem(getJson(url), 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        assertThat(tree(getJson(url)).has("fieldErrors")).isFalse();
        assertProblem(getJson(url + "&date=2026-13-45"), 400, "VALIDATION_ERROR", "Проверете въведените данни.");
        assertProblem(getJson(url + "&date=2026-10-01&staffMemberId=x"), 400, "VALIDATION_ERROR",
                "Проверете въведените данни.");
    }

    @Test
    void anUnknownDraftOrSuspendedBusinessIsTheCollapsedNotFoundOnTheSelectionRoutes() throws Exception {
        for (String status : List.of("DRAFT", "SUSPENDED")) {
            availabilityFixtures.setBusinessStatus(tenant.business(), status);
            String slug = slugOf(tenant.business());
            assertProblem(getJson(selectionOptions(slug, tenant.service())), 404, "BUSINESS_PAGE_UNAVAILABLE",
                    "Страницата не е налична.");
            assertProblem(getJson(selectionAvailability(slug, "2026-10-01", tenant.service())), 404,
                    "BUSINESS_PAGE_UNAVAILABLE", "Страницата не е налична.");
        }
        assertProblem(getJson(selectionOptions("unknown-studio", tenant.service())), 404,
                "BUSINESS_PAGE_UNAVAILABLE", "Страницата не е налична.");
    }

    // ---- privacy ------------------------------------------------------------------------------------------------

    @Test
    void noSelectionClaimAttemptOrPersonalValueReachesAResponseErrorOrLog(CapturedOutput output) throws Exception {
        UUID second = svc("СЕНТИНЕЛ-УСЛУГА-9913", 45, "20.50");
        Req request = req().name("СЕНТИНЕЛ-ИМЕ-9913").phone("+359 88 9913991").note("СЕНТИНЕЛ-БЕЛЕЖКА-9913")
                .email("sentinel.9913@example.com");
        List<ReviewedService> reviewed = seen(tenant.service(), second);
        List<MvcResult> failures = new ArrayList<>();
        jdbc.sql("UPDATE service SET price = 99.13 WHERE id = :id").param("id", second).update();
        failures.add(visit(request, reviewed));
        failures.add(visit(request.copy().start("13:00"), reviewed));
        failures.add(visit(request.copy(), List.of(new ReviewedService(UUID.randomUUID(), 913, 99_13))));
        failures.add(postJson(bookingsUrl(request.slug), visitBody(request, reviewed).toString().replace(
                "\"reviewedPriceCents\"", "\"x\":1,\"reviewedPriceCents\"")));
        failures.add(getJson(selectionOptions(request.slug, tenant.service(), UUID.randomUUID())));
        failures.add(getJson(PREFIX + "/" + request.slug + "/booking-options?serviceId=СЕНТИНЕЛ-9913"));

        for (MvcResult failure : failures) {
            assertThat(text(failure.getResponse()))
                    .doesNotContain("СЕНТИНЕЛ").doesNotContain("9913").doesNotContain("sentinel.9913")
                    .doesNotContain(request.attemptId).doesNotContain(second.toString())
                    .doesNotContain("99.13").doesNotContain("9913991");
        }
        assertThat(output.getAll())
                .doesNotContain("СЕНТИНЕЛ").doesNotContain("9913").doesNotContain("sentinel.9913")
                .doesNotContain(request.attemptId).doesNotContain(second.toString());
    }

    private static byte[] hmac(String base64Key, byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
            return mac.doFinal(bytes);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
