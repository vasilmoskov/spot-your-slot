package bg.spotyourslot.scheduling.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The owner-editable content of one schedule exception aggregate. The compact
 * constructor enforces the invariants PostgreSQL cannot express because they
 * depend on child-row counts: full-day aggregates have no periods, partial
 * closures/time off and additional working periods have at least one, and a
 * working-day override may have zero or more.
 *
 * <p>Periods are stored sorted by start then end. Duplicate and overlapping
 * periods are rejected; adjacent periods stay separate and are never merged.
 *
 * @param staffMemberId absent exactly for {@link ScheduleExceptionKind#BUSINESS_CLOSURE}
 * @param firstDate first local date, inclusive
 * @param lastDate last local date, inclusive; equal to {@code firstDate} unless full-day
 * @param allDay whether the block covers whole local dates
 */
public record ScheduleExceptionContent(
        ScheduleExceptionKind kind,
        UUID staffMemberId,
        LocalDate firstDate,
        LocalDate lastDate,
        boolean allDay,
        List<LocalPeriod> periods) {
    private static final Comparator<LocalPeriod> BY_START_THEN_END =
            Comparator.comparing(LocalPeriod::start).thenComparing(LocalPeriod::end);

    public ScheduleExceptionContent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(firstDate, "firstDate");
        Objects.requireNonNull(lastDate, "lastDate");
        Objects.requireNonNull(periods, "periods");
        if (kind.isStaffScoped() == (staffMemberId == null)) {
            throw new IllegalArgumentException(kind.isStaffScoped()
                    ? "A StaffMember is required for " + kind
                    : "A StaffMember is not allowed for " + kind);
        }
        if (lastDate.isBefore(firstDate)) {
            throw new IllegalArgumentException("Last date must not precede the first date");
        }
        if (!allDay && !firstDate.equals(lastDate)) {
            throw new IllegalArgumentException("A period-based exception applies to one local date");
        }
        if (!kind.isBlock() && allDay) {
            throw new IllegalArgumentException("A working exception cannot be full-day");
        }
        periods = sorted(periods);
        if (allDay && !periods.isEmpty()) {
            throw new IllegalArgumentException("A full-day exception has no periods");
        }
        if (!allDay && periods.isEmpty() && kind != ScheduleExceptionKind.WORKING_DAY_OVERRIDE) {
            throw new IllegalArgumentException("A period-based " + kind + " requires at least one period");
        }
    }

    public static ScheduleExceptionContent businessClosureDays(LocalDate firstDate, LocalDate lastDate) {
        return new ScheduleExceptionContent(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, firstDate, lastDate, true, List.of());
    }

    public static ScheduleExceptionContent businessClosurePartial(
            LocalDate date, List<LocalPeriod> periods) {
        return new ScheduleExceptionContent(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, date, date, false, periods);
    }

    public static ScheduleExceptionContent staffTimeOffDays(
            UUID staffMemberId, LocalDate firstDate, LocalDate lastDate) {
        return new ScheduleExceptionContent(
                ScheduleExceptionKind.STAFF_TIME_OFF,
                staffMemberId,
                firstDate,
                lastDate,
                true,
                List.of());
    }

    public static ScheduleExceptionContent staffTimeOffPartial(
            UUID staffMemberId, LocalDate date, List<LocalPeriod> periods) {
        return new ScheduleExceptionContent(
                ScheduleExceptionKind.STAFF_TIME_OFF, staffMemberId, date, date, false, periods);
    }

    public static ScheduleExceptionContent workingDayOverride(
            UUID staffMemberId, LocalDate date, List<LocalPeriod> periods) {
        return new ScheduleExceptionContent(
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE, staffMemberId, date, date, false, periods);
    }

    public static ScheduleExceptionContent additionalWorkingPeriods(
            UUID staffMemberId, LocalDate date, List<LocalPeriod> periods) {
        return new ScheduleExceptionContent(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                staffMemberId,
                date,
                date,
                false,
                periods);
    }

    private static List<LocalPeriod> sorted(List<LocalPeriod> source) {
        List<LocalPeriod> copy = new ArrayList<>(source.size());
        for (LocalPeriod period : source) {
            copy.add(Objects.requireNonNull(period, "period"));
        }
        copy.sort(BY_START_THEN_END);
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index).start().isBefore(copy.get(index - 1).end())) {
                throw new IllegalArgumentException("Periods must not duplicate or overlap");
            }
        }
        return List.copyOf(copy);
    }
}
