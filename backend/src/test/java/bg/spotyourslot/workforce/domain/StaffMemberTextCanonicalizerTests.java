package bg.spotyourslot.workforce.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class StaffMemberTextCanonicalizerTests {
    private static final int[] APPROVED_WHITESPACE_CODE_POINTS = {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
        0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
        0x3000
    };

    @ParameterizedTest
    @MethodSource("approvedWhitespace")
    void collapsesEveryApprovedDisplayNameWhitespaceCodePoint(int codePoint) {
        String whitespace = Character.toString(codePoint);

        assertThat(StaffMemberTextCanonicalizer.canonicalDisplayName(
                        whitespace + "Анна" + whitespace + whitespace + "Иванова" + whitespace))
                .isEqualTo("Анна Иванова");
        assertThat(StaffMemberTextCanonicalizer.isApprovedWhitespace(codePoint)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("approvedWhitespace")
    void removesEveryApprovedContactBoundaryWhitespaceCodePoint(int codePoint) {
        String whitespace = Character.toString(codePoint);

        assertThat(StaffMemberTextCanonicalizer.canonicalContactEmail(
                        whitespace + "TEAM@EXAMPLE.INVALID" + whitespace))
                .isEqualTo("team@example.invalid");
        assertThat(StaffMemberTextCanonicalizer.canonicalContactPhone(
                        whitespace + "+359 (2) 123-45-67" + whitespace))
                .isEqualTo("+359 (2) 123-45-67");
    }

    @Test
    void appliesNfkcBeforeFieldSpecificWhitespaceAndCaseHandling() {
        assertThat(StaffMemberTextCanonicalizer.canonicalDisplayName("  ﬃ  é  "))
                .isEqualTo("ffi é");
        assertThat(StaffMemberTextCanonicalizer.canonicalContactEmail(
                        " ＴＥＡＭ@ＥＸＡＭＰＬＥ.INVALID "))
                .isEqualTo("team@example.invalid");
        assertThat(StaffMemberTextCanonicalizer.canonicalContactPhone(" ＋３５９ "))
                .isEqualTo("+359");
    }

    @Test
    void preservesMeaningfulDisplayNameCase() {
        assertThat(StaffMemberTextCanonicalizer.canonicalDisplayName(
                        "  Anna ИВАНОВА Straße  "))
                .isEqualTo("Anna ИВАНОВА Straße");
    }

    @Test
    void lowercasesEmailWithLocaleRootSemantics() {
        assertThat(StaffMemberTextCanonicalizer.canonicalContactEmail(
                        "I@İ.EXAMPLE"))
                .isEqualTo("i@i̇.example");
    }

    @Test
    void convertsBlankOptionalContactsToNull() {
        assertThat(StaffMemberTextCanonicalizer.canonicalContactEmail(null)).isNull();
        assertThat(StaffMemberTextCanonicalizer.canonicalContactPhone(null)).isNull();
        assertThat(StaffMemberTextCanonicalizer.canonicalContactEmail("\u2003\n\u3000"))
                .isNull();
        assertThat(StaffMemberTextCanonicalizer.canonicalContactPhone("\u2003\n\u3000"))
                .isNull();
    }

    @Test
    void preservesAcceptedInternalPhoneFormatting() {
        assertThat(StaffMemberTextCanonicalizer.canonicalContactPhone(
                        "  +359 (2) 123.45/67-8  "))
                .isEqualTo("+359 (2) 123.45/67-8");
    }

    @Test
    void leavesNonApprovedBoundaryCharactersUntouched() {
        assertThat(StaffMemberTextCanonicalizer.canonicalDisplayName("A\u200BB"))
                .isEqualTo("A\u200BB");
        assertThat(StaffMemberTextCanonicalizer.canonicalContactEmail(
                        "\u200BTEAM@EXAMPLE.INVALID\u200B"))
                .isEqualTo("\u200Bteam@example.invalid\u200B");
        assertThat(StaffMemberTextCanonicalizer.canonicalContactPhone("\u200B123\u200B"))
                .isEqualTo("\u200B123\u200B");
        assertThat(StaffMemberTextCanonicalizer.isApprovedWhitespace(0x200B)).isFalse();
    }

    @Test
    void preservesSupplementaryCharacters() {
        assertThat(StaffMemberTextCanonicalizer.canonicalDisplayName("  😀  Екип  "))
                .isEqualTo("😀 Екип");
    }

    private static Stream<Integer> approvedWhitespace() {
        return IntStream.of(APPROVED_WHITESPACE_CODE_POINTS).boxed();
    }
}
