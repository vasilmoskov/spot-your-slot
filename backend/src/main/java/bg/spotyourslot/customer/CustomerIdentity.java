package bg.spotyourslot.customer;

/**
 * The raw identity a visitor or staff member submitted: a display name and a phone and/or email.
 * It is input only and is canonicalized by {@link CustomerIdentification}. The record exposes its
 * components because it is the explicit input contract, but its {@link #toString()} redacts every
 * value so a log line or an exception message can never leak personal data.
 */
public record CustomerIdentity(String displayName, String phone, String email) {
    @Override
    public String toString() {
        return "CustomerIdentity[redacted]";
    }
}
