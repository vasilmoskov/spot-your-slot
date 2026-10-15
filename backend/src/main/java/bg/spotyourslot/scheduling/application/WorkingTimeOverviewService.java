package bg.spotyourslot.scheduling.application;

import bg.spotyourslot.scheduling.WorkingTimeOverview;
import bg.spotyourslot.scheduling.WorkingTimeRecords.Closure;
import bg.spotyourslot.scheduling.WorkingTimeRecords.ClosureKind;
import bg.spotyourslot.scheduling.WorkingTimeRecords.Day;
import bg.spotyourslot.scheduling.WorkingTimeRecords.Interval;
import bg.spotyourslot.scheduling.WorkingTimeRecords.StaffWorkingTime;
import bg.spotyourslot.scheduling.WorkingTimeRecords.WorkingTime;
import bg.spotyourslot.scheduling.WorkingTimeRecords.WorkingTimeFailure;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator;
import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator.StaffInput;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import bg.spotyourslot.workforce.StaffCalendarAccess;
import bg.spotyourslot.workforce.StaffCalendarAccess.CalendarStaffMember;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles {@link WorkingTimeOverview}: two statements inside the caller's transaction (the
 * StaffMembers with their weekly periods, then the exceptions of the selected StaffMembers and the
 * Business closures), and the pure {@link WorkingTimeCalculator}. Unexpected failures are replaced
 * by a fixed, cause-free exception.
 */
@Component
public class WorkingTimeOverviewService implements WorkingTimeOverview {
    private static final int MAX_DATES = 31;

    private final StaffCalendarAccess staff;
    private final ScheduleExceptionStore exceptions;

    public WorkingTimeOverviewService(StaffCalendarAccess staff, ScheduleExceptionStore exceptions) {
        this.staff = staff;
        this.exceptions = exceptions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public WorkingTime overview(
            UUID businessId,
            ZoneId zone,
            LocalDate firstDate,
            LocalDate lastDate,
            UUID staffMemberIdOrNull,
            Set<UUID> staffMemberIdsWithAppointments) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(firstDate, "firstDate");
        Objects.requireNonNull(lastDate, "lastDate");
        Objects.requireNonNull(staffMemberIdsWithAppointments, "staffMemberIdsWithAppointments");
        if (lastDate.isBefore(firstDate)
                || ChronoUnit.DAYS.between(firstDate, lastDate) + 1 > MAX_DATES) {
            throw new IllegalArgumentException("The window holds between one and 31 dates");
        }
        try {
            List<CalendarStaffMember> found = staff.find(businessId, staffMemberIdOrNull, true);
            List<CalendarStaffMember> included = new ArrayList<>();
            for (CalendarStaffMember member : found) {
                if (staffMemberIdOrNull != null
                        || member.active()
                        || staffMemberIdsWithAppointments.contains(member.id())) {
                    included.add(member);
                }
            }
            List<ScheduleException> stored = exceptions.findOverlappingForStaff(
                    businessId,
                    firstDate,
                    lastDate,
                    included.stream().filter(CalendarStaffMember::active).map(CalendarStaffMember::id)
                            .toList());
            List<StaffInput> inputs = included.stream().map(WorkingTimeOverviewService::input).toList();
            List<StaffWorkingTime> calculated = WorkingTimeCalculator.calculate(
                            zone, firstDate, lastDate, inputs, stored).stream()
                    .map(WorkingTimeOverviewService::published)
                    .toList();
            List<Day> days = WorkingTimeCalculator.days(zone, firstDate, lastDate).stream()
                    .map(day -> new Day(day.date(), day.start(), day.end()))
                    .toList();
            return new WorkingTime(days, calculated);
        } catch (RuntimeException failure) {
            throw new WorkingTimeFailure();
        }
    }

    private static StaffInput input(CalendarStaffMember member) {
        Map<DayOfWeek, List<LocalPeriod>> byDay = new EnumMap<>(DayOfWeek.class);
        for (WorkingPeriod period : member.weeklyPeriods()) {
            byDay.computeIfAbsent(period.weekday(), day -> new ArrayList<>())
                    .add(new LocalPeriod(period.startTime(), period.endTime()));
        }
        return new StaffInput(member.id(), member.displayName(), member.active(), byDay);
    }

    private static StaffWorkingTime published(WorkingTimeCalculator.StaffWorkingTime time) {
        return new StaffWorkingTime(
                time.id(),
                time.displayName(),
                time.active(),
                time.workingIntervals().stream()
                        .map(interval -> new Interval(interval.start(), interval.end()))
                        .toList(),
                time.closures().stream()
                        .map(closure -> new Closure(
                                closure.date(), ClosureKind.valueOf(closure.kind().name())))
                        .toList());
    }
}
