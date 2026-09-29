package bg.spotyourslot.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Published, deeply immutable availability results. They are deliberately
 * separate from the internal {@code scheduling.domain} records.
 */
public final class AvailabilityRecords {
    private AvailabilityRecords() {
    }

    /**
     * @param timezone the authoritative Business timezone
     * @param calculatedAt the single clock instant used for the calculation
     * @param occupiedDuration the Service duration a slot occupies (zero buffers)
     * @param slots ordered by start instant
     */
    public record AvailabilitySnapshot(
            ZoneId timezone,
            Instant calculatedAt,
            Duration occupiedDuration,
            List<AvailabilitySlot> slots) {
        public AvailabilitySnapshot {
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(calculatedAt, "calculatedAt");
            Objects.requireNonNull(occupiedDuration, "occupiedDuration");
            slots = List.copyOf(slots);
        }
    }

    /**
     * One bookable start instant. The instant is the identity; the offset keeps a
     * repeated wall-clock time distinguishable. The StaffMember IDs are internal
     * application information for future deterministic assignment, ordered by
     * {@link UUID#compareTo(UUID)}, and are not a public-exposure decision.
     */
    public record AvailabilitySlot(
            Instant start,
            Instant end,
            ZoneOffset startOffset,
            List<UUID> staffMemberIds) {
        public AvailabilitySlot {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(startOffset, "startOffset");
            staffMemberIds = List.copyOf(staffMemberIds);
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("Slot start must be before its end");
            }
            if (staffMemberIds.isEmpty()) {
                throw new IllegalArgumentException("A slot requires at least one StaffMember");
            }
            for (int index = 1; index < staffMemberIds.size(); index++) {
                if (staffMemberIds.get(index - 1).compareTo(staffMemberIds.get(index)) >= 0) {
                    throw new IllegalArgumentException("StaffMember IDs must be distinct and ordered");
                }
            }
        }
    }
}
