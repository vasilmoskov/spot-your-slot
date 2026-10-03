package bg.spotyourslot.customer.domain;

import bg.spotyourslot.shared.contact.ContactPhoneNumbers;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * The narrow, approved Customer search (ADR-0021): a case-insensitive substring of the normalized
 * display name, a substring of the lower-case email, and a canonical phone match. A criterion is
 * {@code null} when the search term does not apply to that field. The criteria hold derived
 * search text, so {@link #toString()} is redacted and never shows it.
 *
 * <p>Phone handling: input that is a full valid number matches the canonical phone exactly. Other
 * input made only of digits with a leading {@code +}, {@code 00}, or local {@code 0} is converted
 * to its canonical prefix ({@code 0} becomes {@code +359}) and prefix-matched. Anything else, such
 * as letters, wildcard characters, a bare {@code +}, or digits without a prefix, adds no phone
 * criterion, so malformed phone-like text never broadens the search.
 */
public record CustomerSearchCriteria(
        String nameFragment, String emailFragment, String phoneExact, String phonePrefix) {
    private static final String BULGARIAN_COUNTRY_PREFIX = "+359";

    /** No filter: every Customer of the Business matches. */
    public static CustomerSearchCriteria none() {
        return new CustomerSearchCriteria(null, null, null, null);
    }

    /**
     * Derives the criteria from a search term that is already trimmed, non-blank, and within the
     * length limit.
     */
    public static CustomerSearchCriteria fromTerm(String term) {
        String name = ContactTextCanonicalizer.canonicalDisplayName(term);
        String email = Normalizer.normalize(term.toLowerCase(Locale.ROOT), Normalizer.Form.NFKC);
        Optional<String> exact = ContactPhoneNumbers.canonicalize(term);
        String prefix = exact.isPresent() ? null : phonePrefix(term);
        return new CustomerSearchCriteria(name, email, exact.orElse(null), prefix);
    }

    public boolean isEmpty() {
        return nameFragment == null
                && emailFragment == null
                && phoneExact == null
                && phonePrefix == null;
    }

    private static String phonePrefix(String term) {
        String stripped = stripSeparators(term);
        if (!stripped.matches("\\+?[0-9]+")) {
            return null;
        }
        String prefix;
        if (stripped.startsWith("+")) {
            prefix = stripped;
        } else if (stripped.startsWith("00")) {
            prefix = "+" + stripped.substring(2);
        } else if (stripped.startsWith("0")) {
            prefix = BULGARIAN_COUNTRY_PREFIX + stripped.substring(1);
        } else {
            return null;
        }
        return prefix.length() > 1 ? prefix : null;
    }

    private static String stripSeparators(String value) {
        var stripped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            boolean separator = ContactTextCanonicalizer.isApprovedWhitespace(codePoint)
                    || codePoint == '('
                    || codePoint == ')'
                    || codePoint == '-'
                    || codePoint == '.';
            if (!separator) {
                stripped.appendCodePoint(codePoint);
            }
        }
        return stripped.toString();
    }

    @Override
    public String toString() {
        return "CustomerSearchCriteria[redacted]";
    }
}
