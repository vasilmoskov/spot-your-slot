package bg.spotyourslot.scheduling;

import bg.spotyourslot.scheduling.WorkingTimeRecords.WorkingTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

/**
 * Published, read-only working-time facts for the Business calendar (ADR-0028 section 12). It
 * supplies what a day or week grid needs to draw working and non-working time. It is deliberately
 * not an availability calculation: it applies no notice, horizon, grid, Service duration, or
 * Appointment, and nothing derived from it is authoritative for bookability (that stays with
 * {@link AvailabilityQuery} and the database).
 *
 * <p>It joins the caller's transaction ({@code MANDATORY}, read-only), takes no lock, authorizes
 * nothing, and issues exactly two statements: the StaffMembers of the Business and the schedule
 * exceptions that touch the window.
 */
public interface WorkingTimeOverview {
    /**
     * @param zone the Business timezone the caller already read in the same snapshot
     * @param firstDate the first Business-local date, inclusive
     * @param lastDate the last Business-local date, inclusive; at most 31 dates in all
     * @param staffMemberIdOrNull one StaffMember (active or not), or null for the unfiltered rule:
     *        every active StaffMember plus every inactive one named in
     *        {@code staffMemberIdsWithAppointments}
     * @param staffMemberIdsWithAppointments the identifiers of the StaffMembers that have a visible
     *        Appointment in the window; only inactive StaffMembers are affected by it, and it is
     *        ignored when a single StaffMember is requested
     * @return the day bounds and the included StaffMembers; the list is empty only when a requested
     *         single StaffMember is missing or foreign (indistinguishable)
     */
    WorkingTime overview(
            UUID businessId,
            ZoneId zone,
            LocalDate firstDate,
            LocalDate lastDate,
            UUID staffMemberIdOrNull,
            Set<UUID> staffMemberIdsWithAppointments);
}
