package bg.spotyourslot.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import bg.spotyourslot.booking.BookedAppointment;
import bg.spotyourslot.booking.BookingField;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import bg.spotyourslot.booking.BookingResult;
import bg.spotyourslot.booking.GuestBookingRequest;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessBookingAccess;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.customer.CustomerConcurrentConflict;
import bg.spotyourslot.customer.CustomerOperationFailure;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.AvailabilityFailure;
import bg.spotyourslot.workforce.StaffBookingAccess;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests of the transaction owner and retry classifier with a scripted attempt body and a fake
 * transaction manager. Every commit-phase fault here is an <b>injected</b> exception, not PostgreSQL
 * evidence: it proves the classification rules. The real PostgreSQL behavior behind them is
 * observed in {@code GuestBookingCommitFailureIntegrationTests}.
 */
class GuestBookingServiceTests {
    private static final String ATTEMPT = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final UUID BUSINESS = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final String SENTINEL = "SENTINEL-PRIVATE-TEXT-42";
    private static final BookedAppointment BOOKED = new BookedAppointment(
            "ABCDEFGHJK", BookedAppointment.Status.CONFIRMED, "Подстригване", 30,
            new BigDecimal("25.00"), "Мария", Instant.parse("2026-10-08T07:00:00Z"),
            Instant.parse("2026-10-08T07:30:00Z"), "Europe/Sofia");

    private final BookingAttemptProcedure procedure = mock(BookingAttemptProcedure.class);
    private final AppointmentStore appointments = mock(AppointmentStore.class);
    private final FakeTransactionManager transactions = new FakeTransactionManager();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger diagnosticsLogger = (Logger) LoggerFactory.getLogger(BookingDiagnostics.class);
    private GuestBookingService service;

    @BeforeEach
    void setUp() {
        logs.start();
        diagnosticsLogger.addAppender(logs);
        diagnosticsLogger.setLevel(Level.WARN);
        service = new GuestBookingService(procedure, new BookingDiagnostics(), appointments, transactions);
    }

    @AfterEach
    void tearDown() {
        diagnosticsLogger.detachAppender(logs);
        TransactionSynchronizationManager.clear();
    }

    private static GuestBookingRequest request() {
        return new GuestBookingRequest(
                "salon", ATTEMPT, UUID.randomUUID(), null,
                Instant.parse("2026-10-08T07:00:00Z"), "Ана", "0888123456", null, null);
    }

    /** Scripts the attempt body: each call consumes one step, a result or a failure. */
    private void script(Object... steps) {
        Deque<Object> remaining = new ArrayDeque<>(List.of(steps));
        doAnswer(invocation -> {
            Object step = remaining.removeFirst();
            if (step instanceof RuntimeException failure) {
                throw failure;
            }
            if (step instanceof BookingResult.Created) {
                invocation.<BookingAttemptProcedure.CreatedIds>getArgument(2).record(BUSINESS, APPOINTMENT);
            }
            return step;
        }).when(procedure).execute(any(), any(), any());
        presentInDatabase(true);
    }

    /** What the verification read after a normal commit finds: the Appointment, or nothing. */
    private void presentInDatabase(boolean present) {
        org.mockito.Mockito.when(appointments.find(any(), any())).thenReturn(
                present ? java.util.Optional.of(mock(bg.spotyourslot.booking.domain.Appointment.class))
                        : java.util.Optional.empty());
    }

    // ---- transaction ownership -------------------------------------------------

