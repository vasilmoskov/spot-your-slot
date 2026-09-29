package bg.spotyourslot.scheduling;

public abstract sealed class ScheduleExceptionApplicationException extends RuntimeException
        permits ScheduleExceptionApplicationException.BusinessAccessDenied,
                ScheduleExceptionApplicationException.BusinessSuspended,
                ScheduleExceptionApplicationException.ConcurrentUpdate,
                ScheduleExceptionApplicationException.InvalidInput,
                ScheduleExceptionApplicationException.OverlapConflict,
                ScheduleExceptionApplicationException.ScheduleExceptionNotFound,
                ScheduleExceptionApplicationException.StaffMemberInactive,
                ScheduleExceptionApplicationException.StaffMemberNotFound {
    private ScheduleExceptionApplicationException(String safeMessage) {
        super(safeMessage);
    }

    public enum InputField {
        COMMAND,
        KIND,
        STAFF_MEMBER_ID,
        FIRST_DATE,
        LAST_DATE,
        ALL_DAY,
        PERIODS,
        EXPECTED_VERSION,
        WINDOW
    }

    public static final class InvalidInput extends ScheduleExceptionApplicationException {
        private final InputField field;

        public InvalidInput(InputField field) {
            super("Schedule exception input is invalid");
            this.field = field;
        }

        public InputField field() {
            return field;
        }
    }

    public static final class ScheduleExceptionNotFound
            extends ScheduleExceptionApplicationException {
        public ScheduleExceptionNotFound() {
            super("Schedule exception was not found");
        }
    }

    /** A stale version, a concurrent change, or a retryable database concurrency failure. */
    public static final class ConcurrentUpdate extends ScheduleExceptionApplicationException {
        public ConcurrentUpdate() {
            super("Schedule exception was changed by another operation");
        }
    }

    public static final class OverlapConflict extends ScheduleExceptionApplicationException {
        public OverlapConflict() {
            super("Schedule exception conflicts with an existing exception");
        }
    }

    public static final class BusinessAccessDenied extends ScheduleExceptionApplicationException {
        public BusinessAccessDenied() {
            super("Business access to schedule exceptions is denied");
        }
    }

    public static final class BusinessSuspended extends ScheduleExceptionApplicationException {
        public BusinessSuspended() {
            super("Suspended Business cannot mutate schedule exceptions");
        }
    }

    public static final class StaffMemberNotFound extends ScheduleExceptionApplicationException {
        public StaffMemberNotFound() {
            super("StaffMember was not found");
        }
    }

    public static final class StaffMemberInactive extends ScheduleExceptionApplicationException {
        public StaffMemberInactive() {
            super("Inactive StaffMember cannot receive schedule exception changes");
        }
    }
}
