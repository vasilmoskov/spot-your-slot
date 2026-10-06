package bg.spotyourslot.business;

/**
 * A sanitized non-concurrency failure of a schedule revision operation, for example a Business
 * without a revision row, an unavailable database, an unexpected SQLState, or a caller transaction
 * that is not repeatable-read for the shared guard. It is thrown, never returned, never retryable by
 * this capability, and never a silent success.
 *
 * <p>The message is fixed and the exception exposes no reason, value, identifier, SQL, constraint
 * text, cause, or suppressed exception. It keeps its own application stack trace for diagnostics.
 */
public final class ScheduleRevisionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ScheduleRevisionFailure() {
        super("Schedule revision operation failed", null, false, true);
    }
}
