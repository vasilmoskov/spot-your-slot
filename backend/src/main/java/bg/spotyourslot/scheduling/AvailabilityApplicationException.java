package bg.spotyourslot.scheduling;

/**
 * Typed availability failures. Messages are fixed strings: they never carry SQL
 * text, constraint names, or identifiers. There is no HTTP mapping in this
 * phase; a future public adapter decides how to present them.
 */
public abstract sealed class AvailabilityApplicationException extends RuntimeException
        permits AvailabilityApplicationException.BusinessNotBookable,
                AvailabilityApplicationException.ServiceNotBookable,
                AvailabilityApplicationException.StaffMemberNotEligible,
                AvailabilityApplicationException.AvailabilityFailure {
    private AvailabilityApplicationException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }

    /** The Business is missing, DRAFT, or SUSPENDED; the three are not distinguishable. */
    public static final class BusinessNotBookable extends AvailabilityApplicationException {
        public BusinessNotBookable() {
            super("Business is not available for booking", null);
        }
    }

    /** The Service is missing, foreign to the Business, or inactive. */
    public static final class ServiceNotBookable extends AvailabilityApplicationException {
        public ServiceNotBookable() {
            super("Service is not available for booking", null);
        }
    }

    /** The requested StaffMember is missing, inactive, foreign, or not assigned to the Service. */
    public static final class StaffMemberNotEligible extends AvailabilityApplicationException {
        public StaffMemberNotEligible() {
            super("StaffMember is not eligible for the Service", null);
        }
    }

    /**
     * An unexpected persistence or access failure, corrupt published data, invalid
     * {@link BusyIntervalSource} output, or an impossible translation state. The
     * cause is retained for server-side diagnosis only.
     */
    public static final class AvailabilityFailure extends AvailabilityApplicationException {
        public AvailabilityFailure(Throwable cause) {
            super("Availability calculation failed", cause);
        }
    }
}
