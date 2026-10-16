package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared base of the cancellation and internal-note integration tests (Phase 6): the fixtures of
 * {@link RescheduleAppointmentTest} (real PostgreSQL, owner-created Appointments, a controllable
 * clock, a second StaffMember) plus the real {@link AppointmentLifecycleAdministration} and helpers
 * that run its operations on named booking threads.
 */
public abstract class LifecycleAppointmentTest extends RescheduleAppointmentTest {
    @Autowired protected AppointmentLifecycleAdministration lifecycle;

    // ---- execution -----------------------------------------------------------------------------

    protected CompletableFuture<UpdatedAppointment> submitCancel(String label, CreatedAppointment appointment) {
        return submitCancel(label, ownerContext, new CancelAppointmentCommand(appointment.id(), appointment.version()));
    }

    protected CompletableFuture<UpdatedAppointment> submitCancel(String label, UUID id, long version) {
        return submitCancel(label, ownerContext, new CancelAppointmentCommand(id, version));
    }

    protected CompletableFuture<UpdatedAppointment> submitCancel(
            String label, AuthenticatedBusinessContext context, CancelAppointmentCommand command) {
        return onBookingThread(label, () -> lifecycle.cancel(context, command));
    }

    protected CompletableFuture<UpdatedAppointment> submitNote(
            String label, UUID id, long version, String note) {
        return submitNote(label, ownerContext, new EditInternalNoteCommand(id, version, note));
    }

    protected CompletableFuture<UpdatedAppointment> submitNote(
            String label, AuthenticatedBusinessContext context, EditInternalNoteCommand command) {
        return onBookingThread(label, () -> lifecycle.editInternalNote(context, command));
    }

    protected UpdatedAppointment cancel(CreatedAppointment appointment) {
        return completed(submitCancel("main", appointment));
    }

    protected UpdatedAppointment cancel(UUID id, long version) {
        return completed(submitCancel("main", id, version));
    }

    protected UpdatedAppointment editNote(UUID id, long version, String note) {
        return completed(submitNote("main", id, version, note));
    }

    protected Throwable cancelFailure(UUID id, long version) {
        return failureOf(submitCancel("main", id, version));
    }

    protected Throwable noteFailure(UUID id, long version, String note) {
        return failureOf(submitNote("main", id, version, note));
    }

    /** The columns a successful cancellation may change, and nothing else. */
    protected static final java.util.Set<String> CANCEL_COLUMNS = java.util.Set.of(
            "status", "cancelled_at", "cancelled_by", "version", "updated_at", "xmin_text");
    /** The columns a successful note edit may change, and nothing else. */
    protected static final java.util.Set<String> NOTE_COLUMNS = java.util.Set.of(
            "internal_note", "version", "updated_at", "xmin_text");
}
