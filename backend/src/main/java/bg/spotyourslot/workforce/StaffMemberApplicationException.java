package bg.spotyourslot.workforce;

public abstract sealed class StaffMemberApplicationException extends RuntimeException
        permits StaffMemberApplicationException.InvalidInput {
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
}
