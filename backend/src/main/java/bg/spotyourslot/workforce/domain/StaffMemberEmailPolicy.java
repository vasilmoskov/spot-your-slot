package bg.spotyourslot.workforce.domain;

/**
 * The single contact-email acceptance policy for StaffMembers, applied to the
 * canonical (trimmed, lower-cased) value before any general-purpose address
 * validator runs. The frontend mirrors these rules exactly.
 *
 * <p>An address needs exactly one {@code @}, a non-empty local part, no
 * whitespace, and a dotted domain: at least two labels, none empty, none
 * starting or ending with {@code -}, each made only of ASCII letters, digits,
 * and {@code -}. A single-label domain such as {@code a@a} (or a single
 * internationalized label such as a Cyrillic {@code а}, which browsers submit
 * as {@code xn--80a}) is not a routable contact address and is rejected.
 *
 * <p>Only ASCII addresses are accepted. Internationalized addresses (non-ASCII
 * local part or domain) need SMTPUTF8 support from every mail provider and
 * integration and are intentionally deferred, not accidentally unsupported.
 * Nothing is converted to Punycode.
 */
public final class StaffMemberEmailPolicy {
    private StaffMemberEmailPolicy() {
    }

    public static boolean isAcceptable(String email) {
        if (email == null || email.isEmpty()) {
            return false;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return false;
        }
        if (email.codePoints().anyMatch(codePoint -> codePoint > 127)) {
            return false;
        }
        boolean hasWhitespace = email.codePoints().anyMatch(
                codePoint -> Character.isWhitespace(codePoint)
                        || StaffMemberTextCanonicalizer.isApprovedWhitespace(codePoint));
        if (hasWhitespace) {
            return false;
        }
        String[] labels = email.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!isAcceptableLabel(label)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAcceptableLabel(String label) {
        if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) {
            return false;
        }
        return label.codePoints().allMatch(
                codePoint -> codePoint == '-'
                        || (codePoint >= 'a' && codePoint <= 'z')
                        || (codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= '0' && codePoint <= '9'));
    }
}
