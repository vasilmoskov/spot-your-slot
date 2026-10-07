package bg.spotyourslot.booking.application;

/**
 * The booking fingerprint key configuration is unusable. The reason is a fixed code; the message,
 * the stack, and every field are free of configured values, so a startup failure can never print key
 * material.
 */
public final class FingerprintConfigurationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The only information a failure carries. */
    public enum Reason {
        ACTIVE_VERSION_MISSING,
        ACTIVE_VERSION_INVALID,
        NO_KEYS,
        KEY_VERSION_INVALID,
        KEY_VERSION_DUPLICATE,
        KEY_NOT_BASE64,
        KEY_TOO_SHORT,
        ACTIVE_KEY_MISSING,
        NON_SECRET_KEY_IN_PRODUCTION
    }

    private final transient Reason reason;

    public FingerprintConfigurationException(Reason reason) {
        super("Booking fingerprint key configuration is invalid: " + reason, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
