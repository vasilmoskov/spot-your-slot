package bg.spotyourslot.workforce;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Narrow published contract that lets availability calculation read the
 * StaffMembers who may currently perform a Service, with their recurring
 * weekly schedules. It takes no lock and exposes no profile data.
 */
public interface StaffAvailabilityAccess {
    /**
     * Reads inside the caller's transaction with one joined statement; a caller
     * without a transaction is rejected.
     *
     * <p>Returns only StaffMembers of the Business that are active and assigned
     * to the Service, ordered by ID. A qualifying StaffMember without periods is
     * present with an empty list. The Service's own active state is not checked
     * here.
     */
    List<EligibleStaffMember> findEligibleForService(UUID businessId, UUID serviceId);

    /**
     * The set-based form (ADR-0027): one joined statement whatever the number of Services or
     * StaffMembers, inside the caller's transaction. Returns only the active StaffMembers of the
     * Business assigned to <em>every</em> requested Service (the intersection), ordered by ID,
     * each with its recurring periods. A single Service gives exactly
     * {@link #findEligibleForService}. No Service's own active state is checked here.
     *
     * @param serviceIds one or more distinct identifiers; anything else is rejected
     */
    List<EligibleStaffMember> findEligibleForServices(UUID businessId, Collection<UUID> serviceIds);

    /**
     * @param weeklyPeriods recurring local periods ordered by weekday, start, then end
     */
    record EligibleStaffMember(UUID id, List<WorkingPeriod> weeklyPeriods) {
        public EligibleStaffMember {
            Objects.requireNonNull(id, "id");
            weeklyPeriods = List.copyOf(weeklyPeriods);
        }
    }

    final class StaffAvailabilityFailure extends RuntimeException {
        public StaffAvailabilityFailure(Throwable cause) {
            super("StaffMember availability access failed", cause);
        }
    }
}
