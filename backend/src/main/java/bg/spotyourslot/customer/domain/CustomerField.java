package bg.spotyourslot.customer.domain;

/**
 * Identifies a Customer input field that failed validation. Only identifiers are ever reported,
 * never the submitted value.
 */
public enum CustomerField {
    DISPLAY_NAME,
    PHONE,
    EMAIL,
    /** Neither a phone nor an email was supplied. */
    CONTACT
}
