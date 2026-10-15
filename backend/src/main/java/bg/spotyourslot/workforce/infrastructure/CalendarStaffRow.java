package bg.spotyourslot.workforce.infrastructure;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.util.List;
import java.util.UUID;

public record CalendarStaffRow(
        UUID id, String displayName, boolean active, List<WorkingPeriod> weeklyPeriods) {
    public CalendarStaffRow {
        weeklyPeriods = List.copyOf(weeklyPeriods);
    }
}
