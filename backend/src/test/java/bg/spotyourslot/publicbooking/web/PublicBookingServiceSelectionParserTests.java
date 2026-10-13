package bg.spotyourslot.publicbooking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.GuestBookingRequest.ReviewedService;
import bg.spotyourslot.publicbooking.web.PublicBookingRequestParser.ParsedBooking;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The strict reading of the public booking body for both shapes (ADR-0026, ADR-0027). Every
 * malformed selection is a deliberate public validation error: the {@code services} field error for
 * a malformed selection, and never a {@code NullPointerException} or any other unexpected failure.
 */
class PublicBookingServiceSelectionParserTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String A = "00000000-0000-4000-8000-000000000001";
    private static final String B = "7fffffff-ffff-4fff-bfff-ffffffffffff";
    private static final String C = "80000000-0000-4000-8000-000000000000";
    private static final String D = "ffffffff-ffff-4fff-bfff-ffffffffffff";
    private static final String E = "22222222-2222-4222-8222-222222222222";
    private static final String F = "33333333-3333-4333-8333-333333333333";
    private static final String ATTEMPT = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private static String element(String id, String duration, String price) {
        return "{\"serviceId\":" + id + ",\"reviewedDurationMinutes\":" + duration
                + ",\"reviewedPriceCents\":" + price + "}";
    }

    private static String ok(String id) {
        return element("\"" + id + "\"", "30", "1000");
    }

    private static String body(String serviceProperties) {
        return "{\"attemptId\":\"" + ATTEMPT + "\"," + serviceProperties
                + ",\"staffMemberId\":null,\"start\":\"2026-10-01T10:00:00+03:00\","
                + "\"customer\":{\"displayName\":\"Ана\",\"phone\":\"0888123456\",\"email\":null},"
                + "\"note\":null}";
    }

    private static String services(String... elements) {
        return "\"services\":[" + String.join(",", elements) + "]";
    }

    private static ParsedBooking parse(String json) {
        return PublicBookingRequestParser.parse(JSON.readTree(json));
    }

    private static InvalidPublicRequest rejected(String json) {
        try {
            parse(json);
        } catch (InvalidPublicRequest invalid) {
            return invalid;
        }
        throw new AssertionError("The body was accepted: " + json);
    }

    // ---- accepted ----

    @Test
    void theLegacyShapeIsReadUnchanged() {
        ParsedBooking parsed = parse(body("\"serviceId\":\"" + E + "\""));

        assertThat(parsed.serviceId()).isEqualTo(UUID.fromString(E));
        assertThat(parsed.services()).isNull();
    }

    @Test
    void theNewShapeIsReadWithItsReviewedTermsInTheOrderGiven() {
        ParsedBooking parsed = parse(body(services(
                element("\"" + C + "\"", "60", "0"), element("\"" + A + "\"", "30", "1500"))));

        assertThat(parsed.serviceId()).isNull();
        assertThat(parsed.services()).containsExactly(
                new ReviewedService(UUID.fromString(C), 60, 0), new ReviewedService(UUID.fromString(A), 30, 1500));
    }

    @Test
    void oneAndFiveServicesAndTheMaximumReviewedPriceAreAccepted() {
        assertThat(parse(body(services(ok(A)))).services()).hasSize(1);
        assertThat(parse(body(services(
                element("\"" + A + "\"", "96", "999999999999"), element("\"" + B + "\"", "96", "999999999999"),
                element("\"" + C + "\"", "96", "999999999999"), element("\"" + D + "\"", "96", "999999999999"),
                element("\"" + E + "\"", "96", "999999999999")))).services()).hasSize(5);
        assertThat(parse(body(services(element("\"" + A + "\"", "480", "0")))).services().get(0).durationMinutes())
                .isEqualTo(480);
    }

    @Test
    void anExplicitNullForTheOtherShapeCountsAsAbsent() {
        assertThat(parse(body("\"serviceId\":null," + services(ok(A)))).services()).hasSize(1);
        assertThat(parse(body("\"services\":null,\"serviceId\":\"" + E + "\"")).serviceId())
                .isEqualTo(UUID.fromString(E));
    }

    // ---- rejected: the choice of shape ----

    @Test
    void bothShapesTogetherIsTheServicesFieldError() {
        assertThat(rejected(body("\"serviceId\":\"" + E + "\"," + services(ok(A)))).isServices()).isTrue();
    }

    @Test
    void neitherShapeIsAPlainValidationError() {
        assertThat(rejected(body("\"note\":null")).isServices()).isFalse();
        assertThat(rejected(body("\"serviceId\":null,\"services\":null")).isServices()).isFalse();
    }

    // ---- rejected: the list ----

    static Stream<String> malformedLists() {
        String six = String.join(",", ok(A), ok(B), ok(C), ok(D), ok(E), ok(F));
        return Stream.of(
                "\"services\":[]",
                "\"services\":[" + six + "]",
                "\"services\":{}",
                "\"services\":\"" + A + "\"",
                "\"services\":7",
                "\"services\":true",
                "\"services\":[null]",
                "\"services\":[" + ok(A) + ",null]",
                "\"services\":[null," + ok(A) + "]",
                "\"services\":[\"" + A + "\"]",
                "\"services\":[7]",
                "\"services\":[[]]",
                "\"services\":[" + ok(A) + "," + ok(A) + "]",
                // the same Service in two spellings
                "\"services\":[" + ok(A) + "," + ok(A.toUpperCase()) + "]");
    }

    @ParameterizedTest
    @MethodSource("malformedLists")
    void everyMalformedListIsTheServicesFieldErrorAndNeverAnotherFailure(String property) {
        assertThat(rejected(body(property)).isServices()).isTrue();
    }

    // ---- rejected: an element ----

    static Stream<String> malformedElements() {
        String id = "\"" + A + "\"";
        return Stream.of(
                // keys
                "{}",
                "{\"serviceId\":" + id + "}",
                "{\"serviceId\":" + id + ",\"reviewedDurationMinutes\":30}",
                "{\"serviceId\":" + id + ",\"reviewedPriceCents\":1000}",
                "{\"reviewedDurationMinutes\":30,\"reviewedPriceCents\":1000}",
                "{\"serviceId\":" + id + ",\"reviewedDurationMinutes\":30,\"reviewedPriceCents\":1000,\"extra\":1}",
                "{\"serviceId\":" + id + ",\"reviewedDurationMinutes\":30,\"extra\":1000}",
                "{\"id\":" + id + ",\"reviewedDurationMinutes\":30,\"reviewedPriceCents\":1000}",
                // identifiers
                element("null", "30", "1000"),
                element("7", "30", "1000"),
                element("\"\"", "30", "1000"),
                element("\"not-a-uuid\"", "30", "1000"),
                element("\"1-1-1-1-1\"", "30", "1000"),
                element("\"+0000000-0000-4000-8000-000000000001\"", "30", "1000"),
                element("\"0000000-00000-4000-8000-000000000001\"", "30", "1000"),
                element("\"00000000-0000-4000-8000-00000000000g\"", "30", "1000"),
                element("\" " + A + "\"", "30", "1000"),
                element("[\"" + A + "\"]", "30", "1000"),
                // duration: type
                element(id, "null", "1000"),
                element(id, "\"30\"", "1000"),
                element(id, "30.0", "1000"),
                element(id, "30.5", "1000"),
                element(id, "3e1", "1000"),
                element(id, "true", "1000"),
                element(id, "[30]", "1000"),
                element(id, "99999999999999999999", "1000"),
                // duration: range
                element(id, "0", "1000"),
                element(id, "-1", "1000"),
                element(id, "481", "1000"),
                element(id, "2147483648", "1000"),
                // price: type
                element(id, "30", "null"),
                element(id, "30", "\"1000\""),
                element(id, "30", "10.00"),
                element(id, "30", "1e3"),
                element(id, "30", "false"),
                element(id, "30", "99999999999999999999999"),
                // price: range
                element(id, "30", "-1"),
                element(id, "30", "1000000000000"));
    }

    @ParameterizedTest
    @MethodSource("malformedElements")
    void everyMalformedElementIsTheServicesFieldErrorAndNeverAnotherFailure(String element) {
        assertThat(rejected(body(services(element))).isServices()).isTrue();
        // The same element after a valid one: a partial or mixed list is still rejected.
        assertThat(rejected(body(services(ok(B), element))).isServices()).isTrue();
    }

    @Test
    void theClaimedDurationsMayNotSumToMoreThanTheVisitCeiling() {
        parse(body(services(
                element("\"" + A + "\"", "240", "0"), element("\"" + B + "\"", "240", "0"))));

        assertThat(rejected(body(services(
                element("\"" + A + "\"", "240", "0"), element("\"" + B + "\"", "241", "0")))).isServices()).isTrue();
        assertThat(rejected(body(services(
                element("\"" + A + "\"", "480", "0"), element("\"" + B + "\"", "1", "0")))).isServices()).isTrue();
    }

    @Test
    void unknownKeysAtTheRootAndInTheCustomerAreStillRejected() {
        assertThat(rejected(body(services(ok(A)) + ",\"extra\":1").replace("\"note\":null}", "\"note\":null}"))
                .isServices()).isFalse();
        assertThatThrownBy(() -> parse(body(services(ok(A))).replace("\"note\":null", "\"note\":null,\"x\":1")))
                .isInstanceOf(InvalidPublicRequest.class);
        assertThatThrownBy(() -> parse(body(services(ok(A))).replace("\"email\":null", "\"email\":null,\"x\":1")))
                .isInstanceOf(InvalidPublicRequest.class);
    }

    @Test
    void aFailureCarriesNoValueAndNoPropertyName() {
        InvalidPublicRequest invalid = rejected(body(services(
                element("\"SECRET-SERVICE-TEXT\"", "30", "1000"))));

        assertThat(invalid.getMessage()).isEqualTo("Public booking request is invalid");
        assertThat(invalid.toString()).doesNotContain("SECRET").doesNotContain("serviceId");
        assertThat(invalid.getCause()).isNull();
        assertThat(invalid.getSuppressed()).isEmpty();
    }

    @Test
    void theParsedRecordRedactsItself() {
        JsonNode node = JSON.readTree(body(services(ok(A))));

        assertThat(PublicBookingRequestParser.parse(node).toString()).isEqualTo("ParsedBooking[redacted]");
    }
}
