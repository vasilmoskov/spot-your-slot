package bg.spotyourslot.catalog.infrastructure;

public abstract sealed class ServicePersistenceException extends RuntimeException
        permits ServicePersistenceException.NameConflict,
                ServicePersistenceException.UnexpectedFailure {
    private ServicePersistenceException(String safeMessage) {
        super(safeMessage);
    }

    private ServicePersistenceException(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }

    public static final class NameConflict extends ServicePersistenceException {
        public NameConflict() {
            super("Service name is already reserved");
        }
    }

    public static final class UnexpectedFailure extends ServicePersistenceException {
        public UnexpectedFailure(Throwable cause) {
            super("Service persistence operation failed", cause);
        }
    }
}
