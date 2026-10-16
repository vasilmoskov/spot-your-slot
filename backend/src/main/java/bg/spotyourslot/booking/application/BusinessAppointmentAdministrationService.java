package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentAdministration;
import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.CustomerNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.ReviewChanged;
import bg.spotyourslot.booking.AppointmentAdministrationException.ServiceUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Created;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.CreatedIds;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Outcome;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejected;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rescheduled;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffBookingAccess;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implements {@link AppointmentAdministration}: the transaction owner, the bounded whole-transaction
 * retry loop, and the classification of every way an attempt can end (ADR-0029 sections 2, 8, 9). One
 * runner serves the creation and the reschedule/reassignment; each operation supplies only its
 * attempt body ({@link BusinessAppointmentCreationProcedure},
 * {@link BusinessAppointmentRescheduleProcedure}). It is deliberately not {@code @Transactional}: a
 * proxy that joined a caller transaction would defeat the guarantee that every attempt is a
 * completely new transaction. The guest runner is not shared or changed.
 *
 * <p><b>Transactions.</b> A {@link TransactionTemplate} with {@code REQUIRED}, {@code
 * REPEATABLE_READ}, read-write begins each attempt. The entry guard proves no transaction exists, so
 * {@code REQUIRED} always creates a new one; the loop runs outside every transaction, so an attempt
 * has ended before the next begins.
 *
 * <p><b>Only a written outcome commits.</b> A rejection wrote nothing and ends its attempt by
 * rollback.
 *
 * <p><b>Retry.</b> At most {@value #MAX_ATTEMPTS} attempts, immediately, each completely new.
 * Retried: exactly the published, sanitized, cause-free conflicts of the Business schedule context,
 * owner Membership, StaffMember, schedule revision, and Service locks, the Appointment persistence
 * concurrency failure, a duplicate generated public reference, and an Appointment overlap. Nothing
 * here reads an SQLState or a persistence exception: the owning modules translate. Never retried:
 * every deterministic rejection, the entry guard, a failure after a commit, and anything else.
 *
 * <p><b>Rollback versus uncertainty.</b> What happens before the body returns proves {@code COMMIT}
 * was never issued. After a written body returns, Spring's own completion report decides: {@code
 * COMMITTED} followed by a failure is uncertain and never retried; {@code ROLLED_BACK} is a proven
 * rollback and the complete operation is retried in a fresh transaction (within the attempt limit);
 * {@code UNKNOWN} or no report is uncertain. After a normal completion one read outside any
 * transaction verifies the outcome: a created Appointment must exist; a rescheduled one must be at
 * the written version with the requested start and StaffMember (still at the expected version is a
 * proven silent rollback, any other state is uncertain).
 */
@Service
public class BusinessAppointmentAdministrationService implements AppointmentAdministration {
    static final int MAX_ATTEMPTS = 3;

    private final BusinessAppointmentCreationProcedure procedure;
    private final BusinessAppointmentRescheduleProcedure rescheduleProcedure;
    private final BookingDiagnostics diagnostics;
    private final AppointmentStore appointments;
    private final TransactionTemplate transaction;

    BusinessAppointmentAdministrationService(
            BusinessAppointmentCreationProcedure procedure,
            BusinessAppointmentRescheduleProcedure rescheduleProcedure,
            BookingDiagnostics diagnostics,
            AppointmentStore appointments,
            PlatformTransactionManager transactionManager) {
        this.procedure = procedure;
        this.rescheduleProcedure = rescheduleProcedure;
        this.diagnostics = diagnostics;
        this.appointments = appointments;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transaction.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        this.transaction.setReadOnly(false);
    }

    @Override
    public CreatedAppointment create(AuthenticatedBusinessContext context, CreateAppointmentCommand command) {
        return ((Created) perform(context, (userId, businessId, state) ->
                        procedure.execute(userId, businessId, command, state.createdIds)))
                .appointment();
    }

    @Override
    public RescheduledAppointment reschedule(
            AuthenticatedBusinessContext context, RescheduleAppointmentCommand command) {
        return ((Rescheduled) perform(context, (userId, businessId, state) ->
                        rescheduleProcedure.execute(userId, businessId, command)))
                .appointment();
    }

    /** The body of one attempt: it runs inside the attempt's transaction. */
    @FunctionalInterface
    private interface AttemptBody {
        Outcome execute(UUID userId, UUID businessId, AttemptState state);
    }

