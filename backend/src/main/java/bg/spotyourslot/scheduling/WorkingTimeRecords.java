package bg.spotyourslot.scheduling;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Published, deeply immutable records of {@link WorkingTimeOverview}. */
public final class WorkingTimeRecords {
    private WorkingTimeRecords() {
    }

    public enum ClosureKind {
        BUSINESS_CLOSURE,
        STAFF_TIME_OFF
    }

    /** A Business-local date with its zone-rule bounds (a daylight-saving day is 23 or 25 hours). */
    public record Day(LocalDate date, Instant start, Instant end) {
    }

    /** A half-open working interval {@code [start, end)} in instants, net of partial exceptions. */
    public record Interval(Instant start, Instant end) {
    }

    /** A date blocked for the whole day by a full-day exception. */
    public record Closure(LocalDate date, ClosureKind kind) {
    }

    /**
     * @param displayName the current profile name
     * @param workingIntervals ordered by start; empty for an inactive StaffMember
     * @param closures ordered by date; empty for an inactive StaffMember
     */
    public record StaffWorkingTime(
            UUID id,
            String displayName,
            boolean active,
            List<Interval> workingIntervals,
            List<Closure> closures) {
        public StaffWorkingTime {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            workingIntervals = List.copyOf(workingIntervals);
            closures = List.copyOf(closures);
        }

        @Override
        public String toString() {
            return "StaffWorkingTime[redacted]";
        }
    }

    public record WorkingTime(List<Day> days, List<StaffWorkingTime> staffMembers) {
        public WorkingTime {
            days = List.copyOf(days);
            staffMembers = List.copyOf(staffMembers);
        }
    }

    /** A sanitized failure of the working-time read; fixed message, no cause. */
    public static final class WorkingTimeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public WorkingTimeFailure() {
            super("Working time calculation failed", null, false, true);
        }
    }
}
