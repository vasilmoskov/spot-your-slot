package bg.spotyourslot.scheduling.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One bookable start instant. The instant is the slot identity; the offset
 * keeps a repeated wall-clock time distinguishable. The eligible StaffMember
 * IDs are retained for later deterministic assignment and are ordered by
 * {@link UUID#compareTo(UUID)}.
 */
public record AvailableSlot(
        Instant start,
        Instant end,
        ZoneOffset startOffset,
        List<UUID> staffMemberIds) {
    public AvailableSlot {
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
    }

    public LocalDateTime localStart() {
        return LocalDateTime.ofInstant(start, startOffset);
    }
}
