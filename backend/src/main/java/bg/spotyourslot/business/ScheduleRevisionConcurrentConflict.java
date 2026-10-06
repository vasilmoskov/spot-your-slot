package bg.spotyourslot.business;

/**
 * A retryable concurrency failure of a schedule revision operation: a PostgreSQL serialization
 * failure ({@code 40001}) or a deadlock ({@code 40P01}). It is thrown, never returned. The caller's
 * transaction is aborted; it must be abandoned and may be retried only in a completely new one.
 *
 * <p>The message is fixed and the exception holds no value, identifier, SQL, constraint text, cause,
 * or suppressed exception. It keeps its own application stack trace for diagnostics.
 */
public final class ScheduleRevisionConcurrentConflict extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ScheduleRevisionConcurrentConflict() {
        super("Schedule revision operation conflicted with a concurrent change", null, false, true);
    }
}
