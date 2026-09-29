package bg.spotyourslot.workforce.infrastructure;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.util.List;
import java.util.UUID;

public record EligibleStaffRow(UUID id, List<WorkingPeriod> weeklyPeriods) {
    public EligibleStaffRow {
        weeklyPeriods = List.copyOf(weeklyPeriods);
    }
}
