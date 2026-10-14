package bg.spotyourslot.scheduling;

import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.AvailabilityRecords.BusinessAvailability;
import bg.spotyourslot.scheduling.AvailabilityRecords.SelectionAvailability;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * The Business-side form (ADR-0029 section 1) for the Business owner's create, move, and
     * reassign operations. It runs the same engine, schedule, exceptions, grid, daylight-saving, and
     * busy-time rules as the public methods, with these differences: no minimum notice (the earliest
     * start is {@code now}, inclusive), an explicit window of Business-local dates instead of the
     * 30-date horizon, a duration supplied by the caller (it looks up no Service), exactly one
     * StaffMember who must be active but need not be assigned to anything, and an optional
     * Appointment whose own interval is not busy time.
     *
     * <p>Transaction rules are those of the public methods: a standalone call creates a
     * repeatable-read, read-only transaction, a joined transaction must already be repeatable-read
     * or serializable, and anything else fails with {@code AvailabilityFailure} before any read. The
     * method takes no lock and writes nothing. It reads <em>no</em> clock: the caller reads its clock
     * once per attempt and passes the instant, so every decision of the attempt shares it.
     *
     * @param occupiedDuration the whole-minute duration a start occupies, 1 to 480 minutes; for a
     *        move it is the stored duration of the Appointment, never the current Services
     * @param firstDate the first Business-local date; not before 2000-01-01
     * @param lastDate the last Business-local date, inclusive; not after 2100-12-31 and at most 31
     *        dates after {@code firstDate} counting both
     * @param now the caller's single clock instant
     * @param ignoredAppointmentIdOrNull the one Appointment whose interval is not busy, or null
     * @throws AvailabilityApplicationException.BusinessNotBookable when the Business is missing,
     *         DRAFT, or SUSPENDED
     * @throws AvailabilityApplicationException.StaffMemberNotEligible when the StaffMember is
     *         missing, foreign, or inactive
     * @throws AvailabilityApplicationException.AvailabilityFailure for any unexpected, sanitized
     *         failure
     * @throws IllegalArgumentException for a caller error, before any read
     */
    BusinessAvailability calculateForBusiness(
            UUID businessId,
            Duration occupiedDuration,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            Instant now,
            UUID ignoredAppointmentIdOrNull);
}
