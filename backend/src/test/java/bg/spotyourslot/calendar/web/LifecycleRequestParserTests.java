package bg.spotyourslot.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shape rules of the cancellation and internal-note requests: exact allowlisted keys, canonical
 * identifiers, a required integral version, a required JSON string for the note, no {@code null}, and
 * no echo of any property name or value in the failure.
 */
class LifecycleRequestParserTests {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECRET = "СЕНТИНЕЛ-СТОЙНОСТ-5531";

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void aValidCancellationBodyIsReadByShapeOnlyAndAnyVersionLongPasses() {
        CancelAppointmentCommand command = CancelAppointmentRequestParser.parse(ID, json("{\"expectedVersion\":7}"));

        assertThat(command.appointmentId()).isEqualTo(UUID.fromString(ID));
        assertThat(command.expectedVersion()).isEqualTo(7L);
        // The domain bound (not negative) is the service's decision, after authorization and lifecycle.
        assertThat(CancelAppointmentRequestParser.parse(ID, json("{\"expectedVersion\":-1}")).expectedVersion())
                .isEqualTo(-1L);
    }

    @Test
    void aValidNoteBodyKeepsTheNoteExactlyAsSubmittedIncludingABlankOne() {
        EditInternalNoteCommand command = EditInternalNoteRequestParser.parse(
                ID, json("{\"expectedVersion\":3,\"internalNote\":\"  ново \\n\"}"));

        assertThat(command.internalNote()).isEqualTo("  ново \n");
        assertThat(command.expectedVersion()).isEqualTo(3L);
        assertThat(EditInternalNoteRequestParser.parse(ID, json("{\"expectedVersion\":0,\"internalNote\":\"\"}"))
                .internalNote()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "[]",
        "null",
        "7",
        "\"x\"",
        "{\"expectedVersion\":null}",
        "{\"expectedVersion\":\"1\"}",
        "{\"expectedVersion\":1.5}",
        "{\"expectedVersion\":1e2}",
        "{\"expectedVersion\":true}",
        "{\"expectedVersion\":99999999999999999999}",
        "{\"expectedVersion\":1,\"reason\":\"x\"}",
        "{\"expectedVersion\":1,\"status\":\"CANCELLED\"}",
        "{\"expectedVersion\":1,\"cancelledBy\":\"BUSINESS\"}",
        "{\"expectedVersion\":1,\"businessId\":\"11111111-1111-4111-8111-111111111111\"}"})
    void everyMalformedCancellationBodyIsTheGenericInvalidInputWithoutAnEcho(String body) {
        assertThatThrownBy(() -> CancelAppointmentRequestParser.parse(ID, json(body)))
                .isInstanceOf(InvalidInput.class)
                .hasNoCause()
                .satisfies(failure -> assertThat(String.valueOf(failure.getMessage())).doesNotContain("expectedVersion"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "[]",
        "null",
        "{\"expectedVersion\":1}",
        "{\"internalNote\":\"x\"}",
        "{\"expectedVersion\":1,\"internalNote\":null}",
        "{\"expectedVersion\":1,\"internalNote\":5}",
        "{\"expectedVersion\":1,\"internalNote\":true}",
        "{\"expectedVersion\":1,\"internalNote\":[\"x\"]}",
        "{\"expectedVersion\":1,\"internalNote\":{\"a\":1}}",
        "{\"expectedVersion\":null,\"internalNote\":\"x\"}",
        "{\"expectedVersion\":\"1\",\"internalNote\":\"x\"}",
        "{\"expectedVersion\":1,\"internalNote\":\"x\",\"status\":\"CANCELLED\"}",
        "{\"expectedVersion\":1,\"internalNote\":\"x\",\"customerNote\":\"x\"}"})
    void everyMalformedNoteBodyIsTheGenericInvalidInputWithoutAnEcho(String body) {
        assertThatThrownBy(() -> EditInternalNoteRequestParser.parse(ID, json(body)))
                .isInstanceOf(InvalidInput.class)
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "not-a-uuid", "1", "0-0-0-0-0", "{11111111-1111-4111-8111-111111111111}",
        "11111111111141118111111111111111", "+1111111-1111-4111-8111-111111111111",
        "11111111-1111-4111-8111-11111111111", " 11111111-1111-4111-8111-111111111111"})
    void aNonCanonicalIdentifierIsInvalidInputOnBothRoutes(String id) {
        assertThatThrownBy(() -> CancelAppointmentRequestParser.parse(id, json("{\"expectedVersion\":1}")))
                .isInstanceOf(InvalidInput.class);
        assertThatThrownBy(() -> EditInternalNoteRequestParser.parse(
                id, json("{\"expectedVersion\":1,\"internalNote\":\"x\"}"))).isInstanceOf(InvalidInput.class);
    }

    @Test
    void anUnknownKeyNamedLikeASecretIsNeverEchoed() {
        for (String body : List.of(
                "{\"expectedVersion\":1,\"" + SECRET + "\":1}",
                "{\"expectedVersion\":1,\"internalNote\":\"x\",\"" + SECRET + "\":1}")) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                    () -> EditInternalNoteRequestParser.parse(ID, json(body)));
            assertThat(failure).isInstanceOf(InvalidInput.class);
            assertThat(String.valueOf(failure.getMessage())).doesNotContain(SECRET);
        }
        assertThat(new CancelAppointmentCommand(UUID.fromString(ID), 1L).toString()).doesNotContain(ID);
        assertThat(new EditInternalNoteCommand(UUID.fromString(ID), 1L, SECRET).toString())
                .doesNotContain(SECRET).doesNotContain(ID);
    }
}
