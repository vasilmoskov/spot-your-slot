package bg.spotyourslot.workforce.infrastructure;

public abstract sealed class StaffMemberPersistenceException extends RuntimeException
        permits StaffMemberPersistenceException.UnexpectedFailure {
    private StaffMemberPersistenceException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }

    public static final class UnexpectedFailure extends StaffMemberPersistenceException {
        public UnexpectedFailure(Throwable cause) {
            super("StaffMember persistence operation failed", cause);
        }
    }
}
