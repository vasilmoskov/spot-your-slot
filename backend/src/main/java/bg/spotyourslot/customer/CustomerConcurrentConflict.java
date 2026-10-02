package bg.spotyourslot.customer;

/**
 * A retryable concurrency failure of {@link CustomerIdentification#findOrCreate}: a PostgreSQL
 * serialization failure ({@code 40001}), a deadlock ({@code 40P01}), or a bounded re-read that is
 * still inconsistent. It is thrown, never returned. Because the Customer capability participates in
 * the caller's transaction, throwing it marks that transaction rollback-only even if the caller
 * catches it; the caller must abandon that transaction and may retry in a completely new one.
 *
 * <p>The message is fixed and the exception holds no value, ID, SQL, constraint text, cause, or
 * suppressed exception. It keeps its own application stack trace for diagnostics.
 */
public final class CustomerConcurrentConflict extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CustomerConcurrentConflict() {
        super("Customer operation conflicted with a concurrent change", null, false, true);
    }
}
