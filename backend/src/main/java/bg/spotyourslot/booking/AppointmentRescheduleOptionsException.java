package bg.spotyourslot.booking;

/**
 * The only failures {@link AppointmentRescheduleOptions} reports (besides
 * {@link bg.spotyourslot.identity.SelectedBusinessRequired}). Every message is fixed; no instance
 * carries an identifier, a value, a cause, or a suppressed exception, so none can leak a record of
 * another Business. A missing and a foreign Appointment are the same exception, and so are a
 * missing, foreign, inactive, and ineligible StaffMember. They map to the statuses and codes of the
 * reschedule they prepare.
 */
public abstract sealed class AppointmentRescheduleOptionsException extends RuntimeException
        permits AppointmentRescheduleOptionsException.InvalidInput,
                AppointmentRescheduleOptionsException.AccessDenied,
                AppointmentRescheduleOptionsException.BusinessNotActive,
                AppointmentRescheduleOptionsException.BusinessSuspended,
                AppointmentRescheduleOptionsException.AppointmentNotFound,
                AppointmentRescheduleOptionsException.StateConflict,
                AppointmentRescheduleOptionsException.StaffUnavailable,
                AppointmentRescheduleOptionsException.Failure {
    private static final long serialVersionUID = 1L;

    private AppointmentRescheduleOptionsException(String safeMessage, boolean stackTrace) {
        super(safeMessage, null, false, stackTrace);
    }

    /** An identifier or the date violates a bound; no field is named. */
    public static final class InvalidInput extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public InvalidInput() {
            super("Reschedule options request is invalid", false);
        }
    }

    /** The Business is unknown or the caller is not its active owner. */
    public static final class AccessDenied extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public AccessDenied() {
            super("Reschedule options access is denied", false);
        }
    }

    /** The Business is {@code DRAFT}. */
    public static final class BusinessNotActive extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public BusinessNotActive() {
            super("Business is not active", false);
        }
    }

    /** The Business is {@code SUSPENDED}. */
    public static final class BusinessSuspended extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public BusinessSuspended() {
            super("Business is suspended", false);
        }
    }

    /** The Appointment is missing or belongs to another Business (indistinguishable). */
    public static final class AppointmentNotFound extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public AppointmentNotFound() {
            super("The Appointment was not found", false);
        }
    }

    /** The Appointment is not {@code CONFIRMED} or has already started: no option exists. */
    public static final class StateConflict extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public StateConflict() {
            super("The Appointment cannot be changed", false);
        }
    }

    /** The StaffMember is missing, foreign, inactive, or not eligible for the requested move. */
    public static final class StaffUnavailable extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public StaffUnavailable() {
            super("The selected StaffMember is unavailable", false);
        }
    }

    /** Any unexpected failure; it keeps its own application-only stack trace for diagnostics. */
    public static final class Failure extends AppointmentRescheduleOptionsException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Reschedule options read failed", true);
        }
    }
}
