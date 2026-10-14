package bg.spotyourslot.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * @param minimumNotice how long after {@code now} the earliest candidate start is: two hours for
 *        the public policy, zero for the Business-side policy; never negative
 * @param firstDate the first Business-local date considered
 * @param dateCount how many consecutive Business-local dates are considered, from one to
 *        {@value AvailabilityPolicy#BUSINESS_MAX_DATES}; the public policy considers 30
 * @param businessClosures Business-wide blocks applying to every StaffMember
 * @param staff eligible StaffMembers, each at most once
 */
public record AvailabilityRequest(
        ZoneId zone,
        Duration occupiedDuration,
        Instant now,
        Duration minimumNotice,
        LocalDate firstDate,
        int dateCount,
        List<LocalBlock> businessClosures,
        List<StaffAvailabilityInput> staff) {
    public AvailabilityRequest {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(occupiedDuration, "occupiedDuration");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(minimumNotice, "minimumNotice");
        Objects.requireNonNull(firstDate, "firstDate");
        businessClosures = List.copyOf(businessClosures);
        staff = List.copyOf(staff);
        if (occupiedDuration.isNegative()
                || occupiedDuration.isZero()
                || occupiedDuration.toNanosPart() != 0
                || occupiedDuration.toSecondsPart() != 0) {
            throw new IllegalArgumentException("Occupied duration must be a positive whole number of minutes");
        }
        if (minimumNotice.isNegative()) {
            throw new IllegalArgumentException("Minimum notice must not be negative");
        }
        if (dateCount < 1 || dateCount > AvailabilityPolicy.BUSINESS_MAX_DATES) {
            throw new IllegalArgumentException("The date window must hold between one and 31 dates");
        }
        Set<UUID> seen = new HashSet<>();
        for (StaffAvailabilityInput member : staff) {
            if (!seen.add(member.staffMemberId())) {
                throw new IllegalArgumentException("A StaffMember may appear only once");
            }
        }
    }

    /**
     * The public policy (ADR-0013, ADR-0026): two hours of notice and the 30 Business-local dates
     * from the date of {@code now}. Every caller written before the Business-side policy keeps
     * working unchanged and gets exactly the former behavior.
     */
    public AvailabilityRequest(
            ZoneId zone,
            Duration occupiedDuration,
            Instant now,
            List<LocalBlock> businessClosures,
            List<StaffAvailabilityInput> staff) {
        this(
                zone,
                occupiedDuration,
                now,
                AvailabilityPolicy.MINIMUM_NOTICE,
                LocalDate.ofInstant(now, zone),
                AvailabilityPolicy.HORIZON_DAYS,
                businessClosures,
                staff);
    }
}
