package bg.spotyourslot.scheduling.infrastructure;

/**
 * Safe persistence failures. Messages are fixed strings: they never carry SQL
 * text, constraint names, or submitted data. The cause is retained for
 * server-side diagnosis only.
 */
public abstract sealed class ScheduleExceptionPersistenceException extends RuntimeException
        permits ScheduleExceptionPersistenceException.OverlapConflict,
                ScheduleExceptionPersistenceException.InvalidReference,
                ScheduleExceptionPersistenceException.ConcurrentWriteConflict,
                ScheduleExceptionPersistenceException.UnexpectedFailure {
    private ScheduleExceptionPersistenceException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }

    /** A same-kind, same-scope exception already covers part of the requested dates or times. */
    public static final class OverlapConflict extends ScheduleExceptionPersistenceException {
        public OverlapConflict(Throwable cause) {
            super("Schedule exception conflicts with an existing exception", cause);
        }
    }

    /** The Business or StaffMember reference does not exist within the tenant. */
    public static final class InvalidReference extends ScheduleExceptionPersistenceException {
        public InvalidReference(Throwable cause) {
            super("Schedule exception reference is invalid", cause);
        }
    }

    /** PostgreSQL aborted the write as a deadlock or serialization victim; retrying is safe. */
    public static final class ConcurrentWriteConflict extends ScheduleExceptionPersistenceException {
        public ConcurrentWriteConflict(Throwable cause) {
            super("Schedule exception write conflicted with a concurrent operation", cause);
        }
    }

    public static final class UnexpectedFailure extends ScheduleExceptionPersistenceException {
        public UnexpectedFailure(Throwable cause) {
            super("Schedule exception persistence operation failed", cause);
        }
    }
}
