package bg.spotyourslot.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit inputs for one availability calculation.
 *
 * @param zone the authoritative Business timezone
 * @param occupiedDuration elapsed time a slot occupies; the Service duration
 *        plus a buffer, which is zero in the MVP; a positive whole number of
 *        minutes
 * @param now the calculation instant supplied by the caller
 * @param businessClosures Business-wide blocks applying to every StaffMember
 * @param staff eligible StaffMembers, each at most once
 */
public record AvailabilityRequest(
        ZoneId zone,
        Duration occupiedDuration,
        Instant now,
        List<LocalBlock> businessClosures,
        List<StaffAvailabilityInput> staff) {
    public AvailabilityRequest {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(occupiedDuration, "occupiedDuration");
        Objects.requireNonNull(now, "now");
        businessClosures = List.copyOf(businessClosures);
        staff = List.copyOf(staff);
        if (occupiedDuration.isNegative()
                || occupiedDuration.isZero()
                || occupiedDuration.toNanosPart() != 0
                || occupiedDuration.toSecondsPart() != 0) {
            throw new IllegalArgumentException("Occupied duration must be a positive whole number of minutes");
        }
        Set<UUID> seen = new HashSet<>();
        for (StaffAvailabilityInput member : staff) {
            if (!seen.add(member.staffMemberId())) {
                throw new IllegalArgumentException("A StaffMember may appear only once");
            }
        }
    }
}
