package bg.spotyourslot.workforce.domain;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.util.List;

public final class WorkingPeriodOverlapValidator {
    private WorkingPeriodOverlapValidator() {
    }

    public static boolean hasOverlap(List<WorkingPeriod> periods) {
        for (int first = 0; first < periods.size(); first++) {
            for (int second = first + 1; second < periods.size(); second++) {
                if (overlaps(periods.get(first), periods.get(second))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean overlaps(WorkingPeriod first, WorkingPeriod second) {
        if (first.weekday() != second.weekday()) {
            return false;
        }
        return first.startTime().isBefore(second.endTime())
                && second.startTime().isBefore(first.endTime());
    }
}
