package bg.spotyourslot.booking.infrastructure;

/**
 * The only failures {@link AppointmentStore} reports. Every message is fixed, and no instance
 * retains a cause or a suppressed exception: a database exception can contain SQL, constraint
 * text, identifiers, and the offending names, note, or contact-derived values, so it is classified
 * structurally and then discarded.
 *
 * <p>A failed statement aborts the PostgreSQL transaction. A caller that receives any of these
 * must abandon its current transaction and may retry only in a completely new one (ADR-0023);
 * {@link ConcurrentFailure} and {@link OverlapConflict} are the expected retry or rejection
 * classes, while the classification of what to retry belongs to the booking orchestration.
 *
 * <p>Stack traces: the expected classifications are lightweight (they are ordinary outcomes the
 * caller handles by type), while {@link UnexpectedFailure} keeps its own application-only stack
 * trace so a production failure identifies the operation and call path.
 */
public abstract sealed class AppointmentPersistenceException extends RuntimeException
        permits AppointmentPersistenceException.OverlapConflict,
                AppointmentPersistenceException.DuplicatePublicReference,
                AppointmentPersistenceException.DuplicateAttempt,
                AppointmentPersistenceException.UnknownReference,
                AppointmentPersistenceException.InvalidData,
                AppointmentPersistenceException.ConcurrentFailure,
                AppointmentPersistenceException.UnexpectedFailure {
    private static final long serialVersionUID = 1L;

    private AppointmentPersistenceException(String safeMessage, boolean captureStackTrace) {
        super(safeMessage, null, false, captureStackTrace);
    }

    /** A {@code CONFIRMED} Appointment of the same StaffMember already occupies part of the time. */
    public static final class OverlapConflict extends AppointmentPersistenceException {
        private static final long serialVersionUID = 1L;

        OverlapConflict() {
            super("Appointment time is already occupied", false);
        }
    }

    /** The Business already holds an Appointment with this public reference. */
    public static final class DuplicatePublicReference extends AppointmentPersistenceException {
        private static final long serialVersionUID = 1L;

        DuplicatePublicReference() {
            super("Appointment public reference is already in use", false);
        }
    }

    /** The Business already holds an Appointment created by the same booking attempt. */
    public static final class DuplicateAttempt extends AppointmentPersistenceException {
        private static final long serialVersionUID = 1L;

        DuplicateAttempt() {
            super("Appointment booking attempt is already recorded", false);
        }
    }

    /**
     * The Business, Customer, Service, or StaffMember does not exist in the Business. It does not
     * say which reference, and a foreign-Business reference is indistinguishable from a missing one.
     */
    public static final class UnknownReference extends AppointmentPersistenceException {
        private static final long serialVersionUID = 1L;

        UnknownReference() {
            super("Appointment reference does not exist in the Business", false);
        }
    }

    /** The database rejected a value that violates an approved Appointment data rule. */
    public static final class InvalidData extends AppointmentPersistenceException {
        private static final long serialVersionUID = 1L;

        InvalidData() {
            super("Appointment data was rejected", false);
        }
    }

    /**
     * A PostgreSQL serialization failure ({@code 40001}) or deadlock ({@code 40P01}). Retry is
     * possible only in a completely new transaction.
     */
    public static final class ConcurrentFailure extends AppointmentPersistenceException {
        private static final long serialVersionUID = 1L;

        ConcurrentFailure() {
            super("Appointment operation conflicted with a concurrent change", false);
        }
    }

    /**
     * Any other failure. The optional five-character SQLState is the only retained classification
     * (it contains no personal data); it is {@code null} when the failure was not an SQL error.
     */
    public static final class UnexpectedFailure extends AppointmentPersistenceException {
        private static final long serialVersionUID = 1L;

        private final String sqlState;

        UnexpectedFailure(String sqlState) {
            super("Appointment persistence operation failed", true);
            this.sqlState = sqlState;
        }

        public String sqlState() {
            return sqlState;
        }
    }
}
