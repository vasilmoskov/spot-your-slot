package bg.spotyourslot.scheduling.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure translation of stored schedule exceptions into the explicit inputs of
 * the availability engine (ADR-0013). It performs no filtering by date or
 * eligibility; callers pass the aggregates relevant to the calculation.
 *
 * <p>Full-day blocks become inclusive {@link LocalBlock.FullDays}; each
 * partial period becomes its own {@link LocalBlock.PartialDay}. Override and
 * additional periods stay separate and are never merged, and an override with
 * no periods stays a present, empty override.
 */
public final class ScheduleExceptionInputs {
    private ScheduleExceptionInputs() {
    }

    public static List<LocalBlock> businessClosures(List<ScheduleException> exceptions) {
        return blocks(exceptions, ScheduleExceptionKind.BUSINESS_CLOSURE, null);
    }

    public static List<LocalBlock> timeOff(UUID staffMemberId, List<ScheduleException> exceptions) {
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        return blocks(exceptions, ScheduleExceptionKind.STAFF_TIME_OFF, staffMemberId);
    }

    /**
     * @throws IllegalArgumentException when two overrides target one date, which
     *         the database rejects and the engine cannot resolve
     */
    public static Map<LocalDate, List<LocalPeriod>> overrides(
            UUID staffMemberId, List<ScheduleException> exceptions) {
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        Map<LocalDate, List<LocalPeriod>> result = new HashMap<>();
        for (ScheduleException exception : matching(
                exceptions, ScheduleExceptionKind.WORKING_DAY_OVERRIDE, staffMemberId)) {
            ScheduleExceptionContent content = exception.content();
            if (result.put(content.firstDate(), content.periods()) != null) {
                throw new IllegalArgumentException("Ambiguous overrides for " + content.firstDate());
            }
        }
        return Map.copyOf(result);
    }

    public static Map<LocalDate, List<LocalPeriod>> additionalPeriods(
            UUID staffMemberId, List<ScheduleException> exceptions) {
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        Map<LocalDate, List<LocalPeriod>> collected = new HashMap<>();
        for (ScheduleException exception : matching(
                exceptions, ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS, staffMemberId)) {
            ScheduleExceptionContent content = exception.content();
            collected.computeIfAbsent(content.firstDate(), date -> new ArrayList<>())
                    .addAll(content.periods());
        }
        Map<LocalDate, List<LocalPeriod>> result = new HashMap<>();
        collected.forEach((date, periods) -> result.put(date, List.copyOf(periods)));
        return Map.copyOf(result);
    }

    private static List<LocalBlock> blocks(
            List<ScheduleException> exceptions, ScheduleExceptionKind kind, UUID staffMemberId) {
        List<LocalBlock> blocks = new ArrayList<>();
        for (ScheduleException exception : matching(exceptions, kind, staffMemberId)) {
            ScheduleExceptionContent content = exception.content();
            if (content.allDay()) {
                blocks.add(new LocalBlock.FullDays(content.firstDate(), content.lastDate()));
            } else {
                for (LocalPeriod period : content.periods()) {
                    blocks.add(new LocalBlock.PartialDay(content.firstDate(), period));
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static List<ScheduleException> matching(
            List<ScheduleException> exceptions, ScheduleExceptionKind kind, UUID staffMemberId) {
        List<ScheduleException> matches = new ArrayList<>();
        for (ScheduleException exception : exceptions) {
            ScheduleExceptionContent content = exception.content();
            if (content.kind() == kind && Objects.equals(content.staffMemberId(), staffMemberId)) {
                matches.add(exception);
            }
        }
        return matches;
    }
}
