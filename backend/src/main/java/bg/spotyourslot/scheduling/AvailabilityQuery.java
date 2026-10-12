package bg.spotyourslot.scheduling;

import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.AvailabilityRecords.SelectionAvailability;
import java.util.Collection;
import java.util.UUID;

/**
 * Internal published contract that calculates the appointment start times a
 * Business currently offers for one Service (ADR-0013, ADR-0016). It is not an
 * HTTP contract and makes no public-exposure decision.
 *
 * <p>The result is a current view. It reserves nothing and may be stale as soon
 * as it returns; appointment creation must revalidate under its own transaction,
 * locks, and database conflict protection.
 */
public interface AvailabilityQuery {
    /**
     * A standalone call creates a repeatable-read, read-only transaction. A call
     * inside an existing transaction joins it and that transaction must already
     * be repeatable-read or serializable; otherwise, including when no isolation
     * level is exposed, the call fails with {@code AvailabilityFailure} before
     * any clock or data read. Only such a transaction gives every committed
     * database read in this orchestration the one snapshot PostgreSQL fixes at
     * the first statement. The method itself performs no writes and takes no
     * explicit lock, but a joined outer transaction may be read-write. The
     * injected clock is read exactly once.
     *
     * @param staffMemberIdOrNull non-null requests exactly that StaffMember, who
     *        must be active and assigned to the Service; null requests any eligible
     *        StaffMember
     * @throws AvailabilityApplicationException.BusinessNotBookable when the Business
     *         is missing, DRAFT, or SUSPENDED (deliberately indistinguishable)
     * @throws AvailabilityApplicationException.ServiceNotBookable when the Service is
     *         missing, foreign, or inactive
     * @throws AvailabilityApplicationException.StaffMemberNotEligible when a requested
     *         StaffMember is missing, inactive, foreign, or unassigned
     * @throws AvailabilityApplicationException.AvailabilityFailure for any unexpected,
     *         sanitized failure
     */
    AvailabilitySnapshot calculate(UUID businessId, UUID serviceId, UUID staffMemberIdOrNull);

    /**
     * The set-based form for one visit of one to five distinct Services (ADR-0027), with the
     * same transaction, snapshot, clock, and no-write rules as the one-Service method, which
     * delegates here. The engine sees one continuous occupied duration, the sum of the current
     * Service durations; the grid, notice, horizon, interval precedence, and timezone rules are
     * unchanged. The eligible StaffMembers are the active ones assigned to every Service.
     *
     * @param serviceIds one to five distinct, non-null identifiers; anything else is an
     *        {@link IllegalArgumentException} of the caller
     * @param staffMemberIdOrNull non-null requests exactly that StaffMember, who must be
     *        eligible for the complete selection; null requests any eligible StaffMember
     * @throws AvailabilityApplicationException.BusinessNotBookable when the Business
     *         is missing, DRAFT, or SUSPENDED (deliberately indistinguishable)
     * @throws AvailabilityApplicationException.ServiceNotBookable when any Service is
     *         missing, foreign, or inactive
     * @throws AvailabilityApplicationException.SelectionTooLong when the current durations
     *         sum to more than 480 minutes
     * @throws AvailabilityApplicationException.StaffMemberNotEligible when a requested
     *         StaffMember is missing, inactive, foreign, or unassigned to any selected Service
     * @throws AvailabilityApplicationException.AvailabilityFailure for any unexpected,
     *         sanitized failure
     */
    SelectionAvailability calculate(
            UUID businessId, Collection<UUID> serviceIds, UUID staffMemberIdOrNull);
}
