package bg.spotyourslot.workforce;

public abstract sealed class StaffMemberApplicationException extends RuntimeException
        permits StaffMemberApplicationException.BusinessAccessDenied,
                StaffMemberApplicationException.BusinessSuspended,
                StaffMemberApplicationException.ConcurrentUpdate,
                StaffMemberApplicationException.InvalidInput,
                StaffMemberApplicationException.InvalidLifecycleTransition,
                StaffMemberApplicationException.ServiceInactive,
                StaffMemberApplicationException.ServiceNotFound,
                StaffMemberApplicationException.StaffMemberNotFound {
    private StaffMemberApplicationException(String safeMessage) {
        super(safeMessage);
    }

    public enum InputField {
        COMMAND,
        BUSINESS_ID,
        STAFF_MEMBER_ID,
        PAGE,
        SIZE,
        DISPLAY_NAME,
        CONTACT_EMAIL,
        CONTACT_PHONE,
        SERVICE_IDS,
        EXPECTED_VERSION
    }

    public static final class InvalidInput extends StaffMemberApplicationException {
        private final InputField field;

        public InvalidInput(InputField field) {
            super("StaffMember input is invalid");
            this.field = field;
        }

        public InputField field() {
            return field;
        }
    }

    public static final class BusinessAccessDenied extends StaffMemberApplicationException {
        public BusinessAccessDenied() {
            super("Business access to StaffMembers is denied");
        }
    }

    public static final class StaffMemberNotFound extends StaffMemberApplicationException {
        public StaffMemberNotFound() {
            super("StaffMember was not found");
        }
    }

    public static final class InvalidLifecycleTransition
            extends StaffMemberApplicationException {
        public InvalidLifecycleTransition() {
            super("StaffMember lifecycle transition is not allowed");
        }
    }

    public static final class ConcurrentUpdate extends StaffMemberApplicationException {
        public ConcurrentUpdate() {
            super("StaffMember was changed by another operation");
        }
    }

    public static final class BusinessSuspended extends StaffMemberApplicationException {
        public BusinessSuspended() {
            super("Suspended Business cannot mutate StaffMembers");
        }
    }

    public static final class ServiceNotFound extends StaffMemberApplicationException {
        public ServiceNotFound() {
            super("Service was not found");
        }
    }

    public static final class ServiceInactive extends StaffMemberApplicationException {
        public ServiceInactive() {
            super("Inactive Service cannot be assigned to StaffMember");
        }
    }
}
