package bg.spotyourslot.workforce;

public abstract sealed class StaffWorkingScheduleApplicationException extends RuntimeException
        permits StaffWorkingScheduleApplicationException.ConcurrentUpdate,
                StaffWorkingScheduleApplicationException.InvalidInput,
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
}
