package bg.spotyourslot.workforce;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class StaffWorkingScheduleRecords {
    private StaffWorkingScheduleRecords() {
    }

    public record WorkingPeriod(DayOfWeek weekday, LocalTime startTime, LocalTime endTime) {
    }

    public record StaffWorkingScheduleDetails(
            UUID staffMemberId,
            List<WorkingPeriod> periods,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public StaffWorkingScheduleDetails {
            periods = List.copyOf(periods);
        }
    }

    public record ReplaceWorkingPeriodsCommand(List<WorkingPeriod> periods, Long expectedVersion) {
        public ReplaceWorkingPeriodsCommand {
            if (periods != null) {
                periods = Collections.unmodifiableList(new ArrayList<>(periods));
            }
        }
    }
}
