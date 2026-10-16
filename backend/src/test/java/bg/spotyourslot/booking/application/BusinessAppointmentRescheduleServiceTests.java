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
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejected;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejection;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rescheduled;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.booking.infrastructure.AppointmentStoreFailures;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffBookingAccess;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests of the shared Business runner for the reschedule and reassignment operation with a
 * scripted attempt body and a fake transaction manager. Every commit-phase fault here is an
 * <b>injected</b> exception or status: it proves the classification rules. The real PostgreSQL
 * behavior behind them is observed in {@code AppointmentRescheduleCommitFailureIntegrationTests}.
 */
class BusinessAppointmentRescheduleServiceTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BUSINESS = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-10-08T07:00:00Z");
    private static final String SENTINEL = "SENTINEL-PRIVATE-TEXT-42";

    private final BusinessAppointmentCreationProcedure creation = mock(BusinessAppointmentCreationProcedure.class);
    private final BusinessAppointmentRescheduleProcedure procedure =
            mock(BusinessAppointmentRescheduleProcedure.class);
    private final AppointmentStore appointments = mock(AppointmentStore.class);
    private final FakeTransactionManager transactions = new FakeTransactionManager();
    private BusinessAppointmentAdministrationService service;

    @BeforeEach
    void setUp() {
        service = new BusinessAppointmentAdministrationService(
                creation, procedure, new BookingDiagnostics(), appointments, transactions);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    private static AuthenticatedBusinessContext owner() {
        return new Context(USER, Optional.of(BUSINESS));
    }

    private static RescheduleAppointmentCommand command() {
        return new RescheduleAppointmentCommand(APPOINTMENT, 0L, START, STAFF);
    }

    private static Rescheduled rescheduled() {
        return new Rescheduled(new RescheduledAppointment(
                APPOINTMENT, "ABCDEFGHJK", "CONFIRMED", "MANUAL", START, START.plusSeconds(1800),
                "Europe/Sofia", STAFF, "Мария", UUID.randomUUID(), List.of(), 30, new BigDecimal("25.00"),
                null, 1L, Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-02T08:00:00Z")));
    }

    /** Scripts the attempt body: each call consumes one step, an outcome or a failure. */
    private void script(Object... steps) {
        Deque<Object> remaining = new ArrayDeque<>(List.of(steps));
        doAnswer(invocation -> {
            Object step = remaining.removeFirst();
            if (step instanceof RuntimeException failure) {
                throw failure;
            }
            return step;
        }).when(procedure).execute(any(), any(), any());
        stored(1L, START, STAFF);
    }

    /** The row the verification read finds outside the transaction. */
    private void stored(long version, Instant start, UUID staff) {
        Appointment row = mock(Appointment.class);
        when(row.version()).thenReturn(version);
        when(row.startAt()).thenReturn(start);
        when(row.staffMemberId()).thenReturn(staff);
        when(appointments.find(any(), any())).thenReturn(Optional.of(row));
    }

    // ---- entry guard and identity ----------------------------------------------------------

    @Test
    void anActiveCallerTransactionIsRejectedBeforeAnyWorkEvenForAnUnusableContext() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.reschedule(null, command()))
                .isInstanceOf(BookingOrchestrationFailure.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();

        verifyNoInteractions(procedure, creation, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void anActiveSynchronizationWithoutATransactionIsRejectedToo() {
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.reschedule(owner(), command()))
                .isInstanceOf(BookingOrchestrationFailure.class);

        verifyNoInteractions(procedure, creation, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void anUnauthenticatedContextIsDeniedAndAMissingSelectionIsRequiredWithoutATransaction() {
        assertThatThrownBy(() -> service.reschedule(null, command())).isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.reschedule(new Context(null, Optional.of(BUSINESS)), command()))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.reschedule(new Context(USER, Optional.empty()), command()))
                .isInstanceOf(SelectedBusinessRequired.class);

        verifyNoInteractions(procedure, creation, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @Test
    void everyAttemptIsARequiredRepeatableReadReadWriteTransactionAndTheCreationBodyIsNotUsed() {
        script(rescheduled());

        RescheduledAppointment result = service.reschedule(owner(), command());

        assertThat(result.version()).isEqualTo(1L);
        assertThat(transactions.definitions).hasSize(1);
        TransactionDefinition definition = transactions.definitions.get(0);
        assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(definition.getIsolationLevel()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(definition.isReadOnly()).isFalse();
        assertThat(transactions.events).containsExactly("begin", "commit");
        verifyNoInteractions(creation);
    }

    // ---- deterministic rejections ------------------------------------------------------------

    static Stream<Object[]> rejections() {
        return Stream.of(
                new Object[] {Rejection.ACCESS_DENIED, AccessDenied.class},
                new Object[] {Rejection.BUSINESS_NOT_ACTIVE, BusinessNotActive.class},
                new Object[] {Rejection.BUSINESS_SUSPENDED, BusinessSuspended.class},
                new Object[] {Rejection.INVALID_INPUT, InvalidInput.class},
                new Object[] {Rejection.STAFF_UNAVAILABLE, StaffUnavailable.class},
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

        assertThatThrownBy(() -> service.reschedule(owner(), command()))
                .isInstanceOf(expected)
                .hasNoCause();

        assertThat(transactions.events).containsExactly("begin", "rollback");
    }

    @ParameterizedTest
    @EnumSource(Rejection.class)
    void everyRejectionKindHasATypedFailureOnTheRescheduleRunnerToo(Rejection rejection) {
        script(new Rejected(rejection));

        assertThatThrownBy(() -> service.reschedule(owner(), command()))
                .isInstanceOf(AppointmentAdministrationException.class);
    }

    // ---- retry ---------------------------------------------------------------------------

    static Stream<Supplier<RuntimeException>> retryableConflicts() {
        return Stream.of(
                BusinessScheduleContextAccess.ConcurrentConflict::new,
                SelectedBusinessOwnerAccess.ConcurrentConflict::new,
                StaffBookingAccess.ConcurrentConflict::new,
                ScheduleRevisionConcurrentConflict::new,
                ServiceBookingAccess.ConcurrentConflict::new,
                () -> AppointmentStoreFailures.classify("40001", null),
                () -> AppointmentStoreFailures.classify("40P01", null),
                () -> AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"));
    }

    @ParameterizedTest
    @MethodSource("retryableConflicts")
    void eachSanitizedConflictIsRetriedInACompletelyNewTransaction(Supplier<RuntimeException> conflict) {
        script(conflict.get(), rescheduled());

        assertThat(service.reschedule(owner(), command()).version()).isEqualTo(1L);

        assertThat(transactions.definitions).hasSize(2);
        assertThat(transactions.events).containsExactly("begin", "rollback", "begin", "commit");
    }

    @Test
    void aLostRowRaceIsRetriedAndTheRetrysReReadYieldsTheDistinctStaleVersionResult() {
        script(AppointmentStoreFailures.classify("40001", null), new Rejected(Rejection.CONCURRENT_UPDATE));

        assertThatThrownBy(() -> service.reschedule(owner(), command())).isInstanceOf(ConcurrentUpdate.class);

        assertThat(transactions.definitions).hasSize(2);
    }

    @Test
    void aConflictOnEveryAttemptEndsTemporarilyUnavailableAfterExactlyThreeAttempts() {
        script(new StaffBookingAccess.ConcurrentConflict(),
                new StaffBookingAccess.ConcurrentConflict(),
                new StaffBookingAccess.ConcurrentConflict(),
                rescheduled());

        assertThatThrownBy(() -> service.reschedule(owner(), command())).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(transactions.definitions).hasSize(3);
    }

    @Test
    void anOverlapOnEveryAttemptEndsSlotUnavailableAfterExactlyThreeAttemptsAndNeverAFourth() {
        script(AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                rescheduled());

        assertThatThrownBy(() -> service.reschedule(owner(), command())).isInstanceOf(SlotUnavailable.class);

        assertThat(transactions.definitions).hasSize(3);
    }

    @Test
    void anyOtherFailureBeforeTheBodyReturnsIsOneAttemptAndAFixedInternalFailure() {
        script(new IllegalStateException(SENTINEL), rescheduled());

        assertThatThrownBy(() -> service.reschedule(owner(), command()))
                .isInstanceOf(Failure.class)
                .hasNoCause()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(SENTINEL));

        assertThat(transactions.definitions).hasSize(1);
    }

    // ---- verification read ---------------------------------------------------------------------

    @Test
    void theRowAtTheWrittenVersionWithTheRequestedStartAndStaffMemberIsAConfirmedSuccess() {
        script(rescheduled());
        stored(1L, START, STAFF);

        assertThat(service.reschedule(owner(), command()).id()).isEqualTo(APPOINTMENT);

        assertThat(transactions.definitions).hasSize(1);
    }

    @Test
    void aRowStillAtTheExpectedVersionProvesASilentRollbackAndIsNotRetried() {
        script(rescheduled());
        stored(0L, START.minusSeconds(900), UUID.randomUUID());

        assertThatThrownBy(() -> service.reschedule(owner(), command())).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(transactions.definitions).hasSize(1);
    }

    @Test
    void aRowAtANewerVersionADifferentStartOrStaffMemberOrNoRowAtAllIsUncertain() {
        List<Runnable> states = List.of(
                () -> stored(2L, START, STAFF),
                () -> stored(1L, START.plusSeconds(900), STAFF),
                () -> stored(1L, START, UUID.randomUUID()),
                () -> when(appointments.find(any(), any())).thenReturn(Optional.empty()));
        for (Runnable state : states) {
            transactions.definitions.clear();
            script(rescheduled());
            state.run();

            assertThatThrownBy(() -> service.reschedule(owner(), command())).isInstanceOf(OutcomeUncertain.class);

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    @Test
    void aVerificationReadThatFailsIsUncertain() {
        script(rescheduled());
        when(appointments.find(any(), any())).thenThrow(new IllegalStateException(SENTINEL));

        assertThatThrownBy(() -> service.reschedule(owner(), command()))
                .isInstanceOf(OutcomeUncertain.class)
                .hasNoCause();

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
            script(rescheduled(), rescheduled());
            transactions.commitFailures.add(new CommitFault(misleading, TransactionSynchronization.STATUS_COMMITTED));

            assertThatThrownBy(() -> service.reschedule(owner(), command())).isInstanceOf(OutcomeUncertain.class);

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    static Stream<RuntimeException> rolledBackFailures() {
        // The completion status alone proves the rollback; the content of the failure is never read.
        return Stream.of(
                new TransactionSystemException("x", new RuntimeException(new SQLException("x", "40001"))),
                new TransactionSystemException("x", new RuntimeException(new SQLException("x", "08006"))),
                new IllegalStateException("no cause at all"),
                new IllegalStateException(SENTINEL));
    }

    @ParameterizedTest
    @MethodSource("rolledBackFailures")
    void injectedRolledBackAtCommitIsAProvenRollbackAndTheFirstRetryInAFreshTransactionCanSucceed(
            RuntimeException failure) {
        script(rescheduled(), rescheduled());
        transactions.commitFailures.add(new CommitFault(failure, TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(service.reschedule(owner(), command()).version()).isEqualTo(1L);

        assertThat(transactions.definitions).hasSize(2);
        assertThat(transactions.events).containsExactly("begin", "commit-failed", "begin", "commit");
    }

    @Test
    void threeInjectedRolledBackCompletionsAreTemporarilyUnavailableAndNeverAFourthAttempt() {
        script(rescheduled(), rescheduled(), rescheduled(), rescheduled());
        for (int attempt = 0; attempt < 4; attempt++) {
            transactions.commitFailures.add(new CommitFault(
                    new IllegalStateException(SENTINEL), TransactionSynchronization.STATUS_ROLLED_BACK));
        }

        assertThatThrownBy(() -> service.reschedule(owner(), command()))
                .isInstanceOf(TemporarilyUnavailable.class)
                .hasNoCause();

        assertThat(transactions.definitions).hasSize(3);
        assertThat(transactions.events).containsExactly(
                "begin", "commit-failed", "begin", "commit-failed", "begin", "commit-failed");
        assertThat(transactions.commitFailures).hasSize(1);
    }

    @Test
    void aRolledBackCompletionThenACommittedFailureIsUncertainAndStops() {
        script(rescheduled(), rescheduled());
        transactions.commitFailures.add(new CommitFault(
                new IllegalStateException("x"), TransactionSynchronization.STATUS_ROLLED_BACK));
        transactions.commitFailures.add(new CommitFault(
                new IllegalStateException("x"), TransactionSynchronization.STATUS_COMMITTED));

        assertThatThrownBy(() -> service.reschedule(owner(), command())).isInstanceOf(OutcomeUncertain.class);

        assertThat(transactions.definitions).hasSize(2);
    }

    @Test
    void injectedUnknownOrUnreportedCommitResultsAreUncertainWhateverTheirSqlState() {
        for (int completion : List.of(TransactionSynchronization.STATUS_UNKNOWN, CommitFault.NO_REPORT)) {
            for (String sqlState : List.of("40001", "40P01", "25P02", "08006")) {
                transactions.definitions.clear();
                script(rescheduled(), rescheduled());
                transactions.commitFailures.add(new CommitFault(
                        new TransactionSystemException("x", new RuntimeException(new SQLException("x", sqlState))),
                        completion));

                assertThatThrownBy(() -> service.reschedule(owner(), command()))
                        .isInstanceOf(OutcomeUncertain.class);

                assertThat(transactions.definitions).hasSize(1);
            }
        }
    }

    // ---- privacy -------------------------------------------------------------------------------

    @Test
    void commandsAndResultsRedactTheirStringForms() {
        assertThat(command().toString()).doesNotContain(APPOINTMENT.toString()).doesNotContain(STAFF.toString());
        assertThat(rescheduled().toString()).doesNotContain("Мария");
        assertThat(rescheduled().appointment().toString()).doesNotContain("Мария");
    }

    private record Context(UUID userId, Optional<UUID> selectedBusinessId) implements AuthenticatedBusinessContext {
    }
}
