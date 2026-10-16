package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.domain.AppointmentTransitions.NoteUpdate;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Commit-phase outcomes of the owner reschedule with REAL PostgreSQL and the real Spring transaction
 * manager. Every fault is produced inside a real attempt by a transaction synchronization or a
 * misbehaving collaborator; nothing is a mocked manager. The classification rules with
 * <b>injected</b> completion statuses are in {@code BusinessAppointmentRescheduleServiceTests}.
 *
 * <p>What is real: the database really commits or really does not, Spring really reports the
 * completion status that the orchestration classifies by, and the verification read really runs
 * outside the transaction. What is synthetic: the exceptions that the test callbacks throw (their
 * content is chosen to be as misleading as possible).
 */
class AppointmentRescheduleCommitFailureIntegrationTests extends RescheduleAppointmentTest {
    @Autowired AppointmentStore store;

    private static void register(TransactionSynchronization synchronization) {
        TransactionSynchronizationManager.registerSynchronization(synchronization);
    }

    /** Runs the action inside the attempt, after availability and immediately before the update. */
    private void atTheUpdate(Runnable action) {
        manualHooks.on(ManualBookingHooks.Point.AFTER_BUSINESS_AVAILABILITY, invocation -> action.run());
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
    void anAfterCommitFailureWhateverItContainsIsUncertainNeverRetriedAndTheChangeIsCommitted(
            Supplier<RuntimeException> failure) {
        CreatedAppointment created = existing("10:00");
        // REAL: the update is committed; then a callback throws.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                throw failure.get();
            }
        }));

        assertThat(failureOf(submitMove("a", move(created).start("11:00")))).isInstanceOf(OutcomeUncertain.class);

        assertThat(fullRow(created.id()).get("version")).isEqualTo(1L);
        assertThat(instantOf(fullRow(created.id()), "start_at")).isEqualTo(at("11:00"));
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(threadOf("a") + ":commit");
    }

    @Test
    void aChangeThatLandsBetweenTheCommitAndTheVerificationReadIsUncertainNotSuccessAndNeverRetried() {
        CreatedAppointment created = existing("10:00");
        // REAL: after the commit and before the verification read, another connection edits the row.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                async(() -> store.updateInternalNote(new NoteUpdate(
                        tenant.business(), created.id(), 1L, "Бележка", BookingHookConfiguration.NOW))).join();
            }
        }));

        assertThat(failureOf(submitMove("a", move(created).start("11:00")))).isInstanceOf(OutcomeUncertain.class);

        assertThat(fullRow(created.id()).get("version")).isEqualTo(2L);
        assertThat(instantOf(fullRow(created.id()), "start_at")).isEqualTo(at("11:00"));
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
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
        CreatedAppointment created = existing("10:00");
        hooks.observeTransactions(jdbc);
        // REAL: a plain runtime exception from a pre-commit callback makes Spring roll the transaction
        // back (the update is undone) and report ROLLED_BACK. The failure carries a conflict-shaped cause
        // only to show it is never read: the completion status alone proves the rollback.
        AtomicInteger attempts = new AtomicInteger();
        atTheUpdate(() -> {
            if (attempts.incrementAndGet() == 1) {
                register(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("callback", new SQLException("x", "08006"));
                    }
                });
            }
        });

        RescheduledAppointment moved = completed(submitMove("a", move(created).start("11:00")));

        assertThat(moved.version()).isEqualTo(1L);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":commit");
        assertFreshTransactionAndSnapshotPerAttempt("a", 2);
        // The rolled-back attempt left nothing: the row advanced exactly one version.
        assertThat(fullRow(created.id()).get("version")).isEqualTo(1L);
        assertThat(instantOf(fullRow(created.id()), "start_at")).isEqualTo(at("11:00"));
    }

    @Test
    void aProvenRollbackOnEveryOfTheThreeAttemptsIsTemporarilyUnavailableWithNoFourthAttemptAndNoChange() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        hooks.observeTransactions(jdbc);
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("pre-commit callback failed");
            }
        }));

        assertThat(failureOf(submitMove("a", move(created).start("11:00"))))
                .isInstanceOf(TemporarilyUnavailable.class)
                .hasNoCause();

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(3);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(
                threadOf("a") + ":rollback", threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertFreshTransactionAndSnapshotPerAttempt("a", 3);
        assertThat(fullRow(created.id())).isEqualTo(before);
        manualHooks.reset();
        assertThat(completed(submitMove("again", move(created).start("11:00"))).version()).isEqualTo(1L);
    }

    // ---- failure before commit ---------------------------------------------------------------------------

    @Test
    void aFailureInsideTheBodyIsAFixedInternalFailureWithNoWriteAndNoRetry() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        atTheUpdate(() -> {
            throw new IllegalStateException("SENTINEL-PRIVATE-TEXT-42");
        });

        Throwable failure = failureOf(submitMove("a", move(created).start("11:00")));

        assertThat(failure).isInstanceOf(Failure.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain("SENTINEL");
        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @Test
    void aCollaboratorThatSwallowsAFailedStatementInTheBodyCannotProduceAFalseSuccess() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        // REAL: the failed statement aborts the PostgreSQL transaction; the update that follows fails with
        // an aborted-transaction error inside the body, which is not an approved retryable conflict.
        atTheUpdate(() -> {
            try {
                jdbc.sql("SELECT 1 / 0").query().singleRow();
            } catch (RuntimeException swallowed) {
                // deliberately continue the aborted transaction
            }
        });

        assertThat(failureOf(submitMove("a", move(created).start("11:00")))).isInstanceOf(Failure.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- silent rollback ----------------------------------------------------------------------------------

    @Test
    void aPreCommitCallbackThatSwallowsAFailedStatementIsDetectedByTheVerificationReadAndNeverReportedAsMoved() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        // REAL: after the body (and its last read) the transaction is aborted by a callback. PostgreSQL answers
        // COMMIT with a silent ROLLBACK that the driver and JPA report as success, and Spring says COMMITTED.
        // Only the verification read after the completion reveals that the row is still at the old version.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                try {
                    jdbc.sql("SELECT 1 / 0").query().singleRow();
                } catch (RuntimeException swallowed) {
                    // deliberately continue the aborted transaction
                }
            }
        }));

        assertThat(failureOf(submitMove("a", move(created).start("11:00")))).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);

        // Nothing was stored, so the same request can simply be tried again.
        manualHooks.reset();
        assertThat(completed(submitMove("b", move(created).start("11:00"))).version()).isEqualTo(1L);
    }

    // ---- uncertainty that is real ----------------------------------------------------------------------------

    @Test
    void realConnectionTerminationBeforeCommitIsUncertainEvenThoughNothingWasCommitted() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        // REAL: the backend is terminated just before COMMIT. The orchestration cannot know whether the
        // server committed, so it reports uncertainty; the database in fact holds nothing new.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                jdbc.sql("SELECT pg_terminate_backend(pg_backend_pid())").query().singleRow();
            }
        }));

        assertThat(failureOf(submitMove("a", move(created).start("11:00")))).isInstanceOf(OutcomeUncertain.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        manualHooks.reset();
        assertThat(completed(submitMove("b", move(created).start("11:00"))).version()).isEqualTo(1L);
    }
}
