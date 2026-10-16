package bg.spotyourslot.booking;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;

/**
 * The published, owner-only Appointment administration (ADR-0028, ADR-0029). This phase publishes
 * only the creation of one atomic visit of one to five Services. The Business and the user come
 * from the {@link AuthenticatedBusinessContext}; no Business identifier is ever a parameter.
 *
 * <p>The implementation is not transactional and must not be called inside a transaction: it owns
 * one new {@code REPEATABLE READ} transaction per attempt and retries a whole attempt at most twice
 * (three attempts). A call made inside a transaction is rejected with
 * {@link BookingOrchestrationFailure} before any clock read or statement.
 *
 * <p>Failures are the typed {@link AppointmentAdministrationException}s or
 * {@link bg.spotyourslot.identity.SelectedBusinessRequired}.
 */
public interface AppointmentAdministration {
    /**
     * Creates a {@code CONFIRMED}, {@code MANUAL} Appointment atomically with all its Service lines,
     * or writes nothing. It never creates, changes, or matches a Customer.
     */
    CreatedAppointment create(AuthenticatedBusinessContext context, CreateAppointmentCommand command);
}
