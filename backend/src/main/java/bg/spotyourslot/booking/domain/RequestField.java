package bg.spotyourslot.booking.domain;

/**
 * Identifies a booking request field that failed validation. Only identifiers are ever reported,
 * never the submitted value. {@link #CONTACT} means neither a phone nor an email was supplied; a
 * supplied but invalid phone or email is reported as {@link #PHONE} or {@link #EMAIL}.
 */
public enum RequestField {
    ATTEMPT_ID,
    START,
    DISPLAY_NAME,
    PHONE,
    EMAIL,
    CONTACT,
    NOTE
}
