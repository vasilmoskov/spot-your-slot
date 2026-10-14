package bg.spotyourslot.scheduling;

import bg.spotyourslot.scheduling.domain.AvailabilityPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

        /** The first Business-local date of the bookable window: the date of {@link #calculatedAt}. */
        public LocalDate firstDate() {
            return LocalDate.ofInstant(calculatedAt, timezone);
        }

        /** The last Business-local date of the bookable window (the fixed MVP horizon, ADR-0016). */
        public LocalDate lastDate() {
            return firstDate().plusDays(AvailabilityPolicy.HORIZON_DAYS - 1L);
        }
    }

    /**
     * The result of a Business-side calculation (ADR-0029 section 1): the starts one StaffMember
     * can take on an explicit window of Business-local dates, with no minimum notice. It is
     * deliberately not an {@link AvailabilitySnapshot}, whose window is the fixed public horizon.
     *
     * @param timezone the authoritative Business timezone
     * @param calculatedAt the instant the caller supplied; the calculation read no clock
     * @param occupiedDuration the duration a slot occupies (zero buffers)
     * @param firstDate the first Business-local date calculated
     * @param lastDate the last Business-local date calculated, inclusive
     * @param slots ordered by start instant
     */
    public record BusinessAvailability(
            ZoneId timezone,
            Instant calculatedAt,
            Duration occupiedDuration,
            LocalDate firstDate,
            LocalDate lastDate,
            List<AvailabilitySlot> slots) {
        public BusinessAvailability {
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(calculatedAt, "calculatedAt");
            Objects.requireNonNull(occupiedDuration, "occupiedDuration");
            Objects.requireNonNull(firstDate, "firstDate");
            Objects.requireNonNull(lastDate, "lastDate");
            slots = List.copyOf(slots);
            if (lastDate.isBefore(firstDate)) {
                throw new IllegalArgumentException("The last date must not precede the first date");
            }
        }
    }

    /**
     * The result of a calculation for a selection of one to five Services (ADR-0027).
     *
     * @param snapshot the slots of the whole visit; {@link AvailabilitySnapshot#occupiedDuration()}
     *        is the sum of the selected Service durations
     * @param eligibleStaffMemberIds the active StaffMembers assigned to every selected Service
     *        that were considered, ordered by ID: all of them for "no preference", exactly the
     *        requested one otherwise. Empty means that nobody supports the complete selection,
     *        which differs from a non-empty list whose snapshot has no slots (eligible
     *        StaffMembers without available time). Internal application information, not a
     *        public-exposure decision.
     */
    public record SelectionAvailability(
            AvailabilitySnapshot snapshot, List<UUID> eligibleStaffMemberIds) {
        public SelectionAvailability {
            Objects.requireNonNull(snapshot, "snapshot");
            eligibleStaffMemberIds = List.copyOf(eligibleStaffMemberIds);
        }

        /** Whether at least one StaffMember supports the complete selection. */
        public boolean hasEligibleStaffMembers() {
            return !eligibleStaffMemberIds.isEmpty();
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
