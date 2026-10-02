package bg.spotyourslot.customer;

/**
 * Identifies a submitted identity field that failed validation. Only identifiers are ever
 * reported, never the submitted value. {@link #CONTACT} means neither a phone nor an email was
 * supplied; a supplied but invalid phone or email is reported as {@link #PHONE} or {@link #EMAIL}
 * instead.
 */
public enum IdentityField {
    DISPLAY_NAME,
    PHONE,
    EMAIL,
    CONTACT
}
