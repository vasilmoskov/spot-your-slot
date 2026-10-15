package bg.spotyourslot.booking;

/**
 * The only failures {@link AppointmentCalendar} reports. Every message is fixed; no instance
 * carries an identifier, a value, a cause, or a suppressed exception, so none can leak a Customer,
 * a note, SQL, or the existence of a record of another Business. A missing and a foreign
 * identifier are the same exception.
 */
public abstract sealed class AppointmentCalendarException extends RuntimeException
        permits AppointmentCalendarException.InvalidInput,
                AppointmentCalendarException.AccessDenied,
                AppointmentCalendarException.AppointmentNotFound,
                AppointmentCalendarException.CustomerNotFound,
                AppointmentCalendarException.StaffMemberNotFound,
                AppointmentCalendarException.Failure {
    private static final long serialVersionUID = 1L;

    private AppointmentCalendarException(String safeMessage, boolean stackTrace) {
        super(safeMessage, null, false, stackTrace);
    }

    /** A date, window, page, size, or identifier is invalid; the field is deliberately not named. */
    public static final class InvalidInput extends AppointmentCalendarException {
        private static final long serialVersionUID = 1L;

        public InvalidInput() {
            super("Calendar request is invalid", false);
        }
    }

    /** The Business is unknown or the caller is not its active owner. */
    public static final class AccessDenied extends AppointmentCalendarException {
        private static final long serialVersionUID = 1L;

        public AccessDenied() {
            super("Calendar access is denied", false);
        }
    }

    public static final class AppointmentNotFound extends AppointmentCalendarException {
        private static final long serialVersionUID = 1L;

        public AppointmentNotFound() {
            super("Appointment was not found", false);
        }
    }

    public static final class CustomerNotFound extends AppointmentCalendarException {
        private static final long serialVersionUID = 1L;

        public CustomerNotFound() {
            super("Customer was not found", false);
        }
    }

    public static final class StaffMemberNotFound extends AppointmentCalendarException {
        private static final long serialVersionUID = 1L;

        public StaffMemberNotFound() {
            super("StaffMember was not found", false);
        }
    }

    /** Any unexpected failure; it keeps its own application-only stack trace for diagnostics. */
    public static final class Failure extends AppointmentCalendarException {
        private static final long serialVersionUID = 1L;

        public Failure() {
            super("Calendar read failed", true);
        }
    }
}
