package bg.spotyourslot.customer.domain;

import bg.spotyourslot.shared.contact.ContactEmailPolicy;
import bg.spotyourslot.shared.contact.ContactPhoneNumbers;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The editable, canonical part of a Customer: a display name and a canonical phone and/or email.
 * The constructor accepts only values that are already canonical and enforces every invariant:
 *
 * <ul>
 *   <li>the display name is canonical (NFKC, whitespace collapsed, trimmed), non-blank, and at
 *       most {@value #DISPLAY_NAME_MAX_LENGTH} code points; it is never an identifier;
 *   <li>a phone, when present, is canonical compact E.164;
 *   <li>an email, when present, is the canonical lowercase address;
 *   <li>at least one of phone and email is present.
 * </ul>
 *
 * <p>No original (raw) contact text is ever held. Use {@link #fromInput} to canonicalize raw user
 * input. A violation raises {@link InvalidCustomerData} naming every invalid field and no value.
 */
public record CustomerProfile(String displayName, String phone, String email) {
    public static final int DISPLAY_NAME_MAX_LENGTH = 200;

    public CustomerProfile {
        Set<CustomerField> invalid = EnumSet.noneOf(CustomerField.class);
        if (!isCanonicalDisplayName(displayName)) {
            invalid.add(CustomerField.DISPLAY_NAME);
        }
        if (phone != null && !isCanonicalPhone(phone)) {
            invalid.add(CustomerField.PHONE);
        }
        if (email != null && !isCanonicalEmail(email)) {
            invalid.add(CustomerField.EMAIL);
        }
        if (phone == null && email == null) {
            invalid.add(CustomerField.CONTACT);
        }
        if (!invalid.isEmpty()) {
            throw new InvalidCustomerData(invalid);
        }
    }

    /**
     * Canonicalizes raw input with the shared contact policy. A blank phone or email is absent.
     * Every invalid field is reported together; a phone or email that was supplied but is invalid
     * is reported as that field, and {@link CustomerField#CONTACT} only when neither was supplied.
     */
    public static CustomerProfile fromInput(String displayName, String phone, String email) {
        Set<CustomerField> invalid = EnumSet.noneOf(CustomerField.class);

        String canonicalName = ContactTextCanonicalizer.canonicalDisplayName(displayName);
        if (!isCanonicalDisplayName(canonicalName)) {
            invalid.add(CustomerField.DISPLAY_NAME);
        }

        String trimmedPhone = ContactTextCanonicalizer.canonicalTrimmed(phone);
        Optional<String> canonicalPhone = trimmedPhone == null
                ? Optional.empty()
                : ContactPhoneNumbers.canonicalize(trimmedPhone);
        if (trimmedPhone != null && canonicalPhone.isEmpty()) {
            invalid.add(CustomerField.PHONE);
        }

        String trimmedEmail = ContactTextCanonicalizer.canonicalTrimmed(email);
        Optional<String> canonicalEmail = trimmedEmail == null
                ? Optional.empty()
                : ContactEmailPolicy.canonicalize(trimmedEmail);
        if (trimmedEmail != null && canonicalEmail.isEmpty()) {
            invalid.add(CustomerField.EMAIL);
        }

        if (trimmedPhone == null && trimmedEmail == null) {
            invalid.add(CustomerField.CONTACT);
        }
        if (!invalid.isEmpty()) {
            throw new InvalidCustomerData(invalid);
        }
        return new CustomerProfile(canonicalName, canonicalPhone.orElse(null), canonicalEmail.orElse(null));
    }

    private static boolean isCanonicalDisplayName(String value) {
        return value != null
                && !value.isEmpty()
                && value.codePointCount(0, value.length()) <= DISPLAY_NAME_MAX_LENGTH
                && value.equals(ContactTextCanonicalizer.canonicalDisplayName(value));
    }

    private static boolean isCanonicalPhone(String value) {
        return ContactPhoneNumbers.canonicalize(value).filter(value::equals).isPresent();
    }

    private static boolean isCanonicalEmail(String value) {
        return ContactEmailPolicy.canonicalize(value).filter(value::equals).isPresent();
    }
}
