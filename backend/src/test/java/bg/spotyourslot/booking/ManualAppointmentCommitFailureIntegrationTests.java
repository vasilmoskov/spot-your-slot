package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Commit-phase outcomes of the owner-created Appointment with REAL PostgreSQL and the real Spring
 * transaction manager. Every fault is produced inside a real attempt by a transaction
 * synchronization or a misbehaving collaborator; nothing is a mocked manager. The classification
 * rules with <b>injected</b> completion statuses are in
 * {@code BusinessAppointmentAdministrationServiceTests}.
 *
 * <p>What is real: the database really commits or really does not, and Spring really reports the
 * completion status that the orchestration classifies by. What is synthetic: the exceptions that the
 * test callbacks throw (their content is chosen to be as misleading as possible).
 */
class ManualAppointmentCommitFailureIntegrationTests extends ManualAppointmentTest {
    private static void register(TransactionSynchronization synchronization) {
        TransactionSynchronizationManager.registerSynchronization(synchronization);
    }

    static Stream<Supplier<RuntimeException>> misleadingAfterCommitFailures() {
        return Stream.of(
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "40001"))),
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "40P01"))),
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "25P02"))),
                () -> new UnexpectedRollbackException("a callback claims a rollback"),
                () -> new IllegalStateException("plain failure after the commit"));
    }

    // ---- failures after a real commit -----------------------------------------------------------

    @ParameterizedTest
    @MethodSource("misleadingAfterCommitFailures")
    void anAfterCommitFailureWhateverItContainsIsUncertainNeverRetriedAndTheVisitExists(
            Supplier<RuntimeException> failure) {
        // REAL: the Appointment is committed; then a callback throws.
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                throw failure.get();
            }
        }));

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(OutcomeUncertain.class);

        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(linesCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(threadOf("a") + ":commit");
    }

    // ---- a real rollback that Spring performs because a pre-commit callback failed -------------------

    private void assertFreshTransactionAndSnapshotPerAttempt(String label, int attempts) {
        List<BookingTestHooks.TransactionObservation> firstPoint = hooks.observationsOf(threadOf(label)).stream()
                .filter(observation -> observation.point() == BookingTestHooks.Point.BEFORE_STAFF_LOCK)
                .toList();
        assertThat(firstPoint).hasSize(attempts);
        assertThat(firstPoint.stream().map(BookingTestHooks.TransactionObservation::transactionId).distinct().count())
                .as("distinct PostgreSQL transaction identifiers").isEqualTo(attempts);
        assertThat(firstPoint.stream().map(BookingTestHooks.TransactionObservation::snapshot).distinct().count())
                .as("fresh snapshots").isEqualTo(attempts);
        for (int index = 1; index < attempts; index++) {
            assertThat(firstPoint.get(index).transactionId())
                    .isGreaterThan(firstPoint.get(index - 1).transactionId());
        }
    }

    @Test
    void aPreCommitCallbackFailureIsAProvenRollbackAndTheCompleteOperationIsRetriedInAFreshTransaction() {
        // REAL: a plain runtime exception from a pre-commit callback makes Spring roll the transaction back
        // (nothing is committed) and report ROLLED_BACK. The failure carries a conflict-shaped cause only to
        // show it is never read: the completion status alone proves the rollback.
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            if (invocation.ordinal() == 1) {
                register(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        // The first attempt has inserted its visit and lines inside its own transaction.
                        throw new IllegalStateException("callback", new SQLException("x", "08006"));
                    }
                });
            }
        });

        CreatedAppointment created = completed(submitCreate("a", cmd()));

        assertThat(created.status()).isEqualTo("CONFIRMED");
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":commit");
        assertFreshTransactionAndSnapshotPerAttempt("a", 2);
        // The rolled-back attempt left nothing: exactly the second attempt's visit and its one line exist.
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(linesCount(tenant.business())).isEqualTo(1);
        assertThat(appointmentById(created.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void aProvenRollbackOnEveryOfTheThreeAttemptsIsTemporarilyUnavailableWithNoFourthAttemptAndNoData() {
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("pre-commit callback failed");
            }
        }));

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(TemporarilyUnavailable.class).hasNoCause();

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(3);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(
                threadOf("a") + ":rollback", threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertFreshTransactionAndSnapshotPerAttempt("a", 3);
        assertThat(totalAppointments()).isZero();
        assertThat(linesCount(tenant.business())).isZero();
        manualHooks.reset();
        assertThat(create(cmd()).status()).isEqualTo("CONFIRMED");
    }

    // ---- failure before commit ---------------------------------------------------------------------------

    @Test
    void aFailureInsideTheBodyIsAFixedInternalFailureWithNoWriteAndNoRetry() {
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            throw new IllegalStateException("SENTINEL-PRIVATE-TEXT-42");
        });

        Throwable failure = failureOf(submitCreate("a", cmd()));

        assertThat(failure).isInstanceOf(Failure.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain("SENTINEL");
        assertThat(totalAppointments()).isZero();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @Test
    void aCollaboratorThatSwallowsAFailedStatementInTheBodyCannotProduceAFalseSuccess() {
        // REAL: the failed statement aborts the PostgreSQL transaction; the insert that follows fails with
        // an aborted-transaction error inside the body, which is not an approved retryable conflict.
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            try {
                jdbc.sql("SELECT 1 / 0").query().singleRow();
            } catch (RuntimeException swallowed) {
                // deliberately continue the aborted transaction
            }
        });

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(Failure.class);

        assertThat(totalAppointments()).isZero();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- silent rollback ----------------------------------------------------------------------------------

    @Test
    void aPreCommitCallbackThatSwallowsAFailedStatementIsDetectedByTheVerificationReadAndNeverReportedAsCreated() {
        // REAL: after the body (and its last read) the transaction is aborted by a callback. PostgreSQL answers
        // COMMIT with a silent ROLLBACK that the driver and JPA report as success, and Spring says COMMITTED.
        // Only the verification read after the completion reveals that nothing persisted.
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                try {
                    jdbc.sql("SELECT 1 / 0").query().singleRow();
                } catch (RuntimeException swallowed) {
                    // deliberately continue the aborted transaction
                }
            }
        }));

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(totalAppointments()).isZero();
        assertThat(linesCount(tenant.business())).isZero();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);

        // Nothing was stored, so the same request can simply be tried again.
        manualHooks.reset();
        assertThat(create(cmd()).status()).isEqualTo("CONFIRMED");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    // ---- uncertainty that is real ----------------------------------------------------------------------------

    @Test
    void realConnectionTerminationBeforeCommitIsUncertainEvenThoughNothingWasCommitted() {
        // REAL: the backend is terminated just before COMMIT. The orchestration cannot know whether the
        // server committed, so it reports uncertainty; the database in fact holds nothing.
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                jdbc.sql("SELECT pg_terminate_backend(pg_backend_pid())").query().singleRow();
            }
        }));

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(OutcomeUncertain.class);

        assertThat(totalAppointments()).isZero();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        manualHooks.reset();
        assertThat(create(cmd()).status()).isEqualTo("CONFIRMED");
    }
}
