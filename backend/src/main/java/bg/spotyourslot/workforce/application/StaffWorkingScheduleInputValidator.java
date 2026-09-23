package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InputField;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.domain.WorkingPeriodOverlapValidator;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StaffWorkingScheduleInputValidator {
    public static final int MAX_PERIODS = 100;

    public UUID validateStaffMemberId(UUID staffMemberId) {
        if (staffMemberId == null) {
            throw new InvalidInput(InputField.STAFF_MEMBER_ID);
        }
        return staffMemberId;
    }

    public ReplaceWorkingPeriodsCommand validateReplacement(
            ReplaceWorkingPeriodsCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        long expectedVersion = expectedVersion(command.expectedVersion());
        List<WorkingPeriod> periods = command.periods();
        if (periods == null || periods.size() > MAX_PERIODS) {
            throw new InvalidInput(InputField.PERIODS);
        }
        for (WorkingPeriod period : periods) {
            validatePeriod(period);
        }
        if (hasDuplicates(periods)) {
            throw new InvalidInput(InputField.DUPLICATE_PERIOD);
        }
        if (WorkingPeriodOverlapValidator.hasOverlap(periods)) {
            throw new InvalidInput(InputField.OVERLAPPING_PERIOD);
        }
        return new ReplaceWorkingPeriodsCommand(periods, expectedVersion);
    }

    private void validatePeriod(WorkingPeriod period) {
        if (period == null || period.weekday() == null) {
            throw new InvalidInput(InputField.WEEKDAY);
        }
        LocalTime start = period.startTime();
        LocalTime end = period.endTime();
        if (start == null || !isMinutePrecise(start)) {
            throw new InvalidInput(InputField.START_TIME);
        }
        if (end == null || !isMinutePrecise(end)) {
            throw new InvalidInput(InputField.END_TIME);
        }
        if (!start.isBefore(end)) {
            throw new InvalidInput(InputField.PERIOD_RANGE);
        }
    }

    private boolean isMinutePrecise(LocalTime value) {
        return value.getSecond() == 0 && value.getNano() == 0;
    }

    private boolean hasDuplicates(List<WorkingPeriod> periods) {
        Set<WorkingPeriod> unique = new HashSet<>(periods);
        return unique.size() != periods.size();
    }

    private long expectedVersion(Long value) {
        if (value == null || value < 0) {
            throw new InvalidInput(InputField.EXPECTED_VERSION);
        }
        return value;
    }
}
