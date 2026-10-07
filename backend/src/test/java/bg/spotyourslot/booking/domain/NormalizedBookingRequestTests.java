package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NormalizedBookingRequestTests {
    private static final String ATTEMPT = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final UUID SERVICE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID STAFF = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant START = Instant.parse("2026-10-08T07:00:00Z");

    private static NormalizedBookingRequest normalize(
            String name, String phone, String email, String note) {
        return NormalizedBookingRequest.normalize(ATTEMPT, SERVICE, null, START, name, phone, email, note);
    }

    private static Set<RequestField> rejected(
            String attempt, Instant start, String name, String phone, String email, String note) {
        try {
            NormalizedBookingRequest.normalize(attempt, SERVICE, null, start, name, phone, email, note);
        } catch (InvalidBookingRequest invalid) {
            return invalid.fields();
        }
        throw new AssertionError("The request was accepted");
    }

    @Test
    void cosmeticDifferencesNormalizeToTheSameValues() {
        NormalizedBookingRequest tidy = normalize("Иван Петров", "+359888123456", "ivan@example.com", "Бележка");
        NormalizedBookingRequest messy = normalize(
                "  Иван \t Петров ", "0888 123 456", "  IVAN@Example.COM ", "\r\n  Бележка \n\n");

        assertThat(messy.customerName()).isEqualTo(tidy.customerName()).isEqualTo("Иван Петров");
        assertThat(messy.customerPhone()).isEqualTo(tidy.customerPhone()).isEqualTo("+359888123456");
        assertThat(messy.customerEmail()).isEqualTo(tidy.customerEmail()).isEqualTo("ivan@example.com");
        assertThat(messy.note()).isEqualTo(tidy.note()).isEqualTo("Бележка");
    }

    @Test
    void aBlankNoteIsAbsentAndLineBreaksAreUnifiedInsideTheNote() {
        assertThat(normalize("Ана", "0888123456", null, "  \r\n \t ").note()).isNull();
        assertThat(normalize("Ана", "0888123456", null, null).note()).isNull();
        assertThat(normalize("Ана", "0888123456", null, "a\r\nb\rc\nd").note()).isEqualTo("a\nb\nc\nd");
        assertThat(normalize("Ана", "0888123456", null, "a\tb").note()).isEqualTo("a\tb");
    }

    @Test
    void aBlankPhoneOrEmailIsAbsentAndOneContactIsEnough() {
        NormalizedBookingRequest onlyPhone = normalize("Ана", "0888123456", "  ", null);
        NormalizedBookingRequest onlyEmail = normalize("Ана", "", "ana@example.com", null);

        assertThat(onlyPhone.customerEmail()).isNull();
        assertThat(onlyEmail.customerPhone()).isNull();
    }

    @Test
    void theStaffPreferenceIsKeptAsSubmittedAndNeverInferred() {
        NormalizedBookingRequest preferred = NormalizedBookingRequest.normalize(
                ATTEMPT, SERVICE, STAFF, START, "Ана", "0888123456", null, null);

        assertThat(preferred.requestedStaffMemberId()).isEqualTo(STAFF);
        assertThat(normalize("Ана", "0888123456", null, null).requestedStaffMemberId()).isNull();
    }

    @Test
    void everyInvalidFieldIsReportedTogetherWithoutAnyValue() {
        assertThat(rejected("not-an-id", START, "  ", "12", "nope", "bad\u0001note"))
                .containsExactlyInAnyOrder(
                        RequestField.ATTEMPT_ID,
                        RequestField.DISPLAY_NAME,
                        RequestField.PHONE,
                        RequestField.EMAIL,
                        RequestField.NOTE);
    }

    @Test
    void neitherPhoneNorEmailIsAContactError() {
        assertThat(rejected(ATTEMPT, START, "Ана", null, null, null)).containsExactly(RequestField.CONTACT);
        assertThat(rejected(ATTEMPT, START, "Ана", "  ", "  ", null)).containsExactly(RequestField.CONTACT);
    }

    @Test
    void aSuppliedButInvalidContactIsNotReportedAsMissing() {
        assertThat(rejected(ATTEMPT, START, "Ана", "123", null, null)).containsExactly(RequestField.PHONE);
        assertThat(rejected(ATTEMPT, START, "Ана", null, "a@", null)).containsExactly(RequestField.EMAIL);
    }

    @Test
    void theDisplayNameIsBoundedAtTwoHundredCodePoints() {
        assertThat(normalize("я".repeat(200), "0888123456", null, null).customerName()).hasSize(200);
        assertThat(rejected(ATTEMPT, START, "я".repeat(201), "0888123456", null, null))
                .containsExactly(RequestField.DISPLAY_NAME);
        assertThat(rejected(ATTEMPT, START, null, "0888123456", null, null))
                .containsExactly(RequestField.DISPLAY_NAME);
    }

    @Test
    void theNoteIsBoundedAtFiveHundredCodePointsAndRejectsControlCharactersAndLoneSurrogates() {
        String astral = "😀";
        assertThat(normalize("Ана", "0888123456", null, astral.repeat(500)).note()).isNotNull();
        assertThat(rejected(ATTEMPT, START, "Ана", "0888123456", null, astral.repeat(501)))
                .containsExactly(RequestField.NOTE);
        for (String bad : new String[] {"a\u0000b", "a\u0007b", "a\u000Bb", "a\u001Fb", "a\u007Fb", "a\uD83Db", "a\uDE00b"}) {
            assertThat(rejected(ATTEMPT, START, "Ана", "0888123456", null, bad))
                    .as("note %s", bad.length()).containsExactly(RequestField.NOTE);
        }
    }

    @Test
    void theStartMustBeAWholeSecondSoTheFingerprintCannotMissASubSecondDifference() {
        assertThat(rejected(ATTEMPT, START.plusNanos(1), "Ана", "0888123456", null, null))
                .containsExactly(RequestField.START);
        assertThat(rejected(ATTEMPT, Instant.parse("+10000-01-01T00:00:00Z"), "Ана", "0888123456", null, null))
                .containsExactly(RequestField.START);
    }

    @Test
    void requiredReferencesAreProgrammingErrorsNotValidationOutcomes() {
        assertThatThrownBy(() -> NormalizedBookingRequest.normalize(
                ATTEMPT, null, null, START, "Ана", "0888123456", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NormalizedBookingRequest.normalize(
                ATTEMPT, SERVICE, null, null, "Ана", "0888123456", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRequestAndItsFailureNeverPrintPersonalData() {
        NormalizedBookingRequest request = normalize("Ана Тайна", "0888123456", null, "тайна");
        InvalidBookingRequest failure = new InvalidBookingRequest(Set.of(RequestField.NOTE));

        assertThat(request.toString()).doesNotContain("Ана").doesNotContain("0888").doesNotContain("тайна");
        assertThat(failure.getMessage()).isEqualTo("Booking request is invalid");
        assertThat(failure.getCause()).isNull();
    }
}
