package bg.spotyourslot.booking;

/**
 * The only failures {@link AppointmentCreationOptions} reports (besides
 * {@link bg.spotyourslot.identity.SelectedBusinessRequired}). Every message is fixed; no instance
 * carries an identifier, a value, a cause, or a suppressed exception, so none can leak a record of
 * another Business. A missing, foreign, and inactive Service are the same exception, and so are those
 * of a StaffMember. They map to the same statuses and codes as the creation they prepare.
 */
public abstract sealed class AppointmentCreationOptionsException extends RuntimeException
        permits AppointmentCreationOptionsException.InvalidInput,
                AppointmentCreationOptionsException.AccessDenied,
                AppointmentCreationOptionsException.BusinessNotActive,
                AppointmentCreationOptionsException.BusinessSuspended,
                AppointmentCreationOptionsException.ServiceUnavailable,
                AppointmentCreationOptionsException.StaffUnavailable,
                AppointmentCreationOptionsException.Failure {
    private static final long serialVersionUID = 1L;

    private AppointmentCreationOptionsException(String safeMessage, boolean stackTrace) {
        super(safeMessage, null, false, stackTrace);
    }

    /** The selection, the StaffMember identifier, or the date violates a bound; no field is named. */
    public static final class InvalidInput extends AppointmentCreationOptionsException {
        private static final long serialVersionUID = 1L;

        public InvalidInput() {
            super("Creation options request is invalid", false);
        }
    }

    /** The Business is unknown or the caller is not its active owner. */
    public static final class AccessDenied extends AppointmentCreationOptionsException {
        private static final long serialVersionUID = 1L;

        public AccessDenied() {
            super("Creation options access is denied", false);
        }
    }

    /** The Business is {@code DRAFT}. */
    public static final class BusinessNotActive extends AppointmentCreationOptionsException {
        private static final long serialVersionUID = 1L;

        public BusinessNotActive() {
            super("Business is not active", false);
        }
    }

    /** The Business is {@code SUSPENDED}. */
    public static final class BusinessSuspended extends AppointmentCreationOptionsException {
        private static final long serialVersionUID = 1L;

        public BusinessSuspended() {
            super("Business is suspended", false);
        }
    }

    /** A Service is missing, foreign, or inactive. */
    public static final class ServiceUnavailable extends AppointmentCreationOptionsException {
        private static final long serialVersionUID = 1L;

        public ServiceUnavailable() {
            super("A selected Service is unavailable", false);
        }
    }

    /** The StaffMember is missing, foreign, inactive, or not assigned to every selected Service. */
    public static final class StaffUnavailable extends AppointmentCreationOptionsException {
        private static final long serialVersionUID = 1L;

        public StaffUnavailable() {
            super("The selected StaffMember is unavailable", false);
        }
    }

    /** Any unexpected failure; it keeps its own application-only stack trace for diagnostics. */
    public static final class Failure extends AppointmentCreationOptionsException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Creation options read failed", true);
        }
    }
}
