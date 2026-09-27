package bg.spotyourslot.workforce;

public abstract sealed class StaffWorkingScheduleApplicationException extends RuntimeException
        permits StaffWorkingScheduleApplicationException.BusinessAccessDenied,
                StaffWorkingScheduleApplicationException.BusinessSuspended,
                StaffWorkingScheduleApplicationException.ConcurrentUpdate,
                StaffWorkingScheduleApplicationException.InvalidInput,
                StaffWorkingScheduleApplicationException.StaffMemberInactive,
                StaffWorkingScheduleApplicationException.StaffMemberNotFound,
                StaffWorkingScheduleApplicationException.StaffWorkingScheduleNotFound {
    private StaffWorkingScheduleApplicationException(String safeMessage) {
        super(safeMessage);
    }

    public enum InputField {
        STAFF_MEMBER_ID,
        COMMAND,
        EXPECTED_VERSION,
        PERIODS,
        WEEKDAY,
        START_TIME,
        END_TIME,
        PERIOD_RANGE,
        DUPLICATE_PERIOD,
        OVERLAPPING_PERIOD
    }

    public static final class InvalidInput extends StaffWorkingScheduleApplicationException {
        private final InputField field;

        public InvalidInput(InputField field) {
            super("Working schedule input is invalid");
            this.field = field;
        }

        public InputField field() {
            return field;
        }
    }

    public static final class StaffWorkingScheduleNotFound
            extends StaffWorkingScheduleApplicationException {
        public StaffWorkingScheduleNotFound() {
            super("Working schedule was not found");
        }
    }

    public static final class ConcurrentUpdate extends StaffWorkingScheduleApplicationException {
        public ConcurrentUpdate() {
            super("Working schedule was changed by another operation");
        }
    }

    public static final class BusinessAccessDenied extends StaffWorkingScheduleApplicationException {
        public BusinessAccessDenied() {
            super("Business access to working schedules is denied");
        }
    }

    public static final class BusinessSuspended extends StaffWorkingScheduleApplicationException {
        public BusinessSuspended() {
            super("Suspended Business cannot mutate working schedules");
        }
    }

    public static final class StaffMemberNotFound extends StaffWorkingScheduleApplicationException {
        public StaffMemberNotFound() {
            super("StaffMember was not found");
        }
    }

    public static final class StaffMemberInactive extends StaffWorkingScheduleApplicationException {
        public StaffMemberInactive() {
            super("Inactive StaffMember cannot receive a schedule replacement");
        }
    }
}
