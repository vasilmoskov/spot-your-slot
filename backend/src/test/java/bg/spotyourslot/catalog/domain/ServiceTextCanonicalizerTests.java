package bg.spotyourslot.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ServiceTextCanonicalizerTests {
    private static final int[] APPROVED_WHITESPACE_CODE_POINTS = {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
        0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
        0x3000
    };

    @ParameterizedTest
    @MethodSource("approvedWhitespace")
    void collapsesEveryApprovedNameWhitespaceCodePoint(int codePoint) {
        String whitespace = Character.toString(codePoint);

        assertThat(ServiceTextCanonicalizer.canonicalName(
                        whitespace + "Мъжко" + whitespace + whitespace + "подстригване" + whitespace))
                .isEqualTo("Мъжко подстригване");
        assertThat(ServiceTextCanonicalizer.isApprovedWhitespace(codePoint)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("approvedWhitespace")
    void removesEveryApprovedDescriptionBoundaryWhitespaceCodePoint(int codePoint) {
        String whitespace = Character.toString(codePoint);

        assertThat(ServiceTextCanonicalizer.canonicalDescription(
                        whitespace + "Описание" + whitespace))
                .isEqualTo("Описание");
    }

    @Test
    void appliesNfkcExpansionAndCompositionBeforeWhitespaceHandling() {
        assertThat(ServiceTextCanonicalizer.canonicalName("  \uFB03  "))
                .isEqualTo("ffi");
        assertThat(ServiceTextCanonicalizer.canonicalName(" e\u0301 "))
                .isEqualTo("é");
        assertThat(ServiceTextCanonicalizer.canonicalDescription(" \uFB03 e\u0301 "))
                .isEqualTo("ffi é");
    }

    @Test
    void preservesMeaningfulCaseAndDoesNotCreateAUniquenessKey() {
        assertThat(ServiceTextCanonicalizer.canonicalName(" Straße ПОДСТРИГВАНЕ "))
                .isEqualTo("Straße ПОДСТРИГВАНЕ");
    }

    @Test
    void preservesDescriptionInternalWhitespaceAndLineBreaks() {
        assertThat(ServiceTextCanonicalizer.canonicalDescription(
                        "  Първи  ред\n\tВтори ред  "))
                .isEqualTo("Първи  ред\n\tВтори ред");
    }

    @Test
    void convertsBlankCanonicalDescriptionsToNull() {
        assertThat(ServiceTextCanonicalizer.canonicalDescription(null)).isNull();
        assertThat(ServiceTextCanonicalizer.canonicalDescription("\u2003\n\u3000")).isNull();
    }

    @Test
    void leavesNonApprovedFormatCharactersUntouched() {
        assertThat(ServiceTextCanonicalizer.canonicalName("A\u200BB"))
                .isEqualTo("A\u200BB");
        assertThat(ServiceTextCanonicalizer.canonicalDescription("\u200B"))
                .isEqualTo("\u200B");
        assertThat(ServiceTextCanonicalizer.isApprovedWhitespace(0x200B)).isFalse();
    }

    @Test
    void preservesSupplementaryCharacters() {
        assertThat(ServiceTextCanonicalizer.canonicalName("  😀  услуга  "))
                .isEqualTo("😀 услуга");
        assertThat(ServiceTextCanonicalizer.canonicalDescription("  😀\nтекст  "))
                .isEqualTo("😀\nтекст");
    }

    private static Stream<Integer> approvedWhitespace() {
        return IntStream.of(APPROVED_WHITESPACE_CODE_POINTS).boxed();
    }
}
