package bg.spotyourslot.booking;

import bg.spotyourslot.booking.AppointmentCalendarRecords.AgendaPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.AppointmentDetail;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarView;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CustomerHistoryPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.HistoryOrder;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The published, owner-only, read-only view of a Business's Appointments (ADR-0028 sections 11 and
 * 12). Every operation derives the Business and the user from the {@link AuthenticatedBusinessContext};
 * no Business identifier is ever a parameter. Each runs in one read-only {@code REPEATABLE READ}
 * transaction, so the reference checks, the count, the rows, the Service lines, the Customer names,
 * and the working-time facts all come from a single database snapshot, and issues a bounded number
 * of statements whatever the number of Appointments, Services, Customers, or StaffMembers.
 *
 * <p>An {@code ACTIVE}, {@code DRAFT}, or {@code SUSPENDED} Business is readable. Only an active
 * {@code BUSINESS_OWNER} of the selected Business is authorized. Failures are the typed
 * {@link AppointmentCalendarException}s or {@link bg.spotyourslot.identity.SelectedBusinessRequired}.
 *
 * <p>Dates are inclusive Business-local dates; a window holds at most 31 of them. Every collection
 * has a documented, deterministic order.
 */
public interface AppointmentCalendar {
    /**
     * The Appointments whose occupied time overlaps the window, with the working facts of the
     * StaffMembers: every active StaffMember, plus an inactive one only when it has an Appointment
     * in the window; or, with a filter, exactly that StaffMember (active or not).
     * Appointments are ordered by start instant, then identifier.
     *
     * @throws AppointmentCalendarException.StaffMemberNotFound when the filter names a missing or
     *         foreign StaffMember
     */
    CalendarView calendar(
            AuthenticatedBusinessContext context, LocalDate from, LocalDate to, UUID staffMemberIdOrNull);

    /** The same Appointments as a server-paginated list in the same fixed order. */
    AgendaPage agenda(
            AuthenticatedBusinessContext context,
            LocalDate from,
            LocalDate to,
            UUID staffMemberIdOrNull,
            Integer page,
            Integer size);

    /**
     * One Appointment with its complete owner-only facts.
     *
     * @throws AppointmentCalendarException.AppointmentNotFound when it is missing or foreign
     */
    AppointmentDetail detail(AuthenticatedBusinessContext context, UUID appointmentId);

    /**
     * The Appointments of one Customer, ordered by start instant then identifier (both ascending for
     * {@link HistoryOrder#OLDEST_FIRST}, both descending for {@link HistoryOrder#NEWEST_FIRST}), optionally
     * only those that start on one Business-local calendar date (the half-open interval from that date's
     * start to the next date's start in the Business timezone, so a 23- or 25-hour day is exact). The order
     * and the date apply before paging, across the whole history, and the total is the filtered count.
     *
     * @param order {@code null} means {@link HistoryOrder#OLDEST_FIRST}
     * @param dateOrNull the Business-local date within the supported range, or {@code null} for every date
     * @throws AppointmentCalendarException.InvalidInput for a date outside the supported range
     * @throws AppointmentCalendarException.CustomerNotFound when the Customer is missing or foreign
     */
    CustomerHistoryPage customerHistory(
            AuthenticatedBusinessContext context,
            UUID customerId,
            Integer page,
            Integer size,
            HistoryOrder order,
            LocalDate dateOrNull);

    /** The history in the default order (oldest first) across every date. */
    CustomerHistoryPage customerHistory(
            AuthenticatedBusinessContext context, UUID customerId, Integer page, Integer size);
}
