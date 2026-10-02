package bg.spotyourslot.shared.contact;

import java.text.Normalizer;

/**
 * Text canonicalization shared by StaffMember and Customer: Unicode NFKC, the approved
 * whitespace set, and trimming. Display names additionally collapse internal whitespace runs.
 */
public final class ContactTextCanonicalizer {
    private ContactTextCanonicalizer() {
    }

    public static String canonicalDisplayName(String value) {
        if (value == null) {
            return null;
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        var canonical = new StringBuilder(normalized.length());
        boolean pendingSpace = false;

        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isApprovedWhitespace(codePoint)) {
                pendingSpace = canonical.length() > 0;
            } else {
                if (pendingSpace) {
                    canonical.append(' ');
                    pendingSpace = false;
                }
                canonical.appendCodePoint(codePoint);
            }
        }

        return canonical.toString();
    }

    /**
     * Trims and NFKC-normalizes an optional free-text contact value (email or
     * telephone), returning {@code null} for a blank value. This is the only
     * shared preprocessing step for contact fields; the caller is
     * responsible for any further field-specific canonicalization and
     * validation. {@link ContactPhoneNumbers} and {@link ContactEmailPolicy} are the
     * single places that interpret and validate a trimmed telephone or email
     * candidate, so there is exactly one canonicalization implementation for
     * writes.
     */
    public static String canonicalTrimmed(String value) {
        return canonicalOptionalContact(value);
    }

    private static String canonicalOptionalContact(String value) {
        if (value == null) {
            return null;
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        int start = 0;
        while (start < normalized.length()) {
            int codePoint = normalized.codePointAt(start);
            if (!isApprovedWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        int end = normalized.length();
        while (end > start) {
            int codePoint = normalized.codePointBefore(end);
            if (!isApprovedWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        return start == end ? null : normalized.substring(start, end);
    }

    public static boolean isApprovedWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000D
                || codePoint == 0x0020
                || codePoint == 0x0085
                || codePoint == 0x00A0
                || codePoint == 0x1680
                || codePoint >= 0x2000 && codePoint <= 0x200A
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }
}
