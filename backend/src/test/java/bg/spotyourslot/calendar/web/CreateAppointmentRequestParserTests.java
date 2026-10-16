package bg.spotyourslot.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class CreateAppointmentRequestParserTests {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String CUSTOMER = "11111111-1111-4111-8111-111111111111";
    private static final String STAFF = "22222222-2222-4222-8222-222222222222";
    private static final String SERVICE = "33333333-3333-4333-8333-333333333333";

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    private static String valid(String extraRoot) {
        return """
                {"customerId":"%s","staffMemberId":"%s","start":"2026-11-03T10:00:00+02:00",
                 "services":[{"serviceId":"%s","reviewedServiceName":"Услуга",
                 "reviewedDurationMinutes":30,"reviewedPriceCents":2500}]%s}
                """.formatted(CUSTOMER, STAFF, SERVICE, extraRoot);
    }

    @Test
    void aValidBodyIsReadByShapeOnly() {
        CreateAppointmentCommand command = CreateAppointmentRequestParser.parse(
                json(valid(",\"internalNote\":\"  бележка  \"")));

        assertThat(command.customerId()).isEqualTo(UUID.fromString(CUSTOMER));
        assertThat(command.staffMemberId()).isEqualTo(UUID.fromString(STAFF));
        assertThat(command.start()).isEqualTo(Instant.parse("2026-11-03T08:00:00Z"));
        assertThat(command.services()).hasSize(1);
        assertThat(command.services().get(0).serviceId()).isEqualTo(UUID.fromString(SERVICE));
        assertThat(command.services().get(0).serviceName()).isEqualTo("Услуга");
        assertThat(command.services().get(0).durationMinutes()).isEqualTo(30);
        assertThat(command.services().get(0).priceCents()).isEqualTo(2500);
        // Not normalized here: the note policy belongs to the domain after authorization.
        assertThat(command.internalNote()).isEqualTo("  бележка  ");
    }

    @Test
    void theNoteIsOptionalAndNullIsAbsent() {
        assertThat(CreateAppointmentRequestParser.parse(json(valid(""))).internalNote()).isNull();
        assertThat(CreateAppointmentRequestParser.parse(json(valid(",\"internalNote\":null"))).internalNote()).isNull();
    }

    @Test
    void domainBoundsAreNotTheParsersConcern() {
        // Zero, huge, negative, duplicate, and empty selections are shape-valid; the service judges them.
        CreateAppointmentCommand command = CreateAppointmentRequestParser.parse(json(
                valid("").replace("\"reviewedDurationMinutes\":30", "\"reviewedDurationMinutes\":0")
                        .replace("\"reviewedPriceCents\":2500", "\"reviewedPriceCents\":-5")));
        assertThat(command.services().get(0).durationMinutes()).isZero();
        assertThat(CreateAppointmentRequestParser.parse(json(
                valid("").replaceAll("(?s)\"services\":\\[.*?\\]", "\"services\":[]"))).services()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[]", "null", "\"x\"", "5", "{}",
        "{\"customerId\":\"11111111-1111-4111-8111-111111111111\"}",
    })
    void aBodyThatIsNotTheObjectIsRejected(String body) {
        assertThatThrownBy(() -> CreateAppointmentRequestParser.parse(json(body))).isInstanceOf(InvalidInput.class);
    }

    @Test
    void everyShapeViolationIsRejectedWithAFixedMessageAndNoValue() {
        List<String> invalid = List.of(
                valid(",\"businessId\":\"" + CUSTOMER + "\""),
                valid(",\"x\":1"),
                valid("").replace("\"customerId\"", "\"customer\""),
                valid("").replace(CUSTOMER, "1"),
                valid("").replace(CUSTOMER, "+1111111-1111-4111-8111-111111111111"),
                valid("").replace(CUSTOMER, "11111111111141118111111111111111"),
                valid("").replace("\"customerId\":\"" + CUSTOMER + "\"", "\"customerId\":null"),
                valid("").replace("2026-11-03T10:00:00+02:00", "2026-11-03T10:00:00"),
                valid("").replace("\"2026-11-03T10:00:00+02:00\"", "1788000000"),
                valid("").replace("2026-11-03T10:00:00+02:00", "2026-11-03"),
                valid("").replace("\"reviewedDurationMinutes\":30", "\"reviewedDurationMinutes\":30.5"),
                valid("").replace("\"reviewedDurationMinutes\":30", "\"reviewedDurationMinutes\":\"30\""),
                valid("").replace("\"reviewedDurationMinutes\":30", "\"reviewedDurationMinutes\":3e1"),
                valid("").replace("\"reviewedDurationMinutes\":30", "\"reviewedDurationMinutes\":99999999999"),
                valid("").replace("\"reviewedPriceCents\":2500", "\"reviewedPriceCents\":25.5"),
                valid("").replace("\"reviewedPriceCents\":2500", "\"reviewedPriceCents\":99999999999999999999"),
                valid("").replace("\"reviewedServiceName\":\"Услуга\"", "\"reviewedServiceName\":5"),
                valid("").replace("\"reviewedServiceName\":\"Услуга\"", "\"reviewedServiceName\":null"),
                valid("").replace(",\"reviewedPriceCents\":2500", ""),
                valid("").replace("\"reviewedPriceCents\":2500", "\"reviewedPriceCents\":2500,\"extra\":1"),
                valid("").replaceAll("(?s)\"services\":\\[.*?\\]", "\"services\":{}"),
                valid("").replaceAll("(?s)\"services\":\\[.*?\\]", "\"services\":[5]"),
                valid("").replaceAll("(?s)\"services\":\\[.*?\\]", "\"services\":[null]"),
                valid(",\"internalNote\":5"),
                valid(",\"internalNote\":[]"));
        for (String body : invalid) {
            assertThatThrownBy(() -> CreateAppointmentRequestParser.parse(json(body)))
                    .as(body)
                    .isInstanceOf(InvalidInput.class)
                    .hasNoCause()
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(CUSTOMER).doesNotContain("Услуга"));
        }
    }

    @Test
    void theCommandRedactsItsValues() {
        CreateAppointmentCommand command = CreateAppointmentRequestParser.parse(
                json(valid(",\"internalNote\":\"СЕНТИНЕЛ\"")));

        assertThat(command.toString()).doesNotContain("СЕНТИНЕЛ").doesNotContain(CUSTOMER);
        assertThat(command.services().get(0).toString()).doesNotContain("Услуга").doesNotContain(SERVICE);
    }
}