    @Test
    void anActiveCallerTransactionIsRejectedBeforeAnyWork() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.book(request()))
                .isInstanceOf(BookingOrchestrationFailure.class)
                .hasMessage("Guest booking must be invoked without an active transaction")
                .hasNoCause();

        verifyNoInteractions(procedure);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void anActiveSynchronizationWithoutATransactionIsRejectedToo() {
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.book(request())).isInstanceOf(BookingOrchestrationFailure.class);

        verifyNoInteractions(procedure);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void theRejectionIsNeverARetriedOrReturnedOutcome() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> service.book(request())).isInstanceOf(BookingOrchestrationFailure.class);
        assertThatThrownBy(() -> service.book(request())).isInstanceOf(BookingOrchestrationFailure.class);

        verifyNoInteractions(procedure);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void theGuardRunsBeforeTheRequestIsEvenInspected() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // A null request would be a programming error; the guard still wins.
        assertThatThrownBy(() -> service.book(null)).isInstanceOf(BookingOrchestrationFailure.class);
    }

    @Test
    void everyAttemptIsARequiredRepeatableReadReadWriteTransaction() {
        script(new BookingResult.Created(BOOKED));

        service.book(request());

        assertThat(transactions.definitions).hasSize(1);
        TransactionDefinition definition = transactions.definitions.get(0);
        assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(definition.getIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(definition.isReadOnly()).isFalse();
    }

    @Test
    void anInvalidRequestIsRejectedWithoutBeginningATransaction() {
        GuestBookingRequest invalid = new GuestBookingRequest(
                "salon", "bad-id", UUID.randomUUID(), null,
                Instant.parse("2026-10-08T07:00:00Z"), " ", null, null, null);

        BookingResult result = service.book(invalid);

        assertThat(result).isInstanceOfSatisfying(BookingResult.InvalidRequest.class,
                invalidRequest -> assertThat(invalidRequest.fields()).containsExactlyInAnyOrder(
                        BookingField.ATTEMPT_ID, BookingField.DISPLAY_NAME, BookingField.CONTACT));
        verifyNoInteractions(procedure);
        assertThat(transactions.events).isEmpty();
    }

    // ---- commit versus rollback -------------------------------------------------

    @Test
    void onlyACreatedAppointmentCommits() {
        script(new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.Created(BOOKED));

        assertThat(transactions.events).containsExactly("begin", "commit");
    }

    static Stream<BookingResult> nonCreatedResults() {
        return Stream.of(
                new BookingResult.Replayed(BOOKED),
                new BookingResult.BusinessUnavailable(),
                new BookingResult.InvalidRequest(EnumSet.of(BookingField.CONTACT)),
                new BookingResult.ServiceUnavailable(),
                new BookingResult.StaffMemberUnavailable(),
                new BookingResult.SlotUnavailable(),
                new BookingResult.IdentityConflict(),
                new BookingResult.AttemptMismatch(),
                new BookingResult.TemporarilyUnavailable(),
                new BookingResult.OutcomeUncertain());
    }

    @ParameterizedTest
    @MethodSource("nonCreatedResults")
    void everyOtherResultEndsItsAttemptByRollbackAndIsReturnedUnchanged(BookingResult outcome) {
        script(outcome);

        assertThat(service.book(request())).isEqualTo(outcome);

        assertThat(transactions.events).containsExactly("begin", "rollback");
        verify(procedure, times(1)).execute(any(), any(), any());
    }

    // ---- bounded whole-transaction retry ---------------------------------------

    static Stream<Supplier<RuntimeException>> retryableFailures() {
        return Stream.of(
                ScheduleRevisionConcurrentConflict::new,
                CustomerConcurrentConflict::new,
                BusinessBookingAccess.ConcurrentConflict::new,
                ServiceBookingAccess.ConcurrentConflict::new,
                StaffBookingAccess.ConcurrentConflict::new,
                () -> AppointmentPersistenceExceptions.concurrentFailure(),
                () -> AppointmentPersistenceExceptions.duplicateAttempt(),
                () -> AppointmentPersistenceExceptions.duplicatePublicReference(),
                () -> AppointmentPersistenceExceptions.overlap());
    }

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void aRetryableFailureRunsTheWholeTransactionAgainInANewTransaction(Supplier<RuntimeException> failure) {
        script(failure.get(), new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.Created(BOOKED));

        // The first transaction has ended (rolled back) before the second begins.
        assertThat(transactions.events).containsExactly("begin", "rollback", "begin", "commit");
        assertThat(transactions.definitions).hasSize(2);
        assertThat(transactions.statuses.get(0)).isNotSameAs(transactions.statuses.get(1));
    }

    @Test
    void theSameRequestIsReusedByEveryRetry() {
        script(new CustomerConcurrentConflict(), new CustomerConcurrentConflict(), new BookingResult.Created(BOOKED));

        service.book(request());

        verify(procedure, times(3)).execute(eq("salon"), any(), any());
        assertThat(transactions.events).containsExactly(
                "begin", "rollback", "begin", "rollback", "begin", "commit");
    }

    @ParameterizedTest
    @MethodSource("retryableFailures")
    void threeAttemptsAreTheMaximumAndExhaustionIsAKnownRollback(Supplier<RuntimeException> failure) {
        RuntimeException first = failure.get();
        script(first, failure.get(), failure.get());

        BookingResult result = service.book(request());

        boolean overlap = first instanceof AppointmentPersistenceException.OverlapConflict;
        assertThat(result).isEqualTo(overlap
                ? new BookingResult.SlotUnavailable()
                : new BookingResult.TemporarilyUnavailable());
        verify(procedure, times(3)).execute(any(), any(), any());
        assertThat(transactions.events).containsExactly(
                "begin", "rollback", "begin", "rollback", "begin", "rollback");
    }

    @Test
    void theFinalAttemptDecidesBetweenSlotUnavailableAndTemporarilyUnavailable() {
        script(new CustomerConcurrentConflict(), new CustomerConcurrentConflict(),
                AppointmentPersistenceExceptions.overlap());
        assertThat(service.book(request())).isEqualTo(new BookingResult.SlotUnavailable());

        script(AppointmentPersistenceExceptions.overlap(), AppointmentPersistenceExceptions.overlap(),
                new CustomerConcurrentConflict());
        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());
    }

    static Stream<RuntimeException> neverRetriedFailures() {
        return Stream.of(
                new CustomerOperationFailure(),
                new ScheduleRevisionFailure(),
                new BusinessBookingAccess.Failure(),
                new ServiceBookingAccess.Failure(),
                new StaffBookingAccess.Failure(),
                new AvailabilityFailure(new RuntimeException(SENTINEL)),
                AppointmentPersistenceExceptions.unexpected("08006"),
                AppointmentPersistenceExceptions.invalidData(),
                AppointmentPersistenceExceptions.unknownReference(),
                new IllegalStateException(SENTINEL),
                new NullPointerException(SENTINEL));
    }

    @ParameterizedTest
    @MethodSource("neverRetriedFailures")
    void anyOtherFailureBeforeTheBodyReturnsIsAKnownRollbackAndIsNotRetried(RuntimeException failure) {
        script(failure);

        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());

        verify(procedure, times(1)).execute(any(), any(), any());
        assertThat(transactions.events).containsExactly("begin", "rollback");
    }

    @Test
    void aFailedBeginProvesNothingWasCommittedAndIsNotRetried() {
        transactions.failBegin = new CannotCreateTransactionException("connection refused " + SENTINEL);
        script(new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());

        verifyNoInteractions(procedure);
    }

    @Test
    void aFailedRollbackAfterARejectionStillReturnsTheRejectionBecauseNothingWasWritten() {
        transactions.failRollback = new TransactionSystemException("rollback failed " + SENTINEL);
        script(new BookingResult.SlotUnavailable());

        assertThat(service.book(request())).isEqualTo(new BookingResult.SlotUnavailable());
    }

    @Test
    void aFailedRollbackAfterAThrownFailureIsAKnownRollbackBecauseCommitWasNeverIssued() {
        transactions.failRollback = new TransactionSystemException("rollback failed");
        script(new IllegalStateException(SENTINEL));

        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());
    }

    // ---- injected commit-phase faults: classification by the completion report ------

    /** All faults below are INJECTED exceptions with a scripted completion status; see the real tests. */
    private static RuntimeException withSqlState(String sqlState) {
        return new TransactionSystemException(
                "Could not commit", new RuntimeException("wrapper", new SQLException("synthetic", sqlState)));
    }

    private void commitFails(RuntimeException failure, int completion) {
        transactions.commitFailures.add(new CommitFault(failure, completion));
    }

    static Stream<String> conflictStates() {
        return Stream.of("40001", "40P01");
    }

    @ParameterizedTest
    @MethodSource("conflictStates")
    void aCommitCallFailureTheServerReportedAsAConflictIsAProvenRollbackAndIsRetried(String sqlState) {
        commitFails(withSqlState(sqlState), TransactionSynchronization.STATUS_UNKNOWN);
        script(new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.Created(BOOKED));

        verify(procedure, times(2)).execute(any(), any(), any());
        assertThat(transactions.events).containsExactly("begin", "commit-failed", "begin", "commit");
    }

    @ParameterizedTest
    @MethodSource("conflictStates")
    void aSpringRolledBackAttemptWhosePreCommitCallbackRaisedAConflictIsRetried(String sqlState) {
        commitFails(withSqlState(sqlState), TransactionSynchronization.STATUS_ROLLED_BACK);
        script(new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.Created(BOOKED));

        verify(procedure, times(2)).execute(any(), any(), any());
    }

    @Test
    void anInjectedCommitConflictOnEveryAttemptExhaustsAsAKnownRollback() {
        for (int attempt = 0; attempt < 3; attempt++) {
            commitFails(withSqlState("40001"), TransactionSynchronization.STATUS_UNKNOWN);
        }
        script(new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());
    }

    @Test
    void aServerReportedRollbackOfAnAbortedTransactionAtTheCommitCallIsAKnownRollbackAndIsNotRetried() {
        commitFails(withSqlState("25P02"), TransactionSynchronization.STATUS_UNKNOWN);
        script(new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());

        verify(procedure, times(1)).execute(any(), any(), any());
    }

    @Test
    void aTransactionSpringRolledBackInsteadOfCommittingIsAKnownRollbackWhateverTheException() {
        commitFails(new UnexpectedRollbackException("rolled back instead of committed"),
                TransactionSynchronization.STATUS_ROLLED_BACK);
        script(new BookingResult.Created(BOOKED));
        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());

        commitFails(new IllegalStateException("pre-commit callback failed " + SENTINEL),
                TransactionSynchronization.STATUS_ROLLED_BACK);
        script(new BookingResult.Created(BOOKED));
        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());
    }

    static Stream<RuntimeException> misleadingAfterCommitFailures() {
        return Stream.of(
                withSqlState("40001"),
                withSqlState("40P01"),
                withSqlState("25P02"),
                new UnexpectedRollbackException("a callback claims a rollback"),
                new IllegalStateException("plain callback failure"));
    }

    @ParameterizedTest
    @MethodSource("misleadingAfterCommitFailures")
    void anyFailureAfterTheCommitCompletedIsUncertainWhateverItContainsAndIsNeverRetried(RuntimeException failure) {
        commitFails(failure, TransactionSynchronization.STATUS_COMMITTED);
        script(new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.OutcomeUncertain());

        verify(procedure, times(1)).execute(any(), any(), any());
        assertThat(transactions.events).containsExactly("begin", "commit-failed");
    }

    static Stream<RuntimeException> uncertainCommitCallFaults() {
        return Stream.of(
                withSqlState("08006"),
                withSqlState("08001"),
                withSqlState("57014"),
                withSqlState("57P01"),
                withSqlState("58030"),
                withSqlState("XX000"),
                withSqlState("23P01"),
                new TransactionSystemException("Could not commit", new java.io.IOException("connection reset")),
                new TransactionSystemException("Could not commit", new java.net.SocketTimeoutException("timeout")),
                new TransactionSystemException("Could not commit"),
                new IllegalStateException("anything unknown " + SENTINEL),
                new org.springframework.transaction.HeuristicCompletionException(
                        org.springframework.transaction.HeuristicCompletionException.STATE_UNKNOWN,
                        new RuntimeException("heuristic")));
    }

    @ParameterizedTest
    @MethodSource("uncertainCommitCallFaults")
    void anyOtherFailureOfTheCommitCallIsUncertainAndIsNeverRetried(RuntimeException fault) {
        commitFails(fault, TransactionSynchronization.STATUS_UNKNOWN);
        script(new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.OutcomeUncertain());

        // Same attempt identity: the server does not retry or create anything new itself.
        verify(procedure, times(1)).execute(any(), any(), any());
        assertThat(transactions.events).containsExactly("begin", "commit-failed");
    }

    @Test
    void aFailureWithoutAnyCompletionReportIsUncertainEvenWithAConflictSqlState() {
        transactions.commitFailures.add(new CommitFault(withSqlState("40001"), CommitFault.NO_REPORT));
        script(new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.OutcomeUncertain());

        verify(procedure, times(1)).execute(any(), any(), any());
    }

    // ---- verification read after a normal completion ------------------------------

    @Test
    void aNormalCompletionIsConfirmedByAReadOfTheAppointmentOutsideAnyTransaction() {
        script(new BookingResult.Created(BOOKED));

        assertThat(service.book(request())).isEqualTo(new BookingResult.Created(BOOKED));

        verify(appointments, times(1)).find(eq(BUSINESS), eq(APPOINTMENT));
        assertThat(transactions.events).containsExactly("begin", "commit");
    }

    @Test
    void aNormalCompletionWithoutAPersistedAppointmentIsNeverCreatedAndIsNotRetried() {
        script(new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED));
        presentInDatabase(false);

        assertThat(service.book(request())).isEqualTo(new BookingResult.TemporarilyUnavailable());

        verify(procedure, times(1)).execute(any(), any(), any());
    }

    @Test
    void aVerificationReadThatFailsLeavesTheOutcomeUncertain() {
        script(new BookingResult.Created(BOOKED), new BookingResult.Created(BOOKED));
        org.mockito.Mockito.when(appointments.find(any(), any())).thenThrow(new IllegalStateException(SENTINEL));

        assertThat(service.book(request())).isEqualTo(new BookingResult.OutcomeUncertain());

        verify(procedure, times(1)).execute(any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("nonCreatedResults")
    void nothingIsVerifiedWhenNothingWasWritten(BookingResult outcome) {
        script(outcome);

        service.book(request());

        verifyNoInteractions(appointments);
    }

    // ---- diagnostics privacy ----------------------------------------------------

    @Test
    void diagnosticsNeverContainAFailureMessageOrAnyRequestValue() {
        script(new IllegalStateException(SENTINEL));
        service.book(request());
        commitFails(new IllegalStateException("commit " + SENTINEL), TransactionSynchronization.STATUS_UNKNOWN);
        script(new BookingResult.Created(BOOKED));
        service.book(request());
        script(new CustomerConcurrentConflict(), new CustomerConcurrentConflict(), new CustomerConcurrentConflict());
        service.book(request());

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            lines.add(event.getFormattedMessage());
            assertThat(event.getThrowableProxy()).isNull();
        }
        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines))
                .doesNotContain(SENTINEL)
                .doesNotContain(ATTEMPT)
                .doesNotContain("Ана")
                .doesNotContain("0888123456");
        assertThat(lines).allMatch(line -> line.startsWith("booking event="));
    }

    // ---- fake transaction manager -----------------------------------------------

    /** An injected commit failure and the completion status Spring would report for it. */
    private record CommitFault(RuntimeException failure, int completion) {
        static final int NO_REPORT = -99;
    }

    /**
     * Records the lifecycle, can inject failures, and emulates what Spring's transaction manager does
     * around the callbacks: it opens a synchronization scope, reports the completion status to the
     * registered synchronizations, and always closes the scope. It also emulates the rollback-only rule.
     */
    private static final class FakeTransactionManager implements PlatformTransactionManager {
        private final List<String> events = new ArrayList<>();
        private final List<TransactionDefinition> definitions = new ArrayList<>();
        private final List<TransactionStatus> statuses = new ArrayList<>();
        private final Deque<CommitFault> commitFailures = new ArrayDeque<>();
        private RuntimeException failBegin;
        private RuntimeException failRollback;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (failBegin != null) {
                throw failBegin;
            }
            events.add("begin");
            definitions.add(definition);
            TransactionSynchronizationManager.initSynchronization();
            TransactionStatus status = new SimpleTransactionStatus(true);
            statuses.add(status);
            return status;
        }

        @Override
        public void commit(TransactionStatus status) {
            if (status.isRollbackOnly()) {
                rollback(status);
                return;
            }
            CommitFault fault = commitFailures.pollFirst();
            if (fault == null) {
                complete(TransactionSynchronization.STATUS_COMMITTED);
                events.add("commit");
                return;
            }
            events.add("commit-failed");
            if (fault.completion() != CommitFault.NO_REPORT) {
                complete(fault.completion());
            } else {
                TransactionSynchronizationManager.clear();
            }
            throw fault.failure();
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback");
            complete(TransactionSynchronization.STATUS_ROLLED_BACK);
            if (failRollback != null) {
                throw failRollback;
            }
        }

        private static void complete(int completion) {
            TransactionSynchronizationUtils.triggerAfterCompletion(completion);
            TransactionSynchronizationManager.clear();
        }
    }

    /** Builds the sealed, package-private-constructor persistence failures through the store's classifier. */
    private static final class AppointmentPersistenceExceptions {
        static RuntimeException overlap() {
            return translate("23P01", "appointment_staff_no_overlap");
        }

        static RuntimeException duplicateAttempt() {
            return translate("23505", "appointment_business_attempt_hash_unique");
        }

        static RuntimeException duplicatePublicReference() {
            return translate("23505", "appointment_business_public_reference_unique");
        }

        static RuntimeException concurrentFailure() {
            return translate("40001", null);
        }

        static RuntimeException invalidData() {
            return translate("23514", "appointment_status_valid");
        }

        static RuntimeException unknownReference() {
            return translate("23503", "appointment_customer_fk");
        }

        static RuntimeException unexpected(String sqlState) {
            return translate(sqlState, null);
        }

        private static RuntimeException translate(String sqlState, String constraint) {
            return bg.spotyourslot.booking.infrastructure.AppointmentStoreFailures.classify(sqlState, constraint);
        }
    }
}
