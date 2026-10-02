package bg.spotyourslot.customer.infrastructure;

/**
 * The only failures {@link CustomerStore} reports. Every message is fixed, and no instance retains
 * a cause or a suppressed exception: a database exception can contain SQL, constraint text,
 * identifiers, and the offending phone, email, or name, so it is classified structurally and then
 * discarded.
 *
 * <p>Stack traces: the expected classifications are lightweight (no stack trace, as they are
 * ordinary business outcomes the caller handles by type), while {@link UnexpectedFailure} keeps its
 * own normal stack trace so a production failure identifies the Customer operation and call path.
 * A stack trace holds only application class and method names, never a value, SQL, or a database
 * message.
 */
public abstract sealed class CustomerPersistenceException extends RuntimeException
        permits CustomerPersistenceException.DuplicatePhone,
                CustomerPersistenceException.DuplicateEmail,
                CustomerPersistenceException.UnknownBusiness,
                CustomerPersistenceException.InvalidData,
                CustomerPersistenceException.UnexpectedFailure {
    private static final long serialVersionUID = 1L;

    private CustomerPersistenceException(String safeMessage, boolean captureStackTrace) {
        super(safeMessage, null, false, captureStackTrace);
    }

    /** Another Customer of the same Business already holds the phone. */
    public static final class DuplicatePhone extends CustomerPersistenceException {
        private static final long serialVersionUID = 1L;

        DuplicatePhone() {
            super("Customer phone is already in use", false);
        }
    }

    /** Another Customer of the same Business already holds the email. */
    public static final class DuplicateEmail extends CustomerPersistenceException {
        private static final long serialVersionUID = 1L;

        DuplicateEmail() {
            super("Customer email is already in use", false);
        }
    }

    /** The referenced Business does not exist. */
    public static final class UnknownBusiness extends CustomerPersistenceException {
        private static final long serialVersionUID = 1L;

        UnknownBusiness() {
            super("Customer Business does not exist", false);
        }
    }

    /** The database rejected a value that violates an approved Customer data rule. */
    public static final class InvalidData extends CustomerPersistenceException {
        private static final long serialVersionUID = 1L;

        InvalidData() {
            super("Customer data was rejected", false);
        }
    }

    /**
     * Any other failure. The optional five-character SQLState is the only retained classification
     * (it contains no personal data); it is {@code null} when the failure was not an SQL error.
     */
    public static final class UnexpectedFailure extends CustomerPersistenceException {
        private static final long serialVersionUID = 1L;

        private final String sqlState;

        UnexpectedFailure(String sqlState) {
            super("Customer persistence operation failed", true);
            this.sqlState = sqlState;
        }

        public String sqlState() {
            return sqlState;
        }
    }
}
