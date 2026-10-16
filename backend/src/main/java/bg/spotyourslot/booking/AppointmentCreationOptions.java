package bg.spotyourslot.booking;

import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationAvailability;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationOptions;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * The published, owner-only, read-only choices behind the manual Appointment create form
 * (ADR-0028 section 4, ADR-0029 section 1; the read side of {@link AppointmentAdministration#create}).
 * Every operation derives the Business and the user from the {@link AuthenticatedBusinessContext};
 * no Business identifier is ever a parameter. Each runs in one read-only {@code REPEATABLE READ}
 * transaction, takes no lock, writes nothing, reads the clock once, and issues a bounded number of
 * statements whatever the number of Services, StaffMembers, Appointments, or slots.
 *
 * <p>The choices follow the availability of the mutation they prepare: only an {@code ACTIVE}
 * Business offers them. A {@code DRAFT} Business is {@link AppointmentCreationOptionsException.BusinessNotActive}
 * and a {@code SUSPENDED} one {@link AppointmentCreationOptionsException.BusinessSuspended}, the same
 * results as the creation. The result is advisory: it reserves nothing and may be stale as soon as it
 * returns; the creation revalidates everything under its own locks.
 *
 * <p>Nothing here assigns a StaffMember: the owner names one explicitly, and the Service facts
 * (names, durations, prices) of the reviewed terms come from the Service administration API.
 */
public interface AppointmentCreationOptions {
    /**
     * The StaffMembers who may perform the complete visit: the active ones assigned to every selected
     * Service (the intersection, ordered by canonical display name and then identifier), together with
     * the authoritative summed duration. An empty list means that nobody supports the whole selection,
     * which differs from eligible StaffMembers without free time (see {@link #availability}).
     *
     * @param serviceIds one to five distinct identifiers
     * @throws AppointmentCreationOptionsException.InvalidInput for an empty, oversized, or duplicated
     *         selection, or when the current durations sum to over 480 minutes
     * @throws AppointmentCreationOptionsException.ServiceUnavailable when any Service is missing,
     *         foreign, or inactive (indistinguishable)
     */
    CreationOptions options(AuthenticatedBusinessContext context, Collection<UUID> serviceIds);

    /**
     * The starts one explicit StaffMember offers on one Business-local date for the visit: no minimum
     * notice (the earliest start is the clock instant, inclusive), the summed current Service duration,
     * the Business timezone, the 15-minute grid, the schedule, its exceptions, and every blocking
     * Appointment. Both occurrences of a repeated local time are distinct instants.
     *
     * @param staffMemberId must be active, of the Business, and assigned to every selected Service
     * @param date a Business-local date within 2000-01-01 and 2100-12-31
     * @throws AppointmentCreationOptionsException.StaffUnavailable when the StaffMember is missing,
     *         foreign, inactive, or unassigned to any selected Service (indistinguishable)
     */
    CreationAvailability availability(
            AuthenticatedBusinessContext context,
            Collection<UUID> serviceIds,
            UUID staffMemberId,
            LocalDate date);
}
