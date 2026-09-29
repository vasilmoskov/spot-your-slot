package bg.spotyourslot.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit availability inputs for one eligible StaffMember. The caller is
 * responsible for eligibility (active Business, Service and StaffMember and an
 * active assignment); the engine only calculates for the input it receives.
 *
 * <p>An {@code overrides} entry that is present, even with an empty list,
 * replaces every recurring period for that local date.
 */
public record StaffAvailabilityInput(
        UUID staffMemberId,
        Map<DayOfWeek, List<LocalPeriod>> recurringPeriods,
        Map<LocalDate, List<LocalPeriod>> overrides,
        Map<LocalDate, List<LocalPeriod>> additionalPeriods,
        List<LocalBlock> timeOff,
        List<BusyInterval> busyIntervals) {
    public StaffAvailabilityInput {
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        recurringPeriods = copy(recurringPeriods);
        overrides = copy(overrides);
        additionalPeriods = copy(additionalPeriods);
        timeOff = List.copyOf(timeOff);
        busyIntervals = List.copyOf(busyIntervals);
    }

    private static <K> Map<K, List<LocalPeriod>> copy(Map<K, List<LocalPeriod>> source) {
        Map<K, List<LocalPeriod>> copy = new HashMap<>();
        source.forEach((key, periods) -> copy.put(
                Objects.requireNonNull(key, "key"), List.copyOf(periods)));
        return Map.copyOf(copy);
    }
}
