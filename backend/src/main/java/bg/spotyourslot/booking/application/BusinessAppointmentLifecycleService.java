package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedService;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import bg.spotyourslot.booking.AppointmentLifecycleAdministration;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.CancelWritten;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Changed;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.NoteWritten;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Outcome;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Rejected;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Unchanged;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Written;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentCancelledBy;
import bg.spotyourslot.booking.domain.AppointmentStatus;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implements {@link AppointmentLifecycleAdministration}: the transaction owner and the classification
 * of every way a Business cancellation or internal-note edit can end (ADR-0029 as amended by the
 * Phase 6 note). It is deliberately not {@code @Transactional}: a proxy that joined a caller
 * transaction would defeat the guarantee that the call owns its transaction.
 *
 * <p><b>One attempt, never retried.</b> Both operations are short {@code READ COMMITTED} optimistic-
 * version mutations; an automatic second attempt cannot safely reinterpret the caller's expected
 * version or intent, so there is none, not even after a proven rollback. The entry guard rejects an
 * active caller transaction or synchronization before any clock read or statement.
 *
 * <p><b>Completion table</b> (Spring's completion report for the call's own transaction is the only
 * evidence; no cause, SQLState, driver exception, or persistence exception is inspected, except that
 * the published, sanitized, cause-free lock and version conflicts of the owning modules are the typed
 * {@link ConcurrentUpdate}):
 *
 * <ul>
 *   <li>a deterministic rejection: the typed rejection (the transaction ended by rollback);
 *   <li>the body threw before it returned: a published concurrent conflict is {@link ConcurrentUpdate},
 *       anything else {@link Failure}; the transaction was rolled back, nothing was committed;
 *   <li>the body returned a write, then a failure, completion {@code COMMITTED}:
 *       {@link OutcomeUncertain};
 *   <li>the same, completion {@code ROLLED_BACK}: {@link TemporarilyUnavailable};
 *   <li>the same, completion {@code UNKNOWN} or no report: {@link OutcomeUncertain};
 *   <li>a normal completion: one primary-key read outside every transaction. The written version with
 *       the requested facts is the success; the expected version is {@link TemporarilyUnavailable} (a
 *       silent rollback); every other state, a missing row, or an unreadable row is
 *       {@link OutcomeUncertain}. An unchanged note is verified the same way and reported only when
 *       the row is still at the expected version with the same note.
 * </ul>
 */
@Service
public class BusinessAppointmentLifecycleService implements AppointmentLifecycleAdministration {
    private final BusinessAppointmentLifecycleProcedure procedure;
    private final BookingDiagnostics diagnostics;
    private final AppointmentStore appointments;
    private final TransactionTemplate transaction;

    BusinessAppointmentLifecycleService(
            BusinessAppointmentLifecycleProcedure procedure,
            BookingDiagnostics diagnostics,
            AppointmentStore appointments,
            PlatformTransactionManager transactionManager) {
        this.procedure = procedure;
        this.diagnostics = diagnostics;
        this.appointments = appointments;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transaction.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        this.transaction.setReadOnly(false);
    }

    @Override
    public UpdatedAppointment cancel(AuthenticatedBusinessContext context, CancelAppointmentCommand command) {
        return perform(context, (userId, businessId) -> procedure.cancel(userId, businessId, command));
    }

    @Override
    public UpdatedAppointment editInternalNote(
            AuthenticatedBusinessContext context, EditInternalNoteCommand command) {
        return perform(context, (userId, businessId) -> procedure.editInternalNote(userId, businessId, command));
    }

    @FunctionalInterface
    private interface Body {
        Outcome execute(UUID userId, UUID businessId);
    }

    private UpdatedAppointment perform(AuthenticatedBusinessContext context, Body body) {
        requireNoCallerTransaction();
        if (context == null || context.userId() == null) {
            throw new AccessDenied();
        }
        UUID businessId = context.selectedBusinessId().orElseThrow(SelectedBusinessRequired::new);
        UUID userId = context.userId();

        State state = new State();
        try {
            Outcome outcome = transaction.execute(status -> {
                // Spring reports how the transaction really ended; that, not an exception's content,
                // is the evidence the classification below relies on.
                TransactionSynchronizationManager.registerSynchronization(new CompletionRecorder(state));
                Outcome result = body.execute(userId, businessId);
                state.bodyOutcome = result;
                if (result instanceof Rejected) {
                    status.setRollbackOnly();
                }
                return result;
            });
            return verified(Objects.requireNonNull(outcome), businessId);
        } catch (AppointmentAdministrationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw classify(failure, state);
        }
    }

