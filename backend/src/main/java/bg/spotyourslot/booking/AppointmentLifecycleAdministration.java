package bg.spotyourslot.booking;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;

/**
 * The published, owner-only Business cancellation and internal-note edit (ADR-0028 sections 8 and 9,
 * ADR-0029 as amended by the Phase 6 note). The Business and the user come from the
 * {@link AuthenticatedBusinessContext}; no Business identifier is ever a parameter.
 *
 * <p>The implementation is not transactional and must not be called inside a transaction: it owns
 * exactly one {@code READ COMMITTED} transaction per call and <b>never retries</b>. A call made inside
 * a transaction is rejected with {@link BookingOrchestrationFailure} before any clock read or
 * statement. A success is reported only after one primary-key read outside every transaction has
 * confirmed the written facts; otherwise the typed {@link AppointmentAdministrationException}s or
 * {@link bg.spotyourslot.identity.SelectedBusinessRequired} are thrown.
 */
public interface AppointmentLifecycleAdministration {
    /**
     * Cancels a {@code CONFIRMED}, not yet started Appointment as the Business, guarded by the
     * expected version. Nothing but the status, the cancellation facts, the version, and the update
     * time changes.
     */
    UpdatedAppointment cancel(AuthenticatedBusinessContext context, CancelAppointmentCommand command);

    /**
     * Replaces the private internal note of an Appointment of any status, guarded by the expected
     * version. A note equal to the stored one after normalization changes nothing, advances no
     * version, and issues no {@code UPDATE}.
     */
    UpdatedAppointment editInternalNote(AuthenticatedBusinessContext context, EditInternalNoteCommand command);
}
