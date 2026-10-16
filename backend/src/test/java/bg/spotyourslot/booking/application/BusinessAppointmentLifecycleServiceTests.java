package bg.spotyourslot.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import bg.spotyourslot.booking.BookingOrchestrationFailure;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejection;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.CancelWritten;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Changed;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.NoteWritten;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Outcome;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Rejected;
import bg.spotyourslot.booking.application.BusinessAppointmentLifecycleProcedure.Unchanged;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentCancelledBy;
import bg.spotyourslot.booking.domain.AppointmentServiceLine;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.AppointmentStatus;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.booking.infrastructure.AppointmentStoreFailures;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffBookingAccess;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
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
 * Unit tests of the Business cancellation and internal-note orchestration with a scripted attempt
 * body and a fake transaction manager. Every commit-phase fault here is an <b>injected</b> exception
 * or completion status: it proves the classification table of ADR-0029 as amended by the Phase 6
 * note. The real PostgreSQL behavior behind it is observed in
 * {@code AppointmentLifecycleCommitFailureIntegrationTests}.
 */
class BusinessAppointmentLifecycleServiceTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BUSINESS = UUID.randomUUID();
    private static final UUID APPOINTMENT = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-10-01T08:00:00Z");
    private static final Instant START = Instant.parse("2026-10-08T07:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private static final String NOTE = "СЕНТИНЕЛ-БЕЛЕЖКА-1";
    private static final String OLD_NOTE = "СЕНТИНЕЛ-СТАРА-БЕЛЕЖКА-2";
    private static final String SENTINEL = "SENTINEL-PRIVATE-TEXT-42";

    private final BusinessAppointmentLifecycleProcedure procedure =
            mock(BusinessAppointmentLifecycleProcedure.class);
    private final AppointmentStore appointments = mock(AppointmentStore.class);
    private final FakeTransactionManager transactions = new FakeTransactionManager();
    private BusinessAppointmentLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new BusinessAppointmentLifecycleService(
                procedure, new BookingDiagnostics(), appointments, transactions);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    /** The two operations, run through the same orchestration. */
    enum Kind {
        CANCEL,
        EDIT;

        UpdatedAppointment run(BusinessAppointmentLifecycleService service, AuthenticatedBusinessContext context) {
            return this == CANCEL
                    ? service.cancel(context, new CancelAppointmentCommand(APPOINTMENT, 0L))
                    : service.editInternalNote(context, new EditInternalNoteCommand(APPOINTMENT, 0L, NOTE));
        }

        Outcome written() {
            return this == CANCEL
                    ? new Changed(new CancelWritten(APPOINTMENT, 0L, NOW))
                    : new Changed(new NoteWritten(APPOINTMENT, 0L, NOTE, NOW));
        }

        /** The row a successful write leaves. */
        Appointment applied() {
            return this == CANCEL
                    ? row(1L, AppointmentStatus.CANCELLED, AppointmentCancelledBy.BUSINESS, NOW, null, NOW)
                    : row(1L, AppointmentStatus.CONFIRMED, null, null, NOTE, NOW);
        }

        /** The row of an untouched Appointment (a silent rollback). */
        Appointment untouched() {
            return row(0L, AppointmentStatus.CONFIRMED, null, null, this == CANCEL ? null : OLD_NOTE, CREATED);
        }
    }

    private static Appointment row(
            long version,
            AppointmentStatus status,
            AppointmentCancelledBy by,
            Instant cancelledAt,
            String note,
            Instant updatedAt) {
        return new Appointment(
                APPOINTMENT, BUSINESS, UUID.randomUUID(), SERVICE, STAFF, AppointmentSource.MANUAL, status,
                START, START.plusSeconds(1800), START.plusSeconds(1800), "Europe/Sofia", 30,
                new BigDecimal("25.00"), "Подстригване", "Мария", null, "ABCDEFGHJK", null, version, CREATED,
                updatedAt, cancelledAt, by, note,
                List.of(new AppointmentServiceLine(1, SERVICE, "Подстригване", 30, new BigDecimal("25.00"))));
    }

    private static AuthenticatedBusinessContext owner() {
        return new Context(USER, Optional.of(BUSINESS));
    }

    /** Scripts the body of both operations with one step: an outcome or a failure. */
    private void script(Object step) {
        doAnswer(invocation -> {
            if (step instanceof RuntimeException failure) {
                throw failure;
            }
            return step;
        }).when(procedure).cancel(any(), any(), any());
        doAnswer(invocation -> {
            if (step instanceof RuntimeException failure) {
                throw failure;
            }
            return step;
        }).when(procedure).editInternalNote(any(), any(), any());
    }

    private void stored(Appointment row) {
        when(appointments.find(any(), any())).thenReturn(Optional.of(row));
    }

    // ---- entry guard and identity ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anActiveCallerTransactionIsRejectedBeforeAnyWorkEvenForAnUnusableContext(Kind kind) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> kind.run(service, null))
                .isInstanceOf(BookingOrchestrationFailure.class)
                .hasNoCause()
                .hasNoSuppressedExceptions();

        verifyNoInteractions(procedure, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anActiveSynchronizationWithoutATransactionIsRejectedToo(Kind kind) {
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(BookingOrchestrationFailure.class);

        verifyNoInteractions(procedure, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anUnauthenticatedContextIsDeniedAndAMissingSelectionIsRequiredWithoutATransaction(Kind kind) {
        assertThatThrownBy(() -> kind.run(service, null)).isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> kind.run(service, new Context(null, Optional.of(BUSINESS))))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> kind.run(service, new Context(USER, Optional.empty())))
                .isInstanceOf(SelectedBusinessRequired.class);

        verifyNoInteractions(procedure, appointments);
        assertThat(transactions.events).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void theCallIsOneRequiredReadCommittedReadWriteTransaction(Kind kind) {
        script(kind.written());
        stored(kind.applied());

        UpdatedAppointment result = kind.run(service, owner());

        assertThat(result.version()).isEqualTo(1L);
        assertThat(transactions.definitions).hasSize(1);
        TransactionDefinition definition = transactions.definitions.get(0);
        assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(definition.getIsolationLevel()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(definition.isReadOnly()).isFalse();
        assertThat(transactions.events).containsExactly("begin", "commit");
    }

    // ---- deterministic rejections ------------------------------------------------------------------------

    static Stream<Object[]> rejections() {
        return Stream.of(
                new Object[] {Rejection.ACCESS_DENIED, AccessDenied.class},
                new Object[] {Rejection.BUSINESS_NOT_ACTIVE, BusinessNotActive.class},
                new Object[] {Rejection.BUSINESS_SUSPENDED, BusinessSuspended.class},
                new Object[] {Rejection.INVALID_INPUT, InvalidInput.class},
                new Object[] {Rejection.APPOINTMENT_NOT_FOUND, AppointmentNotFound.class},
                new Object[] {Rejection.CONCURRENT_UPDATE, ConcurrentUpdate.class},
                new Object[] {Rejection.STATE_CONFLICT, StateConflict.class});
    }

    @ParameterizedTest
    @MethodSource("rejections")
    void everyRejectionIsOneRolledBackAttemptWithItsTypedFailureAndIsNeverRetried(
            Rejection rejection, Class<? extends AppointmentAdministrationException> expected) {
        for (Kind kind : Kind.values()) {
            transactions.events.clear();
            script(new Rejected(rejection));

            assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(expected).hasNoCause();

            assertThat(transactions.events).containsExactly("begin", "rollback");
        }
        verify(appointments, times(0)).find(any(), any());
    }

    @ParameterizedTest
    @EnumSource(Rejection.class)
    void everyRejectionKindHasATypedFailureAndTheUnreachableOnesAreTheGenericFailure(Rejection rejection) {
        script(new Rejected(rejection));

        assertThatThrownBy(() -> Kind.CANCEL.run(service, owner()))
                .isInstanceOf(AppointmentAdministrationException.class);
    }

    @Test
    void aRollbackFailureAfterARejectionStillReportsTheRejection() {
        script(new Rejected(Rejection.STATE_CONFLICT));
        transactions.failRollback = new IllegalStateException(SENTINEL);

        assertThatThrownBy(() -> Kind.CANCEL.run(service, owner())).isInstanceOf(StateConflict.class);
    }

    // ---- the body threw: published conflicts versus anything else ------------------------------------------------

    static Stream<Supplier<RuntimeException>> publishedConflicts() {
        return Stream.of(
                BusinessScheduleContextAccess.ConcurrentConflict::new,
                SelectedBusinessOwnerAccess.ConcurrentConflict::new,
                () -> AppointmentStoreFailures.classify("40001", null),
                () -> AppointmentStoreFailures.classify("40P01", null));
    }

    @ParameterizedTest
    @MethodSource("publishedConflicts")
    void aPublishedLockOrVersionConflictIsTheTypedConcurrentUpdateAndIsNeverRetried(
            Supplier<RuntimeException> conflict) {
        for (Kind kind : Kind.values()) {
            transactions.definitions.clear();
            script(conflict.get());

            assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(ConcurrentUpdate.class).hasNoCause();

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    static Stream<Supplier<RuntimeException>> otherFailures() {
        return Stream.of(
                () -> new IllegalStateException(SENTINEL),
                StaffBookingAccess.ConcurrentConflict::new,
                ScheduleRevisionConcurrentConflict::new,
                () -> AppointmentStoreFailures.classify("23P01", "appointment_staff_no_overlap"),
                () -> AppointmentStoreFailures.classify("08006", null),
                () -> new TransactionSystemException(SENTINEL, new RuntimeException(new SQLException("x", "40001"))));
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void anyOtherFailureBeforeTheBodyReturnsIsOneAttemptAndAFixedInternalFailure(
            Supplier<RuntimeException> failure) {
        for (Kind kind : Kind.values()) {
            transactions.definitions.clear();
            script(failure.get());

            assertThatThrownBy(() -> kind.run(service, owner()))
                    .isInstanceOf(Failure.class)
                    .hasNoCause()
                    .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(SENTINEL));

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    // ---- the completion table (INJECTED statuses) -------------------------------------------------------------------

    static Stream<Supplier<RuntimeException>> misleadingFailures() {
        return Stream.of(
                () -> new TransactionSystemException(SENTINEL, new RuntimeException(new SQLException("x", "40001"))),
                () -> new TransactionSystemException(SENTINEL, new RuntimeException(new SQLException("x", "40P01"))),
                () -> new TransactionSystemException(SENTINEL, new RuntimeException(new SQLException("x", "25P02"))),
                () -> AppointmentStoreFailures.classify("40001", null),
                () -> new IllegalStateException(SENTINEL));
    }

    private void commitFails(Supplier<RuntimeException> failure, int completion) {
        transactions.commitFailures.add(new CommitFault(failure.get(), completion));
    }

    @ParameterizedTest
    @MethodSource("misleadingFailures")
    void committedFollowedByAFailureIsUncertainWhateverTheFailureContains(Supplier<RuntimeException> failure) {
        for (Kind kind : Kind.values()) {
            transactions.definitions.clear();
            script(kind.written());
            commitFails(failure, TransactionSynchronization.STATUS_COMMITTED);

            assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(OutcomeUncertain.class).hasNoCause();

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    @ParameterizedTest
    @MethodSource("misleadingFailures")
    void rolledBackIsTemporarilyUnavailableWhateverTheFailureContainsAndIsNeverRetried(
            Supplier<RuntimeException> failure) {
        for (Kind kind : Kind.values()) {
            transactions.definitions.clear();
            transactions.events.clear();
            script(kind.written());
            commitFails(failure, TransactionSynchronization.STATUS_ROLLED_BACK);

            assertThatThrownBy(() -> kind.run(service, owner()))
                    .isInstanceOf(TemporarilyUnavailable.class)
                    .hasNoCause();

            assertThat(transactions.definitions).as("no second attempt after a proven rollback").hasSize(1);
            assertThat(transactions.events).containsExactly("begin", "commit-failed");
        }
    }

    @ParameterizedTest
    @MethodSource("misleadingFailures")
    void unknownAndNoCompletionReportAreUncertainAndNeverRetried(Supplier<RuntimeException> failure) {
        for (int completion : new int[] {TransactionSynchronization.STATUS_UNKNOWN, CommitFault.NO_REPORT}) {
            for (Kind kind : Kind.values()) {
                transactions.definitions.clear();
                script(kind.written());
                commitFails(failure, completion);

                assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(OutcomeUncertain.class);

                assertThat(transactions.definitions).hasSize(1);
            }
        }
    }

    @Test
    void aFailureAfterAnUnchangedNoteWroteNothingAndIsTheFixedInternalFailureWhateverTheStatus() {
        for (int completion : new int[] {
            TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK,
            TransactionSynchronization.STATUS_UNKNOWN, CommitFault.NO_REPORT}) {
            transactions.definitions.clear();
            script(new Unchanged(new NoteWritten(APPOINTMENT, 0L, NOTE, CREATED)));
            commitFails(() -> new IllegalStateException(SENTINEL), completion);

            assertThatThrownBy(() -> Kind.EDIT.run(service, owner())).isInstanceOf(Failure.class);

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    // ---- the verification read (after a normal completion) ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void theWrittenVersionWithTheRequestedFactsIsAConfirmedSuccessAfterExactlyOneRead(Kind kind) {
        script(kind.written());
        stored(kind.applied());

        assertThat(kind.run(service, owner()).id()).isEqualTo(APPOINTMENT);

        verify(appointments, times(1)).find(BUSINESS, APPOINTMENT);
        assertThat(transactions.definitions).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aRowStillAtTheExpectedVersionProvesASilentRollbackAndIsNotRetried(Kind kind) {
        script(kind.written());
        stored(kind.untouched());

        assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(transactions.definitions).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void everyOtherRowStateOrNoRowOrAnUnreadableRowIsUncertain(Kind kind) {
        List<Runnable> states = List.of(
                // changed again after the commit
                () -> stored(row(2L, AppointmentStatus.CANCELLED, AppointmentCancelledBy.BUSINESS, NOW, NOTE, NOW)),
                // the written version but not the written facts
                () -> stored(row(1L, AppointmentStatus.CONFIRMED, null, null, "ДРУГА", NOW.plusSeconds(1))),
                () -> stored(row(1L, AppointmentStatus.CANCELLED, AppointmentCancelledBy.BUSINESS,
                        NOW.plusSeconds(1), null, NOW.plusSeconds(1))),
                () -> stored(row(1L, AppointmentStatus.CANCELLED, AppointmentCancelledBy.UNKNOWN, null, null, NOW)),
                () -> when(appointments.find(any(), any())).thenReturn(Optional.empty()),
                () -> when(appointments.find(any(), any())).thenThrow(new IllegalStateException(SENTINEL)));
        for (Runnable state : states) {
            transactions.definitions.clear();
            script(kind.written());
            state.run();

            assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(OutcomeUncertain.class).hasNoCause();

            assertThat(transactions.definitions).hasSize(1);
        }
    }

    @Test
    void anUnchangedNoteIsSuccessOnlyWhenTheRowIsStillAtTheSameVersionWithTheSameNote() {
        Outcome unchanged = new Unchanged(new NoteWritten(APPOINTMENT, 0L, NOTE, CREATED));
        script(unchanged);
        stored(row(0L, AppointmentStatus.CONFIRMED, null, null, NOTE, CREATED));

        UpdatedAppointment result = Kind.EDIT.run(service, owner());

        assertThat(result.version()).isEqualTo(0L);
        assertThat(result.internalNote()).isEqualTo(NOTE);
        assertThat(transactions.events).containsExactly("begin", "commit");

        for (Appointment changed : List.of(
                row(1L, AppointmentStatus.CONFIRMED, null, null, NOTE, NOW),
                row(0L, AppointmentStatus.CONFIRMED, null, null, "ДРУГА", CREATED))) {
            stored(changed);
            // Never the proven-rollback result: nothing was written, so it can only be unconfirmed.
            assertThatThrownBy(() -> Kind.EDIT.run(service, owner())).isInstanceOf(OutcomeUncertain.class);
        }
        when(appointments.find(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> Kind.EDIT.run(service, owner())).isInstanceOf(OutcomeUncertain.class);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aReportedSuccessIsAlwaysBackedByTheVerificationRead(Kind kind) {
        script(kind.written());
        when(appointments.find(any(), any())).thenThrow(new IllegalStateException(SENTINEL));

        assertThatThrownBy(() -> kind.run(service, owner())).isInstanceOf(OutcomeUncertain.class);

        verify(appointments, times(1)).find(any(), any());
    }

    // ---- privacy ------------------------------------------------------------------------------------------------------------

    @Test
    void commandsAndResultsRedactTheirStringForms() {
        EditInternalNoteCommand command = new EditInternalNoteCommand(APPOINTMENT, 0L, NOTE);
        assertThat(command.toString()).doesNotContain(NOTE).doesNotContain(APPOINTMENT.toString());
        assertThat(new CancelAppointmentCommand(APPOINTMENT, 0L).toString()).doesNotContain(APPOINTMENT.toString());
        script(Kind.EDIT.written());
        stored(Kind.EDIT.applied());
        UpdatedAppointment result = Kind.EDIT.run(service, owner());
        assertThat(result.toString()).doesNotContain(NOTE).doesNotContain("Мария");
        assertThat(new NoteWritten(APPOINTMENT, 0L, NOTE, NOW).toString()).doesNotContain(NOTE);
    }

    private record Context(UUID userId, Optional<UUID> selectedBusinessId) implements AuthenticatedBusinessContext {
    }
}