    /**
     * The shared runner: the entry guard, the identity checks, then at most {@value #MAX_ATTEMPTS}
     * completely new transactions. Returns the verified written outcome ({@code Created} or
     * {@code Rescheduled}) or throws the typed failure.
     */
    private Outcome perform(AuthenticatedBusinessContext context, AttemptBody body) {
        requireNoCallerTransaction();
        if (context == null || context.userId() == null) {
            throw new AccessDenied();
        }
        UUID businessId = context.selectedBusinessId().orElseThrow(SelectedBusinessRequired::new);
        UUID userId = context.userId();

        AttemptEnd end = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            end = runAttempt(userId, businessId, body);
            if (end instanceof Finished finished) {
                return finished.written();
            }
        }
        throw exhausted((Retry) end);
    }

    /**
     * The entry guard. It runs before any clock read and before any SQL, lock, availability, or
     * other collaborator call, and it is the only failure thrown for a caller error of this kind.
     */
    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new BookingOrchestrationFailure();
        }
    }

    private AttemptEnd runAttempt(UUID userId, UUID businessId, AttemptBody body) {
        AttemptState state = new AttemptState(businessId);
        try {
            Outcome outcome = transaction.execute(status -> {
                // Spring reports how the transaction really ended; that, not an exception's content,
                // is the evidence the classification below relies on.
                TransactionSynchronizationManager.registerSynchronization(new CompletionRecorder(state));
                Outcome result = body.execute(userId, businessId, state);
                state.bodyOutcome = result;
                if (isRejected(result)) {
                    status.setRollbackOnly();
                }
                return result;
            });
            return verified(Objects.requireNonNull(outcome), state);
        } catch (AppointmentAdministrationException known) {
            throw known;
        } catch (RuntimeException failure) {
            return classify(failure, state);
        }
    }

    /**
     * The transaction reported a normal completion. A rejection wrote nothing. For a {@code Created}
     * attempt, PostgreSQL answers {@code COMMIT} of an aborted transaction with a silent {@code
     * ROLLBACK} that neither the driver nor JPA reports, so one primary-key read outside any
     * transaction establishes that the Appointment exists. Appointments are never deleted, so
     * absence proves the transaction did not commit; an unreadable result is uncertain.
     */
    private AttemptEnd verified(Outcome outcome, AttemptState state) {
        if (outcome instanceof Rejected rejected) {
            throw rejection(rejected);
        }
        if (outcome instanceof Rescheduled rescheduled) {
            return verifiedReschedule(rescheduled, state);
        }
        try {
            if (appointments.find(state.createdIds.businessId(), state.createdIds.appointmentId()).isPresent()) {
                return new Finished(outcome);
            }
        } catch (RuntimeException unreadable) {
            diagnostics.event("admin-commit-unverifiable", unreadable);
            throw new OutcomeUncertain();
        }
        diagnostics.event("admin-silent-rollback");
        throw new TemporarilyUnavailable();
    }

    /**
     * The read-after-completion rule of a reschedule (ADR-0029 section 9), one primary-key read
     * outside any transaction: the row at the version the attempt wrote, with the requested start and
     * StaffMember, is a confirmed success; the row still at the expected version proves the silent
     * rollback (a known rollback, not retried); every other state, including a row another
     * transaction already changed again or one that cannot be found, is uncertain and the owner
     * reloads. A failed read is uncertain too (the caller maps the exception).
     */
    private AttemptEnd verifiedReschedule(Rescheduled outcome, AttemptState state) {
        RescheduledAppointment written = outcome.appointment();
        Appointment stored;
        try {
            stored = appointments.find(state.businessId, written.id()).orElse(null);
        } catch (RuntimeException unreadable) {
            diagnostics.event("admin-commit-unverifiable", unreadable);
            throw new OutcomeUncertain();
        }
        if (stored == null) {
            diagnostics.event("admin-commit-unverifiable");
            throw new OutcomeUncertain();
        }
        if (stored.version() == written.version()
                && stored.startAt().equals(written.start())
                && stored.staffMemberId().equals(written.staffMemberId())) {
            return new Finished(outcome);
        }
        if (stored.version() == written.version() - 1) {
            diagnostics.event("admin-silent-rollback");
            throw new TemporarilyUnavailable();
        }
        diagnostics.event("admin-commit-unverifiable");
        throw new OutcomeUncertain();
    }

    private AttemptEnd classify(RuntimeException failure, AttemptState state) {
        if (state.bodyOutcome == null) {
            // The body did not return: COMMIT was never issued, so nothing was committed.
            Retry retry = retryReason(failure);
            if (retry != null) {
                diagnostics.event("admin-attempt-retry", failure);
                return retry;
            }
            diagnostics.event("admin-attempt-failed", failure);
            throw new Failure();
        }
        if (isRejected(state.bodyOutcome)) {
            // A rollback-only attempt wrote nothing, whatever went wrong while ending it.
            diagnostics.event("admin-rollback-failed", failure);
            throw rejection((Rejected) state.bodyOutcome);
        }
        return classifyCommitFailure(failure, state);
    }

    /**
     * Classification by the transaction's own completion report, never by what an exception contains
     * (no cause, SQLState, driver, or Spring persistence exception is read):
     *
     * <ul>
     *   <li>{@code COMMITTED}: the database committed and something failed afterwards, so the visit
     *       exists: uncertain, never retried;
     *   <li>{@code ROLLED_BACK}: Spring rolled the transaction back instead of committing, so the
     *       rollback is proven and nothing was written: the complete operation is retried in a fresh
     *       transaction, within the three attempts;
     *   <li>{@code UNKNOWN} or no report: the commit operation itself failed or was never reported, so
     *       whether it committed is not known: uncertain, never retried.
     * </ul>
     */
    private AttemptEnd classifyCommitFailure(RuntimeException failure, AttemptState state) {
        if (state.completion == TransactionSynchronization.STATUS_ROLLED_BACK) {
            diagnostics.event("admin-commit-rolled-back", failure);
            return new Retry(RetryReason.CONCURRENCY);
        }
        diagnostics.event(
                state.completion == TransactionSynchronization.STATUS_COMMITTED
                        ? "admin-failure-after-commit"
                        : "admin-commit-uncertain",
                failure);
        throw new OutcomeUncertain();
    }

    private static Retry retryReason(Throwable failure) {
        if (failure instanceof AppointmentPersistenceException.OverlapConflict) {
            return new Retry(RetryReason.OVERLAP);
        }
        if (failure instanceof BusinessScheduleContextAccess.ConcurrentConflict
                || failure instanceof SelectedBusinessOwnerAccess.ConcurrentConflict
                || failure instanceof StaffBookingAccess.ConcurrentConflict
                || failure instanceof ScheduleRevisionConcurrentConflict
                || failure instanceof ServiceBookingAccess.ConcurrentConflict
                || failure instanceof AppointmentPersistenceException.ConcurrentFailure
                || failure instanceof AppointmentPersistenceException.DuplicatePublicReference) {
            return new Retry(RetryReason.CONCURRENCY);
        }
        return null;
    }

    private AppointmentAdministrationException exhausted(Retry last) {
        if (last.reason() == RetryReason.OVERLAP) {
            // The final attempt still lost the overlap race: the time is taken (ADR-0029 section 8).
            return new SlotUnavailable();
        }
        diagnostics.event("admin-retries-exhausted");
        return new TemporarilyUnavailable();
    }

    private static AppointmentAdministrationException rejection(Rejected rejected) {
        return switch (rejected.reason()) {
            case ACCESS_DENIED -> new AccessDenied();
            case BUSINESS_NOT_ACTIVE -> new BusinessNotActive();
            case BUSINESS_SUSPENDED -> new BusinessSuspended();
            case INVALID_INPUT -> new InvalidInput();
            case CUSTOMER_NOT_FOUND -> new CustomerNotFound();
            case SERVICE_UNAVAILABLE -> new ServiceUnavailable();
            case STAFF_UNAVAILABLE -> new StaffUnavailable();
            case REVIEW_CHANGED -> new ReviewChanged();
            case SLOT_UNAVAILABLE -> new SlotUnavailable();
            case APPOINTMENT_NOT_FOUND -> new AppointmentNotFound();
            case CONCURRENT_UPDATE -> new ConcurrentUpdate();
            case STATE_CONFLICT -> new StateConflict();
        };
    }

    private static boolean isRejected(Outcome outcome) {
        return outcome instanceof Rejected;
    }

    private enum RetryReason {
        CONCURRENCY,
        OVERLAP
    }

    private sealed interface AttemptEnd permits Finished, Retry {
    }

    private record Finished(Outcome written) implements AttemptEnd {
    }

    private record Retry(RetryReason reason) implements AttemptEnd {
    }

    /** What the attempt produced and how its transaction really ended. */
    private static final class AttemptState {
        private static final int NO_COMPLETION = -1;

        private final UUID businessId;
        private final CreatedIds createdIds = new CreatedIds();
        private Outcome bodyOutcome;
        /** One of {@code TransactionSynchronization.STATUS_*}, or {@value #NO_COMPLETION}. */
        private int completion = NO_COMPLETION;

        private AttemptState(UUID businessId) {
            this.businessId = businessId;
        }
    }

    /** Records Spring's completion report for the attempt's transaction. */
    private static final class CompletionRecorder implements TransactionSynchronization {
        private final AttemptState state;

        private CompletionRecorder(AttemptState state) {
            this.state = state;
        }

        @Override
        public void afterCompletion(int status) {
            state.completion = status;
        }
    }
}
