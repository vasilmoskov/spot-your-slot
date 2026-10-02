package bg.spotyourslot.shared.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Direct tests of each accepted rule of the shared email policy, independent of the shared
 * golden vectors (which {@link ContactPolicyVectorTests} runs).
 */
class ContactEmailPolicyTests {
    private static final String LABEL_63 = "b".repeat(63);

    @Test
    void trimsAndNormalizesWithNfkcBeforeValidating() {
        assertThat(ContactEmailPolicy.canonicalize("  　 Ime@Primer.BG\n"))
                .contains("ime@primer.bg");
        assertThat(ContactEmailPolicy.canonicalize("ｉｍｅ＠ｐｒｉｍｅｒ．ｂｇ"))
                .contains("ime@primer.bg");
    }

    @Test
    void lowercasesTheWholeAddressWithLocaleRootSemantics() {
        assertThat(ContactEmailPolicy.canonicalize("I.ME+TAG@MAIL.PRIMER.BG"))
                .contains("i.me+tag@mail.primer.bg");
    }

    @Test
    void rejectsNonAsciiCharactersWithoutIdnOrPunycodeConversion() {
        assertThat(ContactEmailPolicy.canonicalize("I@İ.EXAMPLE")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize("ime@пример.бг")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize("иван@primer.bg")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize("ime@xn--e1afmkfd.xn--90ae")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@@x.bg", "a@b@x.bg", "ab", "@x.bg", "a@", "a@x"})
    void requiresExactlyOneAtWithNonEmptyLocalPartAndDomain(String address) {
        assertThat(ContactEmailPolicy.canonicalize(address)).isEmpty();
    }

    @Test
    void limitsTheLocalPartToSixtyFourCharacters() {
        assertThat(ContactEmailPolicy.canonicalize("a".repeat(64) + "@x.bg")).isPresent();
        assertThat(ContactEmailPolicy.canonicalize("a".repeat(65) + "@x.bg")).isEmpty();
    }

    @Test
    void limitsTheCanonicalAddressToThreeHundredTwentyCharacters() {
        String atLimit = "a".repeat(64) + "@" + String.join(".", LABEL_63, LABEL_63, LABEL_63, LABEL_63);
        String overLimit = "a".repeat(64) + "@"
                + String.join(".", LABEL_63, LABEL_63, LABEL_63, "b".repeat(61), "ab");

        assertThat(atLimit).hasSize(320);
        assertThat(overLimit).hasSize(321);
        assertThat(ContactEmailPolicy.canonicalize(atLimit)).contains(atLimit);
        assertThat(ContactEmailPolicy.canonicalize(overLimit)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {".ab@x.bg", "ab.@x.bg", "a..b@x.bg", ".@x.bg", "a.b..c@x.bg"})
    void rejectsLeadingTrailingAndRepeatedDotsInTheLocalPart(String address) {
        assertThat(ContactEmailPolicy.canonicalize(address)).isEmpty();
    }

    @Test
    void acceptsExactlyTheApprovedLocalPartCharacters() {
        String approved = "abcXYZ0189!#$%&'*+/=?^_`{|}~-";

        assertThat(ContactEmailPolicy.canonicalize(approved + "@x.bg"))
                .contains(approved.toLowerCase(java.util.Locale.ROOT) + "@x.bg");
        for (char forbidden : "\"(),:;<>[\\] @".toCharArray()) {
            assertThat(ContactEmailPolicy.canonicalize("a" + forbidden + "b@x.bg"))
                    .as("forbidden local-part character U+%04X", (int) forbidden)
                    .isEmpty();
        }
        assertThat(ContactEmailPolicy.canonicalize("\"ab\"@x.bg")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@x", "a@.bg", "a@x.", "a@x..bg", "a@-x.bg", "a@x-.bg", "a@x.-bg", "a@x.bg-"})
    void requiresAtLeastTwoNonEmptyDomainLabelsWithoutEdgeHyphens(String address) {
        assertThat(ContactEmailPolicy.canonicalize(address)).isEmpty();
    }

    @Test
    void limitsEachDomainLabelToLettersDigitsAndHyphenWithinSixtyThreeCharacters() {
        assertThat(ContactEmailPolicy.canonicalize("a@" + LABEL_63 + ".bg")).isPresent();
        assertThat(ContactEmailPolicy.canonicalize("a@" + LABEL_63 + "b.bg")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize("a@pri-mer.b-g1")).isPresent();
        for (char forbidden : "_!#$%&'*+/=?^`{|}~@ ".toCharArray()) {
            assertThat(ContactEmailPolicy.canonicalize("a@pri" + forbidden + "mer.bg"))
                    .as("forbidden domain character U+%04X", (int) forbidden)
                    .isEmpty();
        }
    }

    @Test
    void rejectsWhitespaceAnywhereInsideTheAddress() {
        assertThat(ContactEmailPolicy.canonicalize("a b@x.bg")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize("a@x .bg")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize("a\tb@x.bg")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize("a@x.bg​z")).isEmpty();
    }

    @Test
    void treatsNullAndBlankAsNotAnAddress() {
        assertThat(ContactEmailPolicy.canonicalize(null)).isEqualTo(Optional.empty());
        assertThat(ContactEmailPolicy.canonicalize("")).isEmpty();
        assertThat(ContactEmailPolicy.canonicalize(" 　\n")).isEmpty();
    }

    @Test
    void canonicalizationIsIdempotent() {
        String canonical = ContactEmailPolicy.canonicalize(" Ime.PREZIME+Tag@Mail.Primer.BG ").orElseThrow();

        assertThat(ContactEmailPolicy.canonicalize(canonical)).contains(canonical);
    }
}
