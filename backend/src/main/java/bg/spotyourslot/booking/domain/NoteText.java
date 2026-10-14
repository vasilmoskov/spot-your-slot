package bg.spotyourslot.booking.domain;

import java.util.regex.Pattern;

/**
 * The plain-text policy of a free-text Appointment note, shared by the guest note
 * ({@link NormalizedBookingRequest}) and the private internal note ({@link AppointmentTransitions}).
 * It is JDK-only and knows nothing of either caller. The rule mirrors the {@code V11} and
 * {@code V14} note CHECK constraints: line breaks become line feeds, surrounding Unicode white
 * space is removed, a blank note is absent, at most 500 code points, no unpaired surrogate, and no
 * control character except a tab and a line feed.
 */
final class NoteText {
    static final int MAX_CODE_POINTS = 500;

    /** NUL and the control characters PostgreSQL or the Appointment note rule rejects; tab and LF stay. */
    private static final Pattern FORBIDDEN_CONTROL = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");

    private NoteText() {
    }

    /** Line breaks to LF, surrounding Unicode white space removed, blank to absent. */
    static String normalize(String note) {
        if (note == null) {
            return null;
        }
        String unified = note.replace("\r\n", "\n").replace('\r', '\n').strip();
        return unified.isEmpty() ? null : unified;
    }

    /** Whether an already normalized, non-blank note satisfies the length and character rules. */
    static boolean isValid(String note) {
        return note.codePointCount(0, note.length()) <= MAX_CODE_POINTS
                && !FORBIDDEN_CONTROL.matcher(note).find()
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
