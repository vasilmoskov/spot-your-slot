package bg.spotyourslot.business;

import bg.spotyourslot.business.domain.BusinessStatus;

public abstract sealed class BusinessApplicationException extends RuntimeException
        permits BusinessApplicationException.BusinessNotFound,
                BusinessApplicationException.BusinessSlugConflict,
                BusinessApplicationException.InvalidLifecycleTransition,
                BusinessApplicationException.ConcurrentUpdate,
                BusinessApplicationException.InvalidInput {
    private BusinessApplicationException(String safeMessage) {
        super(safeMessage);
    }

    public enum InputField {
        COMMAND,
        BUSINESS_ID,
        PAGE,
        SIZE,
        SLUG,
        DISPLAY_NAME,
        BUSINESS_TYPE,
        TIMEZONE,
        DESCRIPTION,
        ADDRESS,
        PHONE,
        CONTACT_EMAIL,
        EXPECTED_VERSION
    }

    public static final class BusinessNotFound extends BusinessApplicationException {
        public BusinessNotFound() {
            super("Business was not found");
        }
    }

    public static final class BusinessSlugConflict extends BusinessApplicationException {
        public BusinessSlugConflict() {
            super("Business slug is already in use");
        }
    }

    public static final class InvalidLifecycleTransition extends BusinessApplicationException {
        private final BusinessStatus currentStatus;
        private final BusinessStatus targetStatus;

        public InvalidLifecycleTransition(
                BusinessStatus currentStatus, BusinessStatus targetStatus) {
            super("Business lifecycle transition is not allowed");
            this.currentStatus = currentStatus;
            this.targetStatus = targetStatus;
        }

        public BusinessStatus currentStatus() {
            return currentStatus;
        }

        public BusinessStatus targetStatus() {
            return targetStatus;
        }
    }

    public static final class ConcurrentUpdate extends BusinessApplicationException {
        public ConcurrentUpdate() {
            super("Business was changed by another operation");
        }
    }

    public static final class InvalidInput extends BusinessApplicationException {
        private final InputField field;

        public InvalidInput(InputField field) {
            super("Business input is invalid");
            this.field = field;
        }

        public InputField field() {
            return field;
        }
    }
}
