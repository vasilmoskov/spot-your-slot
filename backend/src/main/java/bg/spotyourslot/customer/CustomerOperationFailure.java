package bg.spotyourslot.customer;

/**
 * A sanitized non-concurrency failure of a Customer operation, for example an unavailable
 * database, an unexpected SQLState, an unknown Business, or a corrupt stored row. It is thrown,
 * never returned, and marks the caller's transaction rollback-only. It is distinct from the
 * retryable {@link CustomerConcurrentConflict}.
 *
 * <p>The message is fixed and the exception exposes no reason, value, ID, SQL, constraint text,
 * cause, or suppressed exception. It keeps its own application stack trace for diagnostics.
 */
public final class CustomerOperationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CustomerOperationFailure() {
        super("Customer operation failed", null, false, true);
    }
}
