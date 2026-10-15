package bg.spotyourslot.workforce;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Narrow published read contract for the Business calendar (ADR-0028 section 12). It reads inside
 * the caller's transaction ({@code MANDATORY}, read-only), takes no lock, authorizes nothing (the
 * caller has authorized the owner), and answers with one joined statement whatever the number of
 * StaffMembers.
 */
public interface StaffCalendarAccess {
    /**
     * The StaffMembers of the Business, active or not, ordered by normalized display name and then
     * identifier; or, when {@code staffMemberIdOrNull} is given, only that one (empty when it is
     * missing or foreign, which cannot be told apart).
     *
     * @param withWeeklyPeriods whether the recurring weekly periods of the <em>active</em>
     *        StaffMembers are read; an inactive StaffMember never carries periods
     */
    List<CalendarStaffMember> find(
            UUID businessId, UUID staffMemberIdOrNull, boolean withWeeklyPeriods);

    /**
     * @param displayName the current profile name (an Appointment keeps its own snapshot)
     * @param weeklyPeriods ordered by weekday, start, then end; empty when not requested, when none
     *        are configured, or when the StaffMember is inactive
     */
    record CalendarStaffMember(
            UUID id, String displayName, boolean active, List<WorkingPeriod> weeklyPeriods) {
        public CalendarStaffMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            weeklyPeriods = List.copyOf(weeklyPeriods);
        }

        @Override
        public String toString() {
            return "CalendarStaffMember[redacted]";
        }
    }

    final class StaffCalendarFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StaffCalendarFailure() {
            super("StaffMember calendar access failed", null, false, true);
        }
    }
}
