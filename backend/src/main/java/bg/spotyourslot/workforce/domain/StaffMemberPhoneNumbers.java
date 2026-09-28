package bg.spotyourslot.workforce.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Optional;

/**
 * The single backend component that knows StaffMember contact-telephone
 * numbering rules. It centralizes prefix interpretation, country-aware
 * parsing, full validity checking, and canonical E.164 formatting using
 * Google's libphonenumber, so no other component (validator, store,
 * controller) duplicates a country's numbering rules.
 *
 * <p>The input is expected to already be trimmed of leading/trailing
 * whitespace (see {@link StaffMemberTextCanonicalizer#canonicalTrimmed});
 * this class only strips internal visual separators ({@code (}, {@code )},
 * {@code -}, {@code .}, and any approved whitespace) before interpreting the
 * remaining digits by their prefix:
 *
 * <ul>
 *   <li>a leading {@code +} is parsed as an explicit international number;
 *   <li>a leading {@code 00} is treated as international notation, replaced
 *       with {@code +};
 *   <li>a leading {@code 0} is parsed with the product's default region,
 *       {@code BG};
 *   <li>any other, prefix-less value is ambiguous (no recoverable country
 *       code) and is rejected without being parsed, rather than silently
 *       assumed to be Bulgarian.
 * </ul>
 *
 * <p>A value that survives prefix interpretation is only accepted when
 * libphonenumber's full validity check ({@link
 * PhoneNumberUtil#isValidNumber(PhoneNumber)}, not merely a possible-length
 * check) passes. This proves the number is plausible for its country's
 * numbering plan; it never proves the number is active, reachable, or owned
 * by the StaffMember.
 */
public final class StaffMemberPhoneNumbers {
    private static final String DEFAULT_REGION = "BG";
    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

    private StaffMemberPhoneNumbers() {
    }

    /**
     * Canonicalizes and validates an already-trimmed, non-null, non-empty
     * telephone candidate.
     *
     * @return the canonical compact E.164 form (for example
     *     {@code +359895555777}) when the candidate is structurally
     *     unambiguous and passes libphonenumber's full validity check;
     *     otherwise {@link Optional#empty()}.
     */
    public static Optional<String> canonicalize(String trimmedValue) {
        String stripped = stripVisualSeparators(trimmedValue);
        if (stripped.isEmpty()) {
            return Optional.empty();
        }

        try {
            PhoneNumber parsed;
            if (stripped.startsWith("+")) {
                parsed = PHONE_NUMBER_UTIL.parse(stripped, null);
            } else if (stripped.startsWith("00")) {
                parsed = PHONE_NUMBER_UTIL.parse("+" + stripped.substring(2), null);
            } else if (stripped.startsWith("0")) {
                parsed = PHONE_NUMBER_UTIL.parse(stripped, DEFAULT_REGION);
            } else {
                return Optional.empty();
            }

            if (!PHONE_NUMBER_UTIL.isValidNumber(parsed)) {
                return Optional.empty();
            }
            return Optional.of(PHONE_NUMBER_UTIL.format(parsed, PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    private static String stripVisualSeparators(String value) {
        var stripped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (!isVisualSeparator(codePoint)) {
                stripped.appendCodePoint(codePoint);
            }
        }
        return stripped.toString();
    }

    private static boolean isVisualSeparator(int codePoint) {
        return StaffMemberTextCanonicalizer.isApprovedWhitespace(codePoint)
                || codePoint == '('
                || codePoint == ')'
                || codePoint == '-'
                || codePoint == '.';
    }
}
