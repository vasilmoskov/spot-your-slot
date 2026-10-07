package bg.spotyourslot.booking.application;

/**
 * Supplies the short, informational public reference of a new Appointment (ADR-0022): ten
 * characters of the Crockford base32 alphabet, uppercase. A reference grants no access. A fresh one
 * is requested for every attempt, so a unique violation on the reference is retried with another.
 */
public interface PublicReferenceSource {
    String next();
}
