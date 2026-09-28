package bg.spotyourslot.workforce.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class StaffMemberPhoneNumbersTests {

    @ParameterizedTest
    @MethodSource("validPhones")
    void canonicalizesApprovedPhoneFormsToCompactE164UsingLibphonenumber(
            String rawPhone, String expectedCanonical) {
        assertThat(StaffMemberPhoneNumbers.canonicalize(rawPhone)).contains(expectedCanonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Ambiguous: no '0', '00', or '+' prefix, so no country code can be inferred.
            "895555777",
            "123456789",
            // Malformed: contains characters that are not allowed visual separators or digits.
            "+359 abc",
            "+359_123456",
            "☎359123456",
            "١٢٣٤٥٦٧٨", // Arabic-Indic digits
            // A leading '0' inside an already-international number is not a valid E.164 form.
            "+0895555777",
            // A malformed leading '+' sequence.
            "++359123456",
            // Structurally invalid Bulgarian number: too few national digits.
            "+35921",
            // Structurally invalid foreign number: too few national digits for Germany.
            "+491",
    })
    void rejectsAmbiguousMalformedAndStructurallyInvalidNumbers(String phone) {
        assertThat(StaffMemberPhoneNumbers.canonicalize(phone)).isEmpty();
    }

    private static Stream<Arguments> validPhones() {
        return Stream.of(
                // Bulgarian national prefix '0' is interpreted with the default BG region.
                Arguments.of("0895555777", "+359895555777"),
                // International prefix '00' is converted to '+'.
                Arguments.of("0049 151 23456789", "+4915123456789"),
                // A value already starting with '+' preserves its supplied country code.
                Arguments.of("+49 151 23456789", "+4915123456789"),
                Arguments.of("+359 (895) 555-777", "+359895555777"),
                // Allowed visual separators are removed from anywhere in the value.
                Arguments.of("+359.895.555.777", "+359895555777"));
    }
}
