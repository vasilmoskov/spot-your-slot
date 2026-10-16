package bg.spotyourslot.booking;

/**
 * The only failures {@link AppointmentAdministration} reports. Every message is fixed; no instance
 * carries an identifier, a value, a cause, or a suppressed exception, so none can leak a Customer, a
 * note, SQL, or the existence of a record of another Business. A missing and a foreign Customer are
 * the same exception; a missing, foreign, and inactive Service are the same, and so are those of a
 * StaffMember.
 */
public abstract sealed class AppointmentAdministrationException extends RuntimeException
        permits AppointmentAdministrationException.InvalidInput,
                AppointmentAdministrationException.AccessDenied,
                AppointmentAdministrationException.BusinessNotActive,
                AppointmentAdministrationException.BusinessSuspended,
                AppointmentAdministrationException.CustomerNotFound,
                AppointmentAdministrationException.AppointmentNotFound,
                AppointmentAdministrationException.ConcurrentUpdate,
                AppointmentAdministrationException.StateConflict,
                AppointmentAdministrationException.ServiceUnavailable,
                AppointmentAdministrationException.StaffUnavailable,
                AppointmentAdministrationException.ReviewChanged,
                AppointmentAdministrationException.SlotUnavailable,
                AppointmentAdministrationException.TemporarilyUnavailable,
                AppointmentAdministrationException.OutcomeUncertain,
                AppointmentAdministrationException.Failure {
    private static final long serialVersionUID = 1L;

    private AppointmentAdministrationException(String safeMessage, boolean stackTrace) {
        super(safeMessage, null, false, stackTrace);
    }

    /** A domain bound of the request is violated; the field is deliberately not named. */
    public static final class InvalidInput extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public InvalidInput() {
            super("Appointment request is invalid", false);
        }
    }

    /** The Business is unknown or the caller is not its active owner. */
    public static final class AccessDenied extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public AccessDenied() {
            super("Appointment administration access is denied", false);
        }
    }

    /** The Business is {@code DRAFT}. */
    public static final class BusinessNotActive extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public BusinessNotActive() {
            super("Business is not active", false);
        }
    }

    /** The Business is {@code SUSPENDED}. */
    public static final class BusinessSuspended extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public BusinessSuspended() {
            super("Business is suspended", false);
        }
    }

    /** The Customer is missing or belongs to another Business. */
    public static final class CustomerNotFound extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public CustomerNotFound() {
            super("Customer was not found", false);
        }
    }

    /** The Appointment is missing or belongs to another Business (identical). */
    public static final class AppointmentNotFound extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public AppointmentNotFound() {
            super("Appointment was not found", false);
        }
    }

    /** The Appointment's version differs from the expected one, directly or after a lost row race. */
    public static final class ConcurrentUpdate extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public ConcurrentUpdate() {
            super("The Appointment was changed concurrently", false);
        }
    }

    /** The version matches but the state forbids the change: already cancelled, or already started. */
    public static final class StateConflict extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public StateConflict() {
            super("The Appointment state does not allow this change", false);
        }
    }

    /** A Service is missing, foreign, or inactive. */
    public static final class ServiceUnavailable extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public ServiceUnavailable() {
            super("A selected Service is unavailable", false);
        }
    }

    /**
     * The StaffMember is missing, foreign, inactive, or (for a creation or a reassignment) not
     * assigned to every Service of the visit.
     */
    public static final class StaffUnavailable extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public StaffUnavailable() {
            super("The selected StaffMember is unavailable", false);
        }
    }

    /** A reviewed name, duration, or price differs from the current Service; nothing was written. */
    public static final class ReviewChanged extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public ReviewChanged() {
            super("The reviewed Service terms changed", false);
        }
    }

    /** The requested start is not offered, is past, or lost the overlap race. */
    public static final class SlotUnavailable extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public SlotUnavailable() {
            super("The requested time is unavailable", false);
        }
    }

    /** A proven rollback after the bounded retries; nothing was written. */
    public static final class TemporarilyUnavailable extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public TemporarilyUnavailable() {
            super("The Appointment could not be saved now", false);
        }
    }

    /** The commit may have succeeded; never retried automatically. */
    public static final class OutcomeUncertain extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public OutcomeUncertain() {
            super("The Appointment outcome is uncertain", false);
        }
    }

    /** Any other failure; it keeps its own application-only stack trace for diagnostics. */
    public static final class Failure extends AppointmentAdministrationException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Appointment administration failed", true);
        }
    }
}
