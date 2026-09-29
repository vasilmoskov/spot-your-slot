package bg.spotyourslot.scheduling;

import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
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
}
