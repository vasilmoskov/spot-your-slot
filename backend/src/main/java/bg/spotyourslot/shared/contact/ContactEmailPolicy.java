package bg.spotyourslot.shared.contact;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * The single authoritative backend email policy for StaffMembers and Customers. It is
 * sufficient by itself: no other validator may accept or reject an address independently of it.
 * The frontend mirrors these rules and is kept aligned by the shared golden vectors
 * ({@code shared-test-data/contact-policy-vectors.json}).
 *
 * <p>{@link #canonicalize(String)} applies, in order:
 *
 * <ol>
 *   <li>trimming of the approved whitespace set and Unicode NFKC normalization;
 *   <li>ASCII only: any character above U+007F rejects the address (no IDN, no Punycode
 *       conversion, no SMTPUTF8);
 *   <li>no whitespace anywhere;
 *   <li>a total canonical length of at most {@value #MAX_LENGTH} characters;
 *   <li>exactly one {@code @}, with a non-empty local part and a non-empty domain;
 *   <li>a local part of at most {@value #MAX_LOCAL_PART_LENGTH} characters, made of dot-separated
 *       atoms: it must not start or end with {@code .} and must not contain {@code ..};
 *   <li>each local-part atom uses only the RFC 5322 {@code atext} characters
 *       {@code A-Z a-z 0-9 ! # $ % & ' * + / = ? ^ _ ` { | } ~ -}; quoted-string local parts and
 *       comments are not supported;
 *   <li>a domain of at least two non-empty labels, each of at most
 *       {@value #MAX_LABEL_LENGTH} characters, using only ASCII letters, digits, and {@code -},
 *       and neither starting nor ending with {@code -}; a single-label domain such as
 *       {@code a@a} is not a routable contact address;
 *   <li>the whole address, local part and domain, is lower-cased.
 * </ol>
 *
 * <p>SMTP local parts are technically case-sensitive; treating {@code A@x} and {@code a@x} as one
 * identity is the accepted MVP trade-off. No provider-specific folding (Gmail dots, {@code +tag})
 * is applied. A blank or null input is not an address; callers treat blank contact input as absent
 * before calling.
 */
public final class ContactEmailPolicy {
    public static final int MAX_LENGTH = 320;
    public static final int MAX_LOCAL_PART_LENGTH = 64;
    public static final int MAX_LABEL_LENGTH = 63;

    private static final String SPECIAL_ATEXT = "!#$%&'*+/=?^_`{|}~-";

    private ContactEmailPolicy() {
    }

    /**
     * @return the canonical lower-case address, or {@link Optional#empty()} when the input is
     *     blank or violates any rule above
     */
    public static Optional<String> canonicalize(String value) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(value);
        if (trimmed == null) {
            return Optional.empty();
        }
        String address = Normalizer.normalize(trimmed, Normalizer.Form.NFKC);
        if (address.length() > MAX_LENGTH || !isAsciiWithoutWhitespace(address)) {
            return Optional.empty();
        }
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1) {
            return Optional.empty();
        }
        if (!isValidLocalPart(address.substring(0, at))
                || !isValidDomain(address.substring(at + 1))) {
            return Optional.empty();
        }
        return Optional.of(address.toLowerCase(Locale.ROOT));
    }

    private static boolean isAsciiWithoutWhitespace(String address) {
        return address.codePoints().allMatch(
                codePoint -> codePoint <= 127
                        && !Character.isWhitespace(codePoint)
                        && !ContactTextCanonicalizer.isApprovedWhitespace(codePoint));
    }

    private static boolean isValidLocalPart(String localPart) {
        if (localPart.length() > MAX_LOCAL_PART_LENGTH) {
            return false;
        }
        for (String atom : localPart.split("\\.", -1)) {
            if (atom.isEmpty() || !atom.chars().allMatch(ContactEmailPolicy::isAtext)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAtext(int character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || SPECIAL_ATEXT.indexOf(character) >= 0;
    }

    private static boolean isValidDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!isValidLabel(label)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidLabel(String label) {
        if (label.isEmpty()
                || label.length() > MAX_LABEL_LENGTH
                || label.startsWith("-")
                || label.endsWith("-")) {
            return false;
        }
        return label.chars().allMatch(
                character -> character == '-'
                        || (character >= 'a' && character <= 'z')
                        || (character >= 'A' && character <= 'Z')
                        || (character >= '0' && character <= '9'));
    }
}
