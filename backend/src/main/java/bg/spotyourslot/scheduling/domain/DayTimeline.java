package bg.spotyourslot.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts local wall-clock periods to real instant ranges using JDK zone
 * rules. The surrounding timeline is split at every offset transition into
 * pieces of constant offset, so a gap contributes no time and an overlap
 * contributes each occurrence separately.
 */
final class DayTimeline {
    private static final Duration WINDOW_MARGIN = Duration.ofDays(1);

    /** A constant-offset half-open instant range. */
    record Part(Instant start, Instant end, ZoneOffset offset) {
    }

    /**
     * One original working period as a contiguous instant range. Parts of the
     * same period that touch across an offset transition are joined, because
     * elapsed time continues; distinct periods are never joined.
     */
    record Segment(Instant start, Instant end, List<Part> parts) {
        Segment {
            parts = List.copyOf(parts);
        }
    }

    private final ZoneId zone;
    private final ZoneRules rules;

    DayTimeline(ZoneId zone) {
        this.zone = zone;
        this.rules = zone.getRules();
    }

    List<Part> partsOf(LocalDate date, LocalPeriod period) {
        LocalDateTime localStart = date.atTime(period.start());
        LocalDateTime localEnd = date.atTime(period.end());
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant windowStart = dayStart.minus(WINDOW_MARGIN);
        Instant windowEnd = date.plusDays(1).atStartOfDay(zone).toInstant().plus(WINDOW_MARGIN);

        List<Part> parts = new ArrayList<>();
        Instant cursor = windowStart;
        while (cursor.isBefore(windowEnd)) {
            ZoneOffsetTransition next = rules.nextTransition(cursor);
            Instant boundary = next == null || next.getInstant().isAfter(windowEnd)
                    ? windowEnd
                    : next.getInstant();
            ZoneOffset offset = rules.getOffset(cursor);
            LocalDateTime pieceStart = LocalDateTime.ofInstant(cursor, offset);
            LocalDateTime pieceEnd = LocalDateTime.ofInstant(boundary, offset);
            LocalDateTime low = max(localStart, pieceStart);
            LocalDateTime high = min(localEnd, pieceEnd);
            if (low.isBefore(high)) {
                parts.add(new Part(low.toInstant(offset), high.toInstant(offset), offset));
            }
            cursor = boundary;
        }
        return parts;
    }

    List<Segment> segmentsOf(LocalDate date, LocalPeriod period) {
        List<Segment> segments = new ArrayList<>();
        List<Part> current = new ArrayList<>();
        for (Part part : partsOf(date, period)) {
            if (!current.isEmpty() && !current.getLast().end().equals(part.start())) {
                segments.add(segmentOf(current));
                current = new ArrayList<>();
            }
            current.add(part);
        }
        if (!current.isEmpty()) {
            segments.add(segmentOf(current));
        }
        return segments;
    }

    private static Segment segmentOf(List<Part> parts) {
        return new Segment(parts.getFirst().start(), parts.getLast().end(), parts);
    }

    private static LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static LocalDateTime min(LocalDateTime first, LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }
}
