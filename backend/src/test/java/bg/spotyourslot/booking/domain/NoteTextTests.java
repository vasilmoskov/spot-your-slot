package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The neutral note policy extracted from the guest request (ADR-0028 section 9) behaves exactly as
 * the code it was extracted from. {@code Reference} is a verbatim copy of the previous private
 * implementation inside {@code NormalizedBookingRequest}; the corpus mixes hand-picked edge cases
 * with a seeded random sample of the characters that matter (line breaks, white space, controls,
 * surrogates, supplementary characters).
 */
class NoteTextTests {
    /** The implementation before the extraction, copied unchanged. */
    private static final class Reference {
        static final int MAX_NOTE_CODE_POINTS = 500;
        private static final Pattern FORBIDDEN_NOTE_CONTROL = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");

        static String normalizeNote(String note) {
            if (note == null) {
                return null;
            }
            String unified = note.replace("\r\n", "\n").replace('\r', '\n').strip();
            return unified.isEmpty() ? null : unified;
        }

        static boolean isValidNote(String note) {
            return note.codePointCount(0, note.length()) <= MAX_NOTE_CODE_POINTS
                    && !FORBIDDEN_NOTE_CONTROL.matcher(note).find()
                    && !hasUnpairedSurrogate(note);
        }

        private static boolean hasUnpairedSurrogate(String value) {
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (Character.isHighSurrogate(current)) {
                    if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                        return true;
                    }
                    index++;
                } else if (Character.isLowSurrogate(current)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static List<String> corpus() {
        List<String> values = new ArrayList<>(java.util.Arrays.asList(
                null, "", " ", "\n", "\r", "\r\n", "\t", " ", " текст ", "бележка",
                "  бележка  ", "ред\r\nред", "ред\rред", "ред\nред", "ред\n\rред", "таб\tтаб",
                "\u0000", "a\u0000b", "\u0001", "\u000B", "\u001F", "\u007F", "\u0085", "\uD83D",
                "\uDE00", "😀", "\uD83D x", "x \uDE00", "\uD83D😀",
                "я".repeat(500), "я".repeat(501), "😀".repeat(500),
                "😀".repeat(501), " " + "я".repeat(500) + " ", "\r\n".repeat(400) + "x"));
        Random random = new Random(20261014L);
        String alphabet = "aя \t\r\n  \u0000\u0001\u000B\u001F\u007F\u0085😀😀";
        for (int sample = 0; sample < 5000; sample++) {
            StringBuilder builder = new StringBuilder();
            int length = random.nextInt(12);
            for (int index = 0; index < length; index++) {
                builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            values.add(builder.toString());
        }
        return values;
    }

    @Test
    void normalizationIsIdenticalToTheExtractedImplementation() {
        for (String value : corpus()) {
            assertThat(NoteText.normalize(value)).as(String.valueOf(value))
                    .isEqualTo(Reference.normalizeNote(value));
            assertThat(NormalizedBookingRequest.normalizeNote(value))
                    .isEqualTo(Reference.normalizeNote(value));
        }
    }

    @Test
    void validityIsIdenticalToTheExtractedImplementationForEveryNormalizedValue() {
        int validated = 0;
        for (String value : corpus()) {
            String normalized = Reference.normalizeNote(value);
            if (normalized != null) {
                assertThat(NoteText.isValid(normalized)).as(normalized)
                        .isEqualTo(Reference.isValidNote(normalized));
                validated++;
            }
        }
        assertThat(validated).isGreaterThan(1000);
    }

    @Test
    void theLimitIsFiveHundredCodePointsEverywhere() {
        assertThat(NoteText.MAX_CODE_POINTS).isEqualTo(500).isEqualTo(Reference.MAX_NOTE_CODE_POINTS);
        assertThat(NormalizedBookingRequest.MAX_NOTE_CODE_POINTS).isEqualTo(500);
        assertThat(AppointmentRules.MAX_NOTE_CODE_POINTS).isEqualTo(500);
    }

    @Test
    void aNormalizedValidNoteIsAlwaysAcceptedByTheStoredNoteRule() {
        for (String value : corpus()) {
            String normalized = NoteText.normalize(value);
            if (normalized != null && NoteText.isValid(normalized)) {
                AppointmentRules.requireNote(normalized);
            }
        }
    }
}
