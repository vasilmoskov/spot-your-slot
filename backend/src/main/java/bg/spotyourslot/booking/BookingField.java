package bg.spotyourslot.booking;

/**
 * A booking request field that failed validation. Only identifiers are reported, never the
 * submitted value. {@link #CONTACT} means neither a phone nor an email was supplied.
 */
public enum BookingField {
    ATTEMPT_ID,
    START,
    DISPLAY_NAME,
    PHONE,
    EMAIL,
    CONTACT,
    NOTE
}
