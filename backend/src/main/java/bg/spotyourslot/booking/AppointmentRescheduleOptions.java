package bg.spotyourslot.booking;

import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleAvailability;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleOptions;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The published, owner-only, read-only choices behind the reschedule and reassignment form
 * (ADR-0028 sections 4 and 6, ADR-0029 section 1; the read side of
 * {@link AppointmentAdministration#reschedule}). The Business and the user come from the
 * {@link AuthenticatedBusinessContext}; no Business identifier, duration, price, Service, or ignored
 * Appointment is ever a parameter: the duration and the Services are the stored facts of the named
 * Appointment, and that Appointment is the only one ignored as busy time. Each operation runs in one
 * read-only {@code REPEATABLE READ} transaction, takes no lock, writes nothing, reads the clock once,
 * and issues a bounded number of statements whatever the number of Service lines, StaffMembers,
 * Appointments, or slots.
 *
 * <p>The choices follow the mutation they prepare: only an {@code ACTIVE} Business offers them
 * ({@code DRAFT} is {@link AppointmentRescheduleOptionsException.BusinessNotActive}, {@code SUSPENDED}
 * {@link AppointmentRescheduleOptionsException.BusinessSuspended}), and an Appointment that is not
 * {@code CONFIRMED} or has started is {@link AppointmentRescheduleOptionsException.StateConflict}.
 * The result is advisory: it reserves nothing and may be stale as soon as it returns; the reschedule
 * revalidates everything under its own locks. Nothing here assigns a StaffMember.
 */
public interface AppointmentRescheduleOptions {
    /**
     * The StaffMember choices for moving the Appointment: the current StaffMember (whether still
     * active, which is all a time-only move needs) and the other active StaffMembers assigned to every
     * stored Service line.
     *
     * @throws AppointmentRescheduleOptionsException.AppointmentNotFound when the Appointment is
     *         missing or foreign (indistinguishable)
     * @throws AppointmentRescheduleOptionsException.StateConflict when it is cancelled or has started
     */
    RescheduleOptions options(AuthenticatedBusinessContext context, UUID appointmentId);

    /**
     * The starts one explicit StaffMember offers on one Business-local date for the stored duration of
     * the Appointment: no minimum notice (the earliest start is the clock instant, inclusive), the
     * Business timezone, the 15-minute grid, the schedule, its exceptions, and every blocking
     * Appointment except this one. For the current StaffMember the Appointment's own current start is
     * not offered, because a request that changes nothing is refused by the reschedule. Both
     * occurrences of a repeated local time are distinct instants.
     *
     * @param staffMemberId the current StaffMember (active) or an active one assigned to every stored
     *        Service line
     * @param date a Business-local date within 2000-01-01 and 2100-12-31
     * @throws AppointmentRescheduleOptionsException.StaffUnavailable when the StaffMember is missing,
     *         foreign, inactive, or an ineligible replacement (indistinguishable)
     */
    RescheduleAvailability availability(
            AuthenticatedBusinessContext context, UUID appointmentId, UUID staffMemberId, LocalDate date);
}
