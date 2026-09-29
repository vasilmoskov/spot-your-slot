package bg.spotyourslot.scheduling.domain;

import bg.spotyourslot.scheduling.domain.DayTimeline.Part;
import bg.spotyourslot.scheduling.domain.DayTimeline.Segment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Deterministic, side-effect-free availability calculation. It reads no clock,
 * repository, or security context; every input is explicit. See ADR-0013.
 */
public final class AvailabilityEngine {
    private static final Duration SLOT_STEP = Duration.ofMinutes(AvailabilityPolicy.SLOT_STEP_MINUTES);

    private AvailabilityEngine() {
    }

    /**
     * Combines the per-StaffMember results. A slot's identity is its distinct
     * start instant; every StaffMember available at that instant is retained.
     * Slots are ordered by start instant.
     */
    public static List<AvailableSlot> calculate(AvailabilityRequest request) {
        Map<Instant, TreeSet<UUID>> staffByStart = new TreeMap<>();
        for (StaffAvailabilityInput staff : request.staff()) {
            for (Instant start : calculateStarts(request, staff)) {
                staffByStart.computeIfAbsent(start, key -> new TreeSet<>()).add(staff.staffMemberId());
            }
        }

        List<AvailableSlot> slots = new ArrayList<>(staffByStart.size());
        staffByStart.forEach((start, staffMemberIds) -> slots.add(new AvailableSlot(
                start,
                start.plus(request.occupiedDuration()),
                request.zone().getRules().getOffset(start),
                List.copyOf(staffMemberIds))));
        return List.copyOf(slots);
    }

    /** Distinct available start instants for one StaffMember, in chronological order. */
    public static NavigableSet<Instant> calculateStarts(
            AvailabilityRequest request, StaffAvailabilityInput staff) {
        DayTimeline timeline = new DayTimeline(request.zone());
        Instant earliestStart = request.now().plus(AvailabilityPolicy.MINIMUM_NOTICE);
        LocalDate today = LocalDate.ofInstant(request.now(), request.zone());
        NavigableSet<Instant> starts = new TreeSet<>();

        for (int offset = 0; offset < AvailabilityPolicy.HORIZON_DAYS; offset++) {
            LocalDate date = today.plusDays(offset);
            if (blocksWholeDay(request.businessClosures(), date) || blocksWholeDay(staff.timeOff(), date)) {
                continue;
            }
            List<Part> blocked = blockedParts(timeline, date, request.businessClosures(), staff.timeOff());
            for (LocalPeriod period : workingPeriods(staff, date)) {
                for (Segment segment : timeline.segmentsOf(date, period)) {
                    addStarts(starts, segment, request.occupiedDuration(), earliestStart, blocked,
                            staff.busyIntervals());
                }
            }
        }
        return starts;
    }

    /**
     * The base is the override for the date when one is present, otherwise the
     * recurring weekday periods. Additional periods augment it. Periods are
     * kept as separate segments and are never merged.
     */
    private static List<LocalPeriod> workingPeriods(StaffAvailabilityInput staff, LocalDate date) {
        List<LocalPeriod> base = staff.overrides().containsKey(date)
                ? staff.overrides().get(date)
                : staff.recurringPeriods().getOrDefault(date.getDayOfWeek(), List.of());
        List<LocalPeriod> periods = new ArrayList<>(base);
        periods.addAll(staff.additionalPeriods().getOrDefault(date, List.of()));
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

    private static void addStarts(
            NavigableSet<Instant> starts,
            Segment segment,
            Duration occupiedDuration,
            Instant earliestStart,
            List<Part> blocked,
            List<BusyInterval> busyIntervals) {
        for (Part part : segment.parts()) {
            LocalDateTime firstLocal = alignToGrid(LocalDateTime.ofInstant(part.start(), part.offset()));
            Instant candidate = firstLocal.toInstant(part.offset());
            while (candidate.isBefore(part.end())) {
                Instant end = candidate.plus(occupiedDuration);
                if (!candidate.isBefore(earliestStart)
                        && !end.isAfter(segment.end())
                        && !overlapsAny(candidate, end, blocked, busyIntervals)) {
                    starts.add(candidate);
                }
                candidate = candidate.plus(SLOT_STEP);
            }
        }
    }

    private static boolean overlapsAny(
            Instant start, Instant end, List<Part> blocked, List<BusyInterval> busyIntervals) {
        for (Part part : blocked) {
            if (start.isBefore(part.end()) && part.start().isBefore(end)) {
                return true;
            }
        }
        for (BusyInterval busy : busyIntervals) {
            if (start.isBefore(busy.end()) && busy.start().isBefore(end)) {
                return true;
            }
        }
        return false;
    }

    /** The first local wall-clock time at or after the argument on the 15-minute grid. */
    private static LocalDateTime alignToGrid(LocalDateTime local) {
        LocalDateTime hour = local.truncatedTo(ChronoUnit.HOURS);
        boolean hasFraction = local.getSecond() != 0 || local.getNano() != 0;
        int minute = local.getMinute();
        int steps = minute / AvailabilityPolicy.SLOT_STEP_MINUTES;
        if (minute % AvailabilityPolicy.SLOT_STEP_MINUTES != 0 || hasFraction) {
            steps++;
        }
        return hour.plusMinutes((long) steps * AvailabilityPolicy.SLOT_STEP_MINUTES);
    }
}
