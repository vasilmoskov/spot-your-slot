package bg.spotyourslot.publicbooking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.publicbooking.web.PublicBookingRequestParser.ParsedBooking;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The strict reading of the public booking body (ADR-0026). */
class PublicBookingRequestParserTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SERVICE = "6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11";
    private static final String STAFF = "0b3d9a56-8d9e-4b5f-a1f4-5b2d5a3e7c22";

    private static ParsedBooking parse(String json) throws Exception {
        return PublicBookingRequestParser.parse(JSON.readTree(json));
    }

    private static String body(String customer, String extra) {
        return "{\"attemptId\":\"abc\",\"serviceId\":\"" + SERVICE + "\",\"staffMemberId\":\"" + STAFF + "\","
                + "\"start\":\"2026-10-01T10:00:00+03:00\",\"customer\":" + customer + ",\"note\":\"n\"" + extra + "}";
    }

    @Test
    void aCompleteBodyIsReadWithoutAnyInterpretation() throws Exception {
        ParsedBooking parsed = parse(body("{\"displayName\":\"  Иван \",\"phone\":\"0888\",\"email\":\"A@B\"}", ""));

        assertThat(parsed.attemptId()).isEqualTo("abc");
        assertThat(parsed.serviceId()).isEqualTo(UUID.fromString(SERVICE));
        assertThat(parsed.staffMemberId()).isEqualTo(UUID.fromString(STAFF));
        assertThat(parsed.start()).isEqualTo(Instant.parse("2026-10-01T07:00:00Z"));
        assertThat(parsed.displayName()).isEqualTo("  Иван ");
        assertThat(parsed.phone()).isEqualTo("0888");
        assertThat(parsed.email()).isEqualTo("A@B");
        assertThat(parsed.note()).isEqualTo("n");
    }

    @Test
    void absentAndNullOptionalValuesAreNull() throws Exception {
        ParsedBooking parsed = parse("{\"serviceId\":\"" + SERVICE + "\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"staffMemberId\":null,\"customer\":null,\"attemptId\":null}");

        assertThat(parsed.attemptId()).isNull();
        assertThat(parsed.staffMemberId()).isNull();
        assertThat(parsed.displayName()).isNull();
        assertThat(parsed.phone()).isNull();
        assertThat(parsed.email()).isNull();
        assertThat(parsed.note()).isNull();
        assertThat(parsed.start()).isEqualTo(Instant.parse("2026-10-01T07:00:00Z"));
    }

    @Test
    void aCustomerObjectMayOmitEveryField() throws Exception {
        ParsedBooking parsed = parse(body("{}", ""));

        assertThat(parsed.displayName()).isNull();
        assertThat(parsed.phone()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[]", "\"text\"", "12", "true", "null",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\"}",
        "{\"start\":\"2026-10-01T07:00:00Z\"}",
        "{\"serviceId\":\"x\",\"start\":\"2026-10-01T07:00:00Z\"}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00\"}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01\"}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":1759302000}",
        "{\"serviceId\":5,\"start\":\"2026-10-01T07:00:00Z\"}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"staffMemberId\":\"nope\"}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"attemptId\":7}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"note\":{}}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"customer\":\"Иван\"}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"customer\":[]}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"customer\":{\"phone\":123}}",
        "{\"serviceId\":\"6f1a1d0e-3c52-4a39-9d52-8a1d2b0f9c11\",\"start\":\"2026-10-01T07:00:00Z\","
                + "\"customer\":{\"email\":[\"a@b\"]}}"
    })
    void aStructurallyInvalidBodyIsRejectedWithoutDetail(String json) throws Exception {
        JsonNode tree = JSON.readTree(json);

        assertThatThrownBy(() -> PublicBookingRequestParser.parse(tree))
                .isInstanceOf(InvalidPublicRequest.class)
                .hasMessage("Public booking request is invalid")
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "businessId", "business", "price", "durationMinutes", "end", "status", "source", "timezone",
        "customerId", "id", "reference", "extra"
    })
    void anyUnknownRootOrCustomerPropertyIsRejected(String property) throws Exception {
        assertThatThrownBy(() -> parse(body("{}", ",\"" + property + "\":\"x\"")))
                .isInstanceOf(InvalidPublicRequest.class);
        assertThatThrownBy(() -> parse(body("{\"" + property + "\":\"x\"}", "")))
                .isInstanceOf(InvalidPublicRequest.class);
    }

    @Test
    void thePersonalDataOfAParsedBodyIsNeverPrinted() throws Exception {
        ParsedBooking parsed = parse(body("{\"displayName\":\"Сентинел\",\"phone\":\"0888111222\"}", ""));

        assertThat(parsed.toString()).isEqualTo("ParsedBooking[redacted]");
        assertThat(new InvalidPublicRequest().getStackTrace()).isEmpty();
    }
}
