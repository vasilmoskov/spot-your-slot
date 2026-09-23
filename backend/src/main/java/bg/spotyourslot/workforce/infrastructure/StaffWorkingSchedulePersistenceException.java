package bg.spotyourslot.workforce.infrastructure;

public abstract sealed class StaffWorkingSchedulePersistenceException extends RuntimeException
        permits StaffWorkingSchedulePersistenceException.UnexpectedFailure {
    private StaffWorkingSchedulePersistenceException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }

    public static final class UnexpectedFailure extends StaffWorkingSchedulePersistenceException {
        public UnexpectedFailure(Throwable cause) {
            super("Working schedule persistence operation failed", cause);
        }
    }
}
