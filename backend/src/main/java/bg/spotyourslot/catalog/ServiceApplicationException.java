package bg.spotyourslot.catalog;

public abstract sealed class ServiceApplicationException extends RuntimeException
        permits ServiceApplicationException.InvalidInput {
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
}
