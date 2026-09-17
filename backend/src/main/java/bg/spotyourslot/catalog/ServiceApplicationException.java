package bg.spotyourslot.catalog;

public abstract sealed class ServiceApplicationException extends RuntimeException
        permits ServiceApplicationException.BusinessAccessDenied,
                ServiceApplicationException.BusinessSuspended,
                ServiceApplicationException.ConcurrentUpdate,
                ServiceApplicationException.InvalidInput,
                ServiceApplicationException.InvalidLifecycleTransition,
                ServiceApplicationException.ServiceNameConflict,
                ServiceApplicationException.ServiceNotFound {
    private ServiceApplicationException(String safeMessage) {
        super(safeMessage);
    }

    public enum InputField {
        COMMAND,
        BUSINESS_ID,
        SERVICE_ID,
        PAGE,
        SIZE,
        NAME,
        DESCRIPTION,
        DURATION_MINUTES,
        PRICE,
        EXPECTED_VERSION
    }

    public static final class InvalidInput extends ServiceApplicationException {
        private final InputField field;

        public InvalidInput(InputField field) {
            super("Service input is invalid");
            this.field = field;
        }

        public InputField field() {
            return field;
        }
    }

    public static final class BusinessAccessDenied extends ServiceApplicationException {
        public BusinessAccessDenied() {
            super("Business access to Services is denied");
        }
    }

    public static final class ServiceNotFound extends ServiceApplicationException {
        public ServiceNotFound() {
            super("Service was not found");
        }
    }

    public static final class ServiceNameConflict extends ServiceApplicationException {
        public ServiceNameConflict() {
            super("Service name is already in use");
        }
    }

    public static final class InvalidLifecycleTransition extends ServiceApplicationException {
        public InvalidLifecycleTransition() {
            super("Service lifecycle transition is not allowed");
        }
    }

    public static final class ConcurrentUpdate extends ServiceApplicationException {
        public ConcurrentUpdate() {
            super("Service was changed by another operation");
        }
    }

    public static final class BusinessSuspended extends ServiceApplicationException {
        public BusinessSuspended() {
            super("Suspended Business cannot mutate Services");
        }
    }
}
