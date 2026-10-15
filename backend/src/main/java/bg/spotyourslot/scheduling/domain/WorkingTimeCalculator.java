package bg.spotyourslot.scheduling.domain;

import bg.spotyourslot.scheduling.domain.DayTimeline.Part;
import bg.spotyourslot.scheduling.domain.DayTimeline.Segment;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure, side-effect-free calculation of the working facts a calendar renders (ADR-0028 section 12):
 * for each StaffMember, the net working time as instant intervals and the dates blocked for the
 * whole day. It is <em>not</em> an availability engine: it applies no minimum notice, no horizon, no
 * grid, no Service duration, and no Appointment, and it decides nothing about bookability.
 *
 * <p>The working periods of a date are the override when one is present, otherwise the recurring
 * periods of the weekday, plus any additional periods (the rule of {@link AvailabilityEngine}).
 * Each period is resolved to instants with {@link DayTimeline}, so a daylight-saving gap
 * contributes no time and a repeated hour contributes both occurrences. Partial-day Business
 * closures and StaffMember time off are subtracted, so partial exceptions are represented only
 * through the net intervals.
 *
 * <p>A date gets a closure marker, and no intervals, exactly when a <em>full-day</em> exception
 * covers it: a Business closure of whole days ({@link ClosureKind#BUSINESS_CLOSURE}, which wins)
 * or, otherwise, a StaffMember's whole-day time off ({@link ClosureKind#STAFF_TIME_OFF}). The marker
 * is independent of whether the StaffMember would otherwise work that day. An inactive StaffMember
 * has neither intervals nor markers.
 */
public final class WorkingTimeCalculator {
    private WorkingTimeCalculator() {
    }

    public enum ClosureKind {
        BUSINESS_CLOSURE,
        STAFF_TIME_OFF
    }

    public record Interval(Instant start, Instant end) {
        public Interval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("Interval start must be before its end");
            }
        }
    }

    public record Closure(LocalDate date, ClosureKind kind) {
        public Closure {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(kind, "kind");
        }
    }

    public record DayBounds(LocalDate date, Instant start, Instant end) {
    }

    public record StaffInput(
            UUID id,
            String displayName,
            boolean active,
            Map<DayOfWeek, List<LocalPeriod>> recurringPeriods) {
        public StaffInput {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(recurringPeriods, "recurringPeriods");
        }

        @Override
        public String toString() {
            return "StaffInput[redacted]";
        }
    }

    public record StaffWorkingTime(
            UUID id,
            String displayName,
            boolean active,
            List<Interval> workingIntervals,
            List<Closure> closures) {
        public StaffWorkingTime {
            workingIntervals = List.copyOf(workingIntervals);
            closures = List.copyOf(closures);
        }

        @Override
        public String toString() {
            return "StaffWorkingTime[redacted]";
        }
    }

    /** The zone-rule day bounds of every date from {@code first} through {@code last}. */
    public static List<DayBounds> days(ZoneId zone, LocalDate first, LocalDate last) {
        List<DayBounds> days = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            days.add(new DayBounds(
                    date,
                    date.atStartOfDay(zone).toInstant(),
                    date.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        return List.copyOf(days);
    }

    /**
     * @param exceptions the Business closures and the exceptions of the given StaffMembers whose
     *        dates touch the window
     * @return one result per input StaffMember, in the order given
     */
    public static List<StaffWorkingTime> calculate(
            ZoneId zone,
            LocalDate first,
            LocalDate last,
            List<StaffInput> staff,
            List<ScheduleException> exceptions) {
        Objects.requireNonNull(zone, "zone");
        if (last.isBefore(first)) {
            throw new IllegalArgumentException("The last date must not precede the first date");
        }
        DayTimeline timeline = new DayTimeline(zone);
        List<LocalBlock> closures = ScheduleExceptionInputs.businessClosures(exceptions);
        List<StaffWorkingTime> results = new ArrayList<>(staff.size());
        for (StaffInput member : staff) {
            if (!member.active()) {
                results.add(new StaffWorkingTime(
                        member.id(), member.displayName(), false, List.of(), List.of()));
                continue;
            }
            List<LocalBlock> timeOff = ScheduleExceptionInputs.timeOff(member.id(), exceptions);
            Map<LocalDate, List<LocalPeriod>> overrides =
                    ScheduleExceptionInputs.overrides(member.id(), exceptions);
            Map<LocalDate, List<LocalPeriod>> additional =
                    ScheduleExceptionInputs.additionalPeriods(member.id(), exceptions);
            List<Interval> intervals = new ArrayList<>();
            List<Closure> markers = new ArrayList<>();
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                if (blocksWholeDay(closures, date)) {
                    markers.add(new Closure(date, ClosureKind.BUSINESS_CLOSURE));
                    continue;
                }
                if (blocksWholeDay(timeOff, date)) {
                    markers.add(new Closure(date, ClosureKind.STAFF_TIME_OFF));
                    continue;
                }
                List<Part> blocked = blockedParts(timeline, date, closures, timeOff);
                for (LocalPeriod period : workingPeriods(member, overrides, additional, date)) {
                    for (Segment segment : timeline.segmentsOf(date, period)) {
                        subtract(segment.start(), segment.end(), blocked, intervals);
                    }
                }
            }
            intervals.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));
            results.add(new StaffWorkingTime(
                    member.id(), member.displayName(), true, intervals, markers));
        }
        return List.copyOf(results);
    }

    private static List<LocalPeriod> workingPeriods(
            StaffInput member,
            Map<LocalDate, List<LocalPeriod>> overrides,
            Map<LocalDate, List<LocalPeriod>> additional,
            LocalDate date) {
        List<LocalPeriod> base = overrides.containsKey(date)
                ? overrides.get(date)
                : member.recurringPeriods().getOrDefault(date.getDayOfWeek(), List.of());
        List<LocalPeriod> periods = new ArrayList<>(base);
        periods.addAll(additional.getOrDefault(date, List.of()));
        return periods;
    }

    private static boolean blocksWholeDay(List<LocalBlock> blocks, LocalDate date) {
        for (LocalBlock block : blocks) {
            if (block instanceof LocalBlock.FullDays fullDays && fullDays.covers(date)) {
                return true;
            }
        }
        return false;
    }

    private static List<Part> blockedParts(
            DayTimeline timeline,
            LocalDate date,
            List<LocalBlock> businessClosures,
            List<LocalBlock> timeOff) {
        List<Part> blocked = new ArrayList<>();
        for (List<LocalBlock> blocks : List.of(businessClosures, timeOff)) {
            for (LocalBlock block : blocks) {
                if (block instanceof LocalBlock.PartialDay partial && partial.date().equals(date)) {
                    blocked.addAll(timeline.partsOf(date, partial.period()));
                }
            }
        }
        return blocked;
    }

    /** Appends {@code [start, end)} minus every blocked part, as ordered pieces. */
    private static void subtract(Instant start, Instant end, List<Part> blocked, List<Interval> out) {
        List<Interval> pieces = new ArrayList<>(List.of(new Interval(start, end)));
        for (Part block : blocked) {
            List<Interval> next = new ArrayList<>();
            for (Interval piece : pieces) {
                if (!piece.start().isBefore(block.end()) || !block.start().isBefore(piece.end())) {
                    next.add(piece);
                    continue;
                }
                if (piece.start().isBefore(block.start())) {
                    next.add(new Interval(piece.start(), block.start()));
                }
                if (block.end().isBefore(piece.end())) {
                    next.add(new Interval(block.end(), piece.end()));
                }
            }
            pieces = next;
        }
        out.addAll(pieces);
    }
}
