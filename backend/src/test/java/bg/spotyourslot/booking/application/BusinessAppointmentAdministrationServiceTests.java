package bg.spotyourslot.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.booking.AppointmentAdministrationException;
import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.CustomerNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.ReviewChanged;
import bg.spotyourslot.booking.AppointmentAdministrationException.ServiceUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.ReviewedService;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Created;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejected;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejection;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.booking.infrastructure.AppointmentStoreFailures;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessRequired;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Unit tests of the Business transaction owner, retry classifier, and commit classifier with a
 * scripted attempt body and a fake transaction manager. Every commit-phase fault here is an
 * <b>injected</b> exception or status: it proves the classification rules. The real PostgreSQL
 * behavior behind them is observed in {@code ManualAppointmentCommitFailureIntegrationTests}.
 */
class BusinessAppointmentAdministrationServiceTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BUSINESS = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final String SENTINEL = "SENTINEL-PRIVATE-TEXT-42";

    private final BusinessAppointmentCreationProcedure procedure = mock(BusinessAppointmentCreationProcedure.class);
    private final BusinessAppointmentRescheduleProcedure rescheduleProcedure =
            mock(BusinessAppointmentRescheduleProcedure.class);
    private final AppointmentStore appointments = mock(AppointmentStore.class);
    private final FakeTransactionManager transactions = new FakeTransactionManager();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger diagnosticsLogger = (Logger) LoggerFactory.getLogger(BookingDiagnostics.class);
    private BusinessAppointmentAdministrationService service;

    @BeforeEach
    void setUp() {
        logs.start();
        diagnosticsLogger.addAppender(logs);
        diagnosticsLogger.setLevel(Level.WARN);
        service = new BusinessAppointmentAdministrationService(
                procedure, rescheduleProcedure, new BookingDiagnostics(), appointments, transactions);
    }

    @AfterEach
    void tearDown() {
        diagnosticsLogger.detachAppender(logs);
        TransactionSynchronizationManager.clear();
    }

    private static AuthenticatedBusinessContext owner() {
        return new Context(USER, Optional.of(BUSINESS));
    }

    private static CreateAppointmentCommand command() {
        return new CreateAppointmentCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-10-08T07:00:00Z"),
                List.of(new ReviewedService(UUID.randomUUID(), SENTINEL, 30, 2500)),
                SENTINEL);
    }

    private static Created created() {
        return new Created(new CreatedAppointment(
                APPOINTMENT, "ABCDEFGHJK", "CONFIRMED", "MANUAL",
                Instant.parse("2026-10-08T07:00:00Z"), Instant.parse("2026-10-08T07:30:00Z"),
                "Europe/Sofia", UUID.randomUUID(), "Мария", UUID.randomUUID(),
                List.of(), 30, new BigDecimal("25.00"), null, 0, Instant.parse("2026-10-01T08:00:00Z")));
    }

    /** Scripts the attempt body: each call consumes one step, an outcome or a failure. */
    private void script(Object... steps) {
        Deque<Object> remaining = new ArrayDeque<>(List.of(steps));
        doAnswer(invocation -> {
            Object step = remaining.removeFirst();
            if (step instanceof RuntimeException failure) {
                throw failure;
            }
            if (step instanceof Created) {
                invocation.<BusinessAppointmentCreationProcedure.CreatedIds>getArgument(3)
                        .record(BUSINESS, APPOINTMENT);
            }
            return step;
        }).when(procedure).execute(any(), any(), any(), any());
        presentInDatabase(true);
    }

    private void presentInDatabase(boolean present) {
        when(appointments.find(any(), any())).thenReturn(
                present ? Optional.of(mock(Appointment.class)) : Optional.empty());
    }

    // ---- entry guard ------------------------------------------------------------

    @Test
    void anActiveCallerTransactionIsRejectedBeforeAnyWorkEvenForAnUnusableContext() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.create(null, command()))
                .isInstanceOf(BookingOrchestrationFailure.class)
                .hasMessage("Booking orchestration requires no active caller transaction")
                .hasNoCause()
                .hasNoSuppressedExceptions();

        verifyNoInteractions(procedure, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void anActiveSynchronizationWithoutATransactionIsRejectedToo() {
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.create(owner(), command()))
                .isInstanceOf(BookingOrchestrationFailure.class);

        verifyNoInteractions(procedure, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void anUnauthenticatedContextIsDeniedAndAMissingSelectionIsRequiredWithoutATransaction() {
        assertThatThrownBy(() -> service.create(null, command())).isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.create(new Context(null, Optional.of(BUSINESS)), command()))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.create(new Context(USER, Optional.empty()), command()))
                .isInstanceOf(SelectedBusinessRequired.class);

        verifyNoInteractions(procedure, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void everyAttemptIsARequiredRepeatableReadReadWriteTransaction() {
        script(created());

        service.create(owner(), command());

        assertThat(transactions.definitions).hasSize(1);
        TransactionDefinition definition = transactions.definitions.get(0);
        assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(definition.getIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(definition.isReadOnly()).isFalse();
        assertThat(transactions.events).containsExactly("begin", "commit");
    }

    // ---- deterministic rejections ----------------------------------------------------

    static Stream<Object[]> rejections() {
        return Stream.of(
                new Object[] {Rejection.ACCESS_DENIED, AccessDenied.class},
                new Object[] {Rejection.BUSINESS_NOT_ACTIVE, BusinessNotActive.class},
                new Object[] {Rejection.BUSINESS_SUSPENDED, BusinessSuspended.class},
                new Object[] {Rejection.INVALID_INPUT, InvalidInput.class},
                new Object[] {Rejection.CUSTOMER_NOT_FOUND, CustomerNotFound.class},
                new Object[] {Rejection.SERVICE_UNAVAILABLE, ServiceUnavailable.class},
                new Object[] {Rejection.STAFF_UNAVAILABLE, StaffUnavailable.class},
                new Object[] {Rejection.REVIEW_CHANGED, ReviewChanged.class},
                new Object[] {Rejection.SLOT_UNAVAILABLE, SlotUnavailable.class},
                new Object[] {Rejection.APPOINTMENT_NOT_FOUND, AppointmentNotFound.class},
                new Object[] {Rejection.CONCURRENT_UPDATE, ConcurrentUpdate.class},
                new Object[] {Rejection.STATE_CONFLICT, StateConflict.class});
    }

    @ParameterizedTest
    @MethodSource("rejections")
    void everyRejectionIsOneRolledBackAttemptWithItsTypedFailureAndIsNeverRetried(
            Rejection rejection, Class<? extends AppointmentAdministrationException> expected) {
        script(new Rejected(rejection));

        assertThatThrownBy(() -> service.create(owner(), command()))
                .isInstanceOf(expected)
                .hasNoCause();

        assertThat(transactions.events).containsExactly("begin", "rollback");
        assertThat(Rejection.values()).hasSize(12);
    }

    // ---- retry ---------------------------------------------------------------------

    static Stream<Supplier<RuntimeException>> retryableConflicts() {
        return Stream.of(
                BusinessScheduleContextAccess.ConcurrentConflict::new,
                SelectedBusinessOwnerAccess.ConcurrentConflict::new,
                StaffBookingAccess.ConcurrentConflict::new,
                ScheduleRevisionConcurrentConflict::new,
                ServiceBookingAccess.ConcurrentConflict::new,
                () -> AppointmentStoreFailures.classify("40001", null),
                () -> AppointmentStoreFailures.classify("40P01", null),
                () -> AppointmentStoreFailures.classify("23505", "appointment_business_public_reference_unique"));
    }

    @ParameterizedTest
    @MethodSource("retryableConflicts")
    void eachSanitizedConflictIsRetriedInACompletelyNewTransaction(Supplier<RuntimeException> conflict) {
        script(conflict.get(), created());

        CreatedAppointment result = service.create(owner(), command());

        assertThat(result.id()).isEqualTo(APPOINTMENT);
        assertThat(transactions.events).containsExactly("begin", "rollback", "begin", "commit");
    }

    @ParameterizedTest
    @MethodSource("retryableConflicts")
    void aConflictOnEveryAttemptEndsTemporarilyUnavailableAfterExactlyThreeAttempts(
            Supplier<RuntimeException> conflict) {
        if (conflict.get() instanceof AppointmentPersistenceException.OverlapConflict) {
            return;
        }
        script(conflict.get(), conflict.get(), conflict.get());

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(transactions.definitions).hasSize(3);
        assertThat(transactions.events).containsExactly(
                "begin", "rollback", "begin", "rollback", "begin", "rollback");
    }

    @Test
    void anOverlapOnEveryAttemptEndsSlotUnavailableAfterExactlyThreeAttemptsAndNeverAFourth() {
        script(
                AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"));

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(SlotUnavailable.class);

        assertThat(transactions.definitions).hasSize(3);
    }

    @Test
    void aLastAttemptThatIsAConflictAfterAnEarlierOverlapIsTemporarilyUnavailable() {
        script(
                AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                new StaffBookingAccess.ConcurrentConflict(),
                new StaffBookingAccess.ConcurrentConflict());

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(TemporarilyUnavailable.class);
    }

    static Stream<Supplier<RuntimeException>> neverRetried() {
        return Stream.of(
                IllegalStateException::new,
                ScheduleRevisionFailure::new,
                StaffBookingAccess.Failure::new,
                ServiceBookingAccess.Failure::new,
                BusinessScheduleContextAccess.Failure::new,
                SelectedBusinessOwnerAccess.Failure::new,
                () -> AppointmentStoreFailures.classify("23503", "appointment_customer_fk"),
                () -> AppointmentStoreFailures.classify("23514", "appointment_status_valid"),
                () -> AppointmentStoreFailures.classify("23505", "appointment_business_attempt_hash_unique"),
                // A raw persistence-shaped failure is never inspected: no SQLState reading here.
                () -> new RuntimeException("raw", new SQLException("x", "40001")),
                () -> new RuntimeException("raw", new SQLException("x", "40P01")));
    }

    @ParameterizedTest
    @MethodSource("neverRetried")
    void anyOtherFailureBeforeTheBodyReturnsIsOneAttemptAndAFixedInternalFailure(
            Supplier<RuntimeException> failure) {
        script(failure.get());

        assertThatThrownBy(() -> service.create(owner(), command()))
                .isInstanceOf(Failure.class)
                .hasNoCause();

        assertThat(transactions.definitions).hasSize(1);
        assertThat(transactions.events).containsExactly("begin", "rollback");
    }

    @Test
    void aRollbackThatFailsAfterARejectionStillReportsTheRejection() {
        script(new Rejected(Rejection.SLOT_UNAVAILABLE));
        transactions.failRollback = new TransactionSystemException("rollback failed");

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(SlotUnavailable.class);
    }

    // ---- verification read ---------------------------------------------------------------

    @Test
    void aNormalCompletionWhoseRowIsAbsentIsAKnownRollbackNotRetried() {
        script(created());
        presentInDatabase(false);

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(transactions.definitions).hasSize(1);
    }

    @Test
    void aVerificationReadThatFailsIsUncertain() {
        script(created());
        when(appointments.find(any(), any())).thenThrow(new IllegalStateException(SENTINEL));

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(OutcomeUncertain.class);

        assertThat(transactions.definitions).hasSize(1);
    }

    // ---- commit phase (injected statuses) ---------------------------------------------------

    @Test
    void injectedCommittedThenAFailureIsUncertainAndNeverRetriedWhateverTheFailureContains() {
        for (RuntimeException misleading : List.of(
                new TransactionSystemException("x", new RuntimeException(new SQLException("x", "40001"))),
                new TransactionSystemException("x", new RuntimeException(new SQLException("x", "40P01"))),
                new IllegalStateException("plain"))) {
            transactions.events.clear();
            transactions.definitions.clear();
            script(created());
            transactions.commitFailures.add(new CommitFault(misleading, TransactionSynchronization.STATUS_COMMITTED));

            assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(OutcomeUncertain.class);

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    static Stream<RuntimeException> rolledBackFailures() {
        // The completion status alone proves the rollback; the content of the failure is never read.
        return Stream.of(
                new TransactionSystemException("x", new RuntimeException(new SQLException("x", "40001"))),
                new TransactionSystemException("x", new RuntimeException(new SQLException("x", "40P01"))),
                new TransactionSystemException("x", new RuntimeException(new SQLException("x", "08006"))),
                new IllegalStateException("no cause at all"),
                new IllegalStateException(SENTINEL));
    }

    @ParameterizedTest
    @MethodSource("rolledBackFailures")
    void injectedRolledBackAtCommitIsAProvenRollbackAndTheFirstRetryInAFreshTransactionCanSucceed(
            RuntimeException failure) {
        script(created(), created());
        transactions.commitFailures.add(new CommitFault(failure, TransactionSynchronization.STATUS_ROLLED_BACK));

        CreatedAppointment result = service.create(owner(), command());

        assertThat(result.id()).isEqualTo(APPOINTMENT);
        assertThat(transactions.definitions).hasSize(2);
        assertThat(transactions.events).containsExactly("begin", "commit-failed", "begin", "commit");
    }

    @Test
    void threeInjectedRolledBackCompletionsAreTemporarilyUnavailableAndNeverAFourthAttempt() {
        script(created(), created(), created(), created());
        for (int attempt = 0; attempt < 4; attempt++) {
            transactions.commitFailures.add(new CommitFault(
                    new IllegalStateException(SENTINEL), TransactionSynchronization.STATUS_ROLLED_BACK));
        }

        assertThatThrownBy(() -> service.create(owner(), command()))
                .isInstanceOf(TemporarilyUnavailable.class)
                .hasNoCause();

        assertThat(transactions.definitions).hasSize(3);
        assertThat(transactions.events).containsExactly(
                "begin", "commit-failed", "begin", "commit-failed", "begin", "commit-failed");
        assertThat(transactions.commitFailures).hasSize(1);
    }

    @Test
    void aRolledBackCompletionAfterAnEarlierOverlapStillEndsTemporarilyUnavailable() {
        script(
                AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                created(),
                created());
        transactions.commitFailures.add(new CommitFault(
                new IllegalStateException("x"), TransactionSynchronization.STATUS_ROLLED_BACK));
        transactions.commitFailures.add(new CommitFault(
                new IllegalStateException("x"), TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(transactions.definitions).hasSize(3);
    }

    @Test
    void aRolledBackCompletionThenACommittedFailureIsUncertainAndStops() {
        script(created(), created());
        transactions.commitFailures.add(new CommitFault(
                new IllegalStateException("x"), TransactionSynchronization.STATUS_ROLLED_BACK));
        transactions.commitFailures.add(new CommitFault(
                new IllegalStateException("x"), TransactionSynchronization.STATUS_COMMITTED));

        assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(OutcomeUncertain.class);

        assertThat(transactions.definitions).hasSize(2);
    }

    @Test
    void injectedUnknownOrUnreportedCommitResultsAreUncertainWhateverTheirSqlState() {
        for (int completion : List.of(TransactionSynchronization.STATUS_UNKNOWN, CommitFault.NO_REPORT)) {
            for (String sqlState : List.of("40001", "40P01", "25P02", "08006")) {
                transactions.definitions.clear();
                script(created());
                transactions.commitFailures.add(new CommitFault(
                        new TransactionSystemException("x", new RuntimeException(new SQLException("x", sqlState))),
                        completion));

                assertThatThrownBy(() -> service.create(owner(), command())).isInstanceOf(OutcomeUncertain.class);

                assertThat(transactions.definitions).hasSize(1);
            }
        }
    }

    // ---- privacy -------------------------------------------------------------------------

    @Test
    void failuresCarryNoValueAndTheDiagnosticsLogOnlyFixedCodesAndClassNames() {
        script(new IllegalStateException(SENTINEL));

        assertThatThrownBy(() -> service.create(owner(), command()))
                .isInstanceOf(Failure.class)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(SENTINEL));

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(SENTINEL).doesNotContain(BUSINESS.toString());
        }
        assertThat(command().toString()).doesNotContain(SENTINEL);
        assertThat(new ReviewedService(UUID.randomUUID(), SENTINEL, 1, 1).toString()).doesNotContain(SENTINEL);
        assertThat(created().toString()).doesNotContain("Мария");
    }

    @org.junit.jupiter.api.Test
    void everyRejectionKindHasATypedFailure() {
        for (Rejection rejection : Rejection.values()) {
            script(new Rejected(rejection));
            assertThatThrownBy(() -> service.create(owner(), command()))
                    .isInstanceOf(AppointmentAdministrationException.class);
        }
    }

    // ---- fakes ---------------------------------------------------------------------------

    private record Context(UUID userId, Optional<UUID> selectedBusinessId) implements AuthenticatedBusinessContext {
    }
}