    /** The entry guard: before any clock read, statement, lock, or collaborator call. */
    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new BookingOrchestrationFailure();
        }
    }

    /**
     * The transaction reported a normal completion. One primary-key read outside every transaction
     * decides (PostgreSQL answers {@code COMMIT} of an aborted transaction with a silent {@code
     * ROLLBACK} that neither the driver nor JPA reports, and a write that did commit may already have
     * been changed again).
     */
    private UpdatedAppointment verified(Outcome outcome, UUID businessId) {
        if (outcome instanceof Rejected rejected) {
            throw rejection(rejected);
        }
        Written written = outcome instanceof Changed changed ? changed.written() : ((Unchanged) outcome).expected();
        Appointment stored;
        try {
            stored = appointments.find(businessId, written.appointmentId()).orElse(null);
        } catch (RuntimeException unreadable) {
            diagnostics.event("lifecycle-commit-unverifiable", unreadable);
            throw new OutcomeUncertain();
        }
        if (stored == null) {
            diagnostics.event("lifecycle-commit-unverifiable");
            throw new OutcomeUncertain();
        }
        if (outcome instanceof Unchanged unchanged) {
            if (stored.version() == unchanged.expected().expectedVersion()
                    && Objects.equals(stored.internalNote(), unchanged.expected().note())) {
                return updated(stored);
            }
            diagnostics.event("lifecycle-commit-unverifiable");
            throw new OutcomeUncertain();
        }
        if (stored.version() == written.expectedVersion() + 1 && carriesWrittenFacts(stored, written)) {
            return updated(stored);
        }
        if (stored.version() == written.expectedVersion()) {
            diagnostics.event("lifecycle-silent-rollback");
            throw new TemporarilyUnavailable();
        }
        diagnostics.event("lifecycle-commit-unverifiable");
        throw new OutcomeUncertain();
    }

    private static boolean carriesWrittenFacts(Appointment stored, Written written) {
        if (written instanceof CancelWritten cancelled) {
            return stored.status() == AppointmentStatus.CANCELLED
                    && stored.cancelledBy() == AppointmentCancelledBy.BUSINESS
                    && cancelled.cancelledAt().equals(stored.cancelledAt())
                    && cancelled.cancelledAt().equals(stored.updatedAt());
        }
        NoteWritten note = (NoteWritten) written;
        return Objects.equals(note.note(), stored.internalNote()) && note.updatedAt().equals(stored.updatedAt());
    }

    private AppointmentAdministrationException classify(RuntimeException failure, State state) {
        if (state.bodyOutcome == null) {
            // The body did not return: COMMIT was never issued, so nothing was committed.
            if (isPublishedConflict(failure)) {
                diagnostics.event("lifecycle-concurrent-conflict", failure);
                return new ConcurrentUpdate();
            }
            diagnostics.event("lifecycle-body-failed", failure);
            return new Failure();
        }
        if (state.bodyOutcome instanceof Rejected rejected) {
            // A rollback-only attempt wrote nothing, whatever went wrong while ending it.
            diagnostics.event("lifecycle-rollback-failed", failure);
            return rejection(rejected);
        }
        if (state.bodyOutcome instanceof Unchanged) {
            // Nothing was written, so there is no outcome to be uncertain about.
            diagnostics.event("lifecycle-body-failed", failure);
            return new Failure();
        }
        if (state.completion == TransactionSynchronization.STATUS_ROLLED_BACK) {
            diagnostics.event("lifecycle-commit-rolled-back", failure);
            return new TemporarilyUnavailable();
        }
        diagnostics.event(
                state.completion == TransactionSynchronization.STATUS_COMMITTED
                        ? "lifecycle-failure-after-commit"
                        : "lifecycle-commit-uncertain",
                failure);
        return new OutcomeUncertain();
    }

    /** Exactly the published, sanitized, cause-free conflicts that ADR-0029 section 8 maps. */
    private static boolean isPublishedConflict(Throwable failure) {
        return failure instanceof BusinessScheduleContextAccess.ConcurrentConflict
                || failure instanceof SelectedBusinessOwnerAccess.ConcurrentConflict
                || failure instanceof AppointmentPersistenceException.ConcurrentFailure;
    }

    private static AppointmentAdministrationException rejection(Rejected rejected) {
        return switch (rejected.reason()) {
            case ACCESS_DENIED -> new AccessDenied();
            case BUSINESS_NOT_ACTIVE -> new BusinessNotActive();
            case BUSINESS_SUSPENDED -> new BusinessSuspended();
            case INVALID_INPUT -> new InvalidInput();
            case APPOINTMENT_NOT_FOUND -> new AppointmentNotFound();
            case CONCURRENT_UPDATE -> new ConcurrentUpdate();
            case STATE_CONFLICT -> new StateConflict();
            case CUSTOMER_NOT_FOUND, SERVICE_UNAVAILABLE, STAFF_UNAVAILABLE, REVIEW_CHANGED, SLOT_UNAVAILABLE ->
                    new Failure();
        };
    }

    private static UpdatedAppointment updated(Appointment appointment) {
        List<CreatedService> lines = appointment.serviceLines().stream()
                .map(line -> new CreatedService(
                        line.position(), line.serviceName(), line.durationMinutes(), line.priceEur()))
                .toList();
        return new UpdatedAppointment(
                appointment.id(),
                appointment.publicReference(),
                appointment.status().name(),
                appointment.source().name(),
                appointment.startAt(),
                appointment.endAt(),
                appointment.timezone(),
                appointment.staffMemberId(),
                appointment.staffDisplayName(),
                appointment.customerId(),
                lines,
                appointment.durationMinutes(),
                appointment.priceEur(),
                appointment.internalNote(),
                appointment.version(),
                appointment.createdAt(),
                appointment.updatedAt());
    }

    /** What the attempt produced and how its transaction really ended. */
    private static final class State {
        private static final int NO_COMPLETION = -1;

        private Outcome bodyOutcome;
        /** One of {@code TransactionSynchronization.STATUS_*}, or {@value #NO_COMPLETION}. */
        private int completion = NO_COMPLETION;
    }

    /** Records Spring's completion report for the call's transaction. */
    private static final class CompletionRecorder implements TransactionSynchronization {
        private final State state;

        private CompletionRecorder(State state) {
            this.state = state;
        }

        @Override
        public void afterCompletion(int status) {
            state.completion = status;
        }
    }
}
