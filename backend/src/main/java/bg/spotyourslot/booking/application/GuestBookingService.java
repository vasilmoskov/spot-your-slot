package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.BookingField;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import bg.spotyourslot.booking.BookingResult;
import bg.spotyourslot.booking.GuestBooking;
import bg.spotyourslot.booking.GuestBookingRequest;
import bg.spotyourslot.booking.domain.InvalidBookingRequest;
import bg.spotyourslot.booking.domain.NormalizedBookingRequest;
import bg.spotyourslot.booking.domain.RequestField;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessBookingAccess;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.workforce.StaffBookingAccess;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implements {@link GuestBooking}: the transaction owner, the bounded whole-transaction retry loop,
 * and the classification of every way an attempt can end (ADR-0023, ADR-0024). It is deliberately
 * not {@code @Transactional}: a proxy that joined a caller transaction would defeat the guarantee
 * that every attempt is a completely new transaction.
 *
 * <p><b>Transactions.</b> A {@link TransactionTemplate} with {@code REQUIRED}, {@code
 * REPEATABLE_READ}, read-write begins each attempt. Because the entry guard proved that no
 * transaction exists, {@code REQUIRED} always creates a new one, so no {@code REQUIRES_NEW},
 * savepoint, or global transaction-manager change is needed. The loop runs outside every
 * transaction, so an attempt has ended (committed or rolled back) before the next begins, and the
 * next sees a fresh snapshot and none of the previous attempt's writes.
 *
 * <p><b>Only {@code Created} commits.</b> Every other result (replay, mismatch, rejection) has
 * written nothing and ends its attempt by rollback, so a Customer is never created without its
 * Appointment and commit uncertainty can arise only for a booking that wrote something.
 *
 * <p><b>Retry.</b> At most {@value #MAX_ATTEMPTS} attempts in total, immediately, each with the same
 * attempt identifier and payload. Retried: the sanitized concurrency conflicts of the schedule
 * revision, Business, Service, StaffMember, and Customer contracts, an Appointment overlap, a duplicate attempt
 * hash or public reference, and a commit-time serialization failure or deadlock reported by
 * PostgreSQL. Never retried: validation and identity outcomes, a payload mismatch, the entry guard,
 * and any other failure.
 *
 * <p><b>Rollback versus uncertainty.</b> Whatever happens before the transaction body returns
 * (including a failed begin or a failed rollback) proves that {@code COMMIT} was never issued, so
 * nothing was committed: a known rollback. After a booking body returns, a failure is a <em>proven
 * rollback</em> only when Spring reports it rolled the transaction back instead of committing
 * ({@link UnexpectedRollbackException}) or PostgreSQL itself reports the rollback as the result of
 * {@code COMMIT}: SQLState {@code 40001} or {@code 40P01} (a transaction conflict) or {@code 25P02}
 * (the server returned {@code ROLLBACK} for an aborted transaction). Every other commit-phase failure
 * (I/O, timeout, connection loss, an unknown error, or any error after a successful commit) is
 * <em>uncertain</em>; an arbitrary connection error never proves rollback.
 */
@Service
public class GuestBookingService implements GuestBooking {
    static final int MAX_ATTEMPTS = 3;

    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";
    private static final String IN_FAILED_SQL_TRANSACTION = "25P02";
    private static final int MAX_CAUSE_DEPTH = 16;

    private final BookingAttemptProcedure procedure;
    private final BookingDiagnostics diagnostics;
    private final AppointmentStore appointments;
    private final TransactionTemplate transaction;

    GuestBookingService(
            BookingAttemptProcedure procedure,
            BookingDiagnostics diagnostics,
            AppointmentStore appointments,
            PlatformTransactionManager transactionManager) {
        this.procedure = procedure;
        this.diagnostics = diagnostics;
        this.appointments = appointments;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transaction.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        this.transaction.setReadOnly(false);
    }

    @Override
    public BookingResult book(GuestBookingRequest request) {
        requireNoCallerTransaction();
        Objects.requireNonNull(request, "request");

        NormalizedBookingRequest normalized;
        try {
            normalized = NormalizedBookingRequest.normalize(
                    request.attemptId(),
                    request.serviceId(),
                    request.staffMemberId(),
                    request.start(),
                    request.displayName(),
                    request.phone(),
                    request.email(),
                    request.note());
        } catch (InvalidBookingRequest invalid) {
            return new BookingResult.InvalidRequest(fields(invalid.fields()));
        }

        AttemptEnd end = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            end = runAttempt(request.businessSlug(), normalized);
            if (end instanceof Finished finished) {
                return finished.result();
            }
        }
        return exhausted((Retry) end);
    }

    /**
     * The entry guard. It runs before any clock read and before any SQL, lock, availability, or
     * Customer call, and it is the only failure that is thrown instead of returned.
     */
    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new BookingOrchestrationFailure();
        }
    }

    private AttemptEnd runAttempt(String businessSlug, NormalizedBookingRequest request) {
        AttemptState state = new AttemptState();
        try {
            BookingResult result = transaction.execute(status -> {
                // Spring reports how the transaction really ended; that, not an exception's content, is
                // the evidence the classification below relies on.
                TransactionSynchronizationManager.registerSynchronization(new CompletionRecorder(state));
                BookingResult outcome = procedure.execute(businessSlug, request, state.createdIds);
                state.bodyResult = outcome;
                if (!(outcome instanceof BookingResult.Created)) {
                    status.setRollbackOnly();
                }
                return outcome;
            });
            return new Finished(verified(Objects.requireNonNull(result), state));
        } catch (RuntimeException failure) {
            return classify(failure, state);
        }
    }

    /**
     * The transaction reported a normal completion for a {@code Created} attempt. PostgreSQL answers
     * {@code COMMIT} of an aborted transaction with a silent {@code ROLLBACK} that neither the driver nor
     * JPA reports (observed), so a normal return does not yet prove the Appointment exists. One primary-key
     * read outside any transaction (autocommit, so it sees only committed data) establishes it. Appointments
     * are never deleted, so absence proves the transaction did not commit. If the read itself fails the
     * outcome is unknown and is reported as uncertain.
     */
    private BookingResult verified(BookingResult result, AttemptState state) {
        if (!(result instanceof BookingResult.Created)) {
            return result;
        }
        try {
            if (appointments.find(state.createdIds.businessId(), state.createdIds.appointmentId()).isPresent()) {
                return result;
            }
        } catch (RuntimeException unreadable) {
            diagnostics.event("commit-unverifiable", unreadable);
            return new BookingResult.OutcomeUncertain();
        }
        diagnostics.event("silent-rollback");
        return new BookingResult.TemporarilyUnavailable();
    }

    private AttemptEnd classify(RuntimeException failure, AttemptState state) {
        if (state.bodyResult == null) {
            // The body did not return: COMMIT was never issued, so nothing was committed.
            Retry retry = retryReason(failure);
            if (retry != null) {
                diagnostics.event("attempt-retry", failure);
                return retry;
            }
            diagnostics.event("attempt-failed", failure);
            return new Finished(new BookingResult.TemporarilyUnavailable());
        }
        if (!(state.bodyResult instanceof BookingResult.Created)) {
            // A rollback-only attempt wrote nothing, whatever went wrong while ending it.
            diagnostics.event("rollback-failed", failure);
            return new Finished(state.bodyResult);
        }
        return classifyCommitFailure(failure, state);
    }

    /**
     * Classification by the transaction's own completion report, never by what an exception contains. An
     * exception thrown by a synchronization callback after the commit carries arbitrary content (even a
     * serialization-failure SQLState or an {@code UnexpectedRollbackException}) and must not be mistaken
     * for evidence about the database.
     *
     * <ul>
     *   <li>{@code COMMITTED}: the database committed and something failed afterwards, so the Appointment
     *       exists: uncertain for the guest (the same attempt replays it), never retried.
     *   <li>{@code ROLLED_BACK}: Spring rolled the transaction back instead of committing (a failed
     *       pre-commit callback, or a rollback-only mark): a proven rollback, retried only when the
     *       failure is a serialization failure or deadlock.
     *   <li>{@code UNKNOWN}: the commit operation itself failed. Only an SQLState the server reported
     *       for that commit proves a rollback ({@code 40001}/{@code 40P01} retry, {@code 25P02} known);
     *       anything else (I/O, timeout, connection loss) is uncertain.
     *   <li>No completion report: uncertain.
     * </ul>
     */
    private AttemptEnd classifyCommitFailure(RuntimeException failure, AttemptState state) {
        String sqlState = sqlState(failure);
        boolean conflict = SERIALIZATION_FAILURE.equals(sqlState) || DEADLOCK_DETECTED.equals(sqlState);
        switch (state.completion) {
            case TransactionSynchronization.STATUS_COMMITTED -> {
                diagnostics.event("failure-after-commit", failure);
                return new Finished(new BookingResult.OutcomeUncertain());
            }
            case TransactionSynchronization.STATUS_ROLLED_BACK -> {
                if (conflict) {
                    diagnostics.event("commit-retry", failure);
                    return new Retry(RetryReason.CONCURRENCY);
                }
                diagnostics.event("commit-rolled-back", failure);
                return new Finished(new BookingResult.TemporarilyUnavailable());
            }
            case TransactionSynchronization.STATUS_UNKNOWN -> {
                if (conflict) {
                    diagnostics.event("commit-retry", failure);
                    return new Retry(RetryReason.CONCURRENCY);
                }
                if (IN_FAILED_SQL_TRANSACTION.equals(sqlState)) {
                    diagnostics.event("commit-rolled-back", failure);
                    return new Finished(new BookingResult.TemporarilyUnavailable());
                }
            }
            default -> {
            }
        }
        diagnostics.event("commit-uncertain", failure);
        return new Finished(new BookingResult.OutcomeUncertain());
    }

    private static Retry retryReason(Throwable failure) {
        if (failure instanceof AppointmentPersistenceException.OverlapConflict) {
            return new Retry(RetryReason.OVERLAP);
        }
        if (failure instanceof ScheduleRevisionConcurrentConflict
                || failure instanceof CustomerConcurrentConflict
                || failure instanceof BusinessBookingAccess.ConcurrentConflict
                || failure instanceof ServiceBookingAccess.ConcurrentConflict
                || failure instanceof StaffBookingAccess.ConcurrentConflict
                || failure instanceof AppointmentPersistenceException.ConcurrentFailure
                || failure instanceof AppointmentPersistenceException.DuplicateAttempt
                || failure instanceof AppointmentPersistenceException.DuplicatePublicReference) {
            return new Retry(RetryReason.CONCURRENCY);
        }
        return null;
    }

    private BookingResult exhausted(Retry last) {
        if (last.reason() == RetryReason.OVERLAP) {
            // The final attempt still lost the overlap race: the time is taken (ADR-0023).
            return new BookingResult.SlotUnavailable();
        }
        diagnostics.event("retries-exhausted");
        return new BookingResult.TemporarilyUnavailable();
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static Set<BookingField> fields(Set<RequestField> requestFields) {
        Set<BookingField> mapped = EnumSet.noneOf(BookingField.class);
        for (RequestField field : requestFields) {
            mapped.add(BookingField.valueOf(field.name()));
        }
        return mapped;
    }

    private enum RetryReason {
        CONCURRENCY,
        OVERLAP
    }

    private sealed interface AttemptEnd permits Finished, Retry {
    }

    private record Finished(BookingResult result) implements AttemptEnd {
    }

    private record Retry(RetryReason reason) implements AttemptEnd {
    }

    /** What the attempt produced and how its transaction really ended. */
    private static final class AttemptState {
        private final BookingAttemptProcedure.CreatedIds createdIds = new BookingAttemptProcedure.CreatedIds();
        private BookingResult bodyResult;
        /** One of {@code TransactionSynchronization.STATUS_*}, or {@value #NO_COMPLETION}. */
        private int completion = NO_COMPLETION;
        private static final int NO_COMPLETION = -1;
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
