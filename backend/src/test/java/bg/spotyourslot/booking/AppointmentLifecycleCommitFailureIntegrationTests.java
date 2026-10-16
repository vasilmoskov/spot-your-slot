package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.Failure;
import bg.spotyourslot.booking.AppointmentAdministrationException.OutcomeUncertain;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import bg.spotyourslot.booking.domain.AppointmentTransitions.NoteUpdate;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Commit-phase outcomes of the Business cancellation and the internal-note edit with REAL PostgreSQL
 * and the real Spring transaction manager. Every fault is produced inside a real attempt by a
 * transaction synchronization or a misbehaving collaborator; nothing is a mocked manager. The
 * classification rules with <b>injected</b> completion statuses are in
 * {@code BusinessAppointmentLifecycleServiceTests}.
 *
 * <p>What is real: the database really commits or really does not, Spring really reports the
 * completion status the orchestration classifies by, and the verification read really runs outside the
 * transaction. What is synthetic: the exceptions the callbacks throw (their content is chosen to be as
 * misleading as possible). Neither operation is ever retried, so every case asserts exactly one
 * PostgreSQL transaction.
 */
class AppointmentLifecycleCommitFailureIntegrationTests extends LifecycleAppointmentTest {
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-7781";

    @Autowired AppointmentStore store;

    /** The two operations, run through the same real orchestration. */
    enum Kind {
        CANCEL,
        NOTE
    }

    private CompletableFuture<UpdatedAppointment> submit(Kind kind, String label, CreatedAppointment created) {
        return kind == Kind.CANCEL
                ? submitCancel(label, created)
                : submitNote(label, created.id(), created.version(), NOTE);
    }

    private void assertApplied(Kind kind, CreatedAppointment created) {
        Map<String, Object> row = fullRow(created.id());
        assertThat(row.get("version")).isEqualTo(1L);
        if (kind == Kind.CANCEL) {
            assertThat(row.get("status")).isEqualTo("CANCELLED");
        } else {
            assertThat(row.get("internal_note")).isEqualTo(NOTE);
        }
    }

    private void assertNothingApplied(CreatedAppointment created, Map<String, Object> before) {
        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(fullRow(created.id()).get("status")).isEqualTo("CONFIRMED");
    }

    private static void register(TransactionSynchronization synchronization) {
        TransactionSynchronizationManager.registerSynchronization(synchronization);
    }

    /** Runs the action inside the attempt, after the clock read and immediately before the UPDATE. */
    private void atTheUpdate(Runnable action) {
        manualHooks.on(ManualBookingHooks.Point.AFTER_CLOCK_READ, invocation -> action.run());
    }

    static Stream<Supplier<RuntimeException>> misleadingAfterCommitFailures() {
        return Stream.of(
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "40001"))),
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "40P01"))),
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "25P02"))),
                () -> new UnexpectedRollbackException("a callback claims a rollback"),
                () -> new IllegalStateException("plain failure after the commit"));
    }

    // ---- failures after a real commit -----------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void anAfterCommitFailureWhateverItContainsIsUncertainNeverRetriedAndTheChangeIsCommitted(Kind kind) {
        List<String> times = List.of("09:00", "09:30", "10:00", "10:30", "11:00");
        AtomicInteger index = new AtomicInteger();
        misleadingAfterCommitFailures().forEach(failure -> {
            CreatedAppointment created = existing(times.get(index.getAndIncrement()));
            TransactionLog.clear();
            // REAL: the UPDATE is committed; then a callback throws.
            atTheUpdate(() -> register(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw failure.get();
                }
            }));

            assertThat(failureOf(submit(kind, "a", created))).isInstanceOf(OutcomeUncertain.class).hasNoCause();

            assertApplied(kind, created);
            assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
            assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(threadOf("a") + ":commit");
            manualHooks.reset();
        });
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aChangeThatLandsBetweenTheCommitAndTheVerificationReadIsUncertainNotSuccessAndNeverRetried(Kind kind) {
        CreatedAppointment created = existing("10:00");
        // REAL: after the commit and before the verification read, another connection edits the row.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                async(() -> store.updateInternalNote(new NoteUpdate(
                        tenant.business(), created.id(), 1L, "ДРУГА БЕЛЕЖКА", BookingHookConfiguration.NOW))).join();
            }
        }));

        assertThat(failureOf(submit(kind, "a", created))).isInstanceOf(OutcomeUncertain.class);

        assertThat(fullRow(created.id()).get("version")).isEqualTo(2L);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- a real rollback that Spring performs because a pre-commit callback failed ------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aProvenRollbackIsTemporarilyUnavailableNotRetriedLeavesTheRowAndKeepsBlockingTheTime(Kind kind) {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        // REAL: a plain runtime exception from a pre-commit callback makes Spring roll the transaction back
        // (the UPDATE is undone) and report ROLLED_BACK. The cause is conflict-shaped only to show it is
        // never read: the completion status alone proves the rollback, and nothing is retried.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("callback", new SQLException("x", "40001"));
            }
        }));

        assertThat(failureOf(submit(kind, "a", created))).isInstanceOf(TemporarilyUnavailable.class).hasNoCause();

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(threadOf("a") + ":rollback");
        assertNothingApplied(created, before);
        // The Appointment is still CONFIRMED, so it still blocks its time.
        assertThat(book(req().staff(tenant.staff()).start("10:00").build()))
                .isInstanceOf(BookingResult.SlotUnavailable.class);
        // Nothing was stored, so the same request can simply be made again.
        manualHooks.reset();
        assertThat(completed(submit(kind, "again", created)).version()).isEqualTo(1L);
    }

    // ---- failure before commit -----------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aFailureInsideTheBodyIsAFixedInternalFailureWithNoWriteAndNoRetry(Kind kind) {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        atTheUpdate(() -> {
            throw new IllegalStateException("SENTINEL-PRIVATE-TEXT-42");
        });

        Throwable failure = failureOf(submit(kind, "a", created));

        assertThat(failure).isInstanceOf(Failure.class).hasNoCause();
        assertThat(failure.getMessage()).doesNotContain("SENTINEL");
        assertNothingApplied(created, before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aCollaboratorThatSwallowsAFailedStatementInTheBodyCannotProduceAFalseSuccess(Kind kind) {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        // REAL: the failed statement aborts the PostgreSQL transaction; the UPDATE that follows fails with
        // an aborted-transaction error inside the body, which is not an approved concurrent conflict.
        atTheUpdate(() -> {
            try {
                jdbc.sql("SELECT 1 / 0").query().singleRow();
            } catch (RuntimeException swallowed) {
                // deliberately continue the aborted transaction
            }
        });

        assertThat(failureOf(submit(kind, "a", created))).isInstanceOf(Failure.class);

        assertNothingApplied(created, before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- silent rollback ------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void aPreCommitCallbackThatSwallowsAFailedStatementIsDetectedByTheVerificationReadAndNeverReportedAsDone(
            Kind kind) {
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

        assertThat(failureOf(submit(kind, "a", created))).isInstanceOf(TemporarilyUnavailable.class);

        assertNothingApplied(created, before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        manualHooks.reset();
        assertThat(completed(submit(kind, "b", created)).version()).isEqualTo(1L);
    }

    // ---- uncertainty that is real ------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    void realConnectionTerminationBeforeCommitIsUncertainEvenThoughNothingWasCommitted(Kind kind) {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        // REAL: the backend is terminated just before COMMIT. The orchestration cannot know whether the
        // server committed, so it reports uncertainty and never retries; the database holds nothing new.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                jdbc.sql("SELECT pg_terminate_backend(pg_backend_pid())").query().singleRow();
            }
        }));

        assertThat(failureOf(submit(kind, "a", created))).isInstanceOf(OutcomeUncertain.class);

        assertNothingApplied(created, before);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        manualHooks.reset();
        assertThat(completed(submit(kind, "b", created)).version()).isEqualTo(1L);
    }

    // ---- the unchanged-note no-op versus a write that silently rolled back ---------------------------------------------------

    @Test
    void aVerifiedNoOpSucceedsAtTheSameVersionWhileAWriteThatSilentlyRolledBackDoesNot() {
        CreatedAppointment created = create(cmd().note(NOTE));
        Map<String, Object> before = fullRow(created.id());

        // A normal no-op: success, same version, no UPDATE (the tuple version is unchanged).
        UpdatedAppointment noOp = completed(submitNote("n", created.id(), 0L, NOTE));
        assertThat(noOp.version()).isEqualTo(0L);
        assertThat(fullRow(created.id())).isEqualTo(before);

        // A write whose transaction silently rolled back: the same final row, but not a success.
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
        assertThat(failureOf(submitNote("w", created.id(), 0L, "ДРУГА"))).isInstanceOf(TemporarilyUnavailable.class);
        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void aNoOpWhoseRowIsChangedBeforeTheVerificationReadIsUncertainAndAFailureOfANoOpWroteNothing() {
        CreatedAppointment created = create(cmd().note(NOTE));
        // REAL: another connection edits the note after the no-op's transaction completed and before its
        // verification read; the no-op cannot be confirmed as current.
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                async(() -> store.updateInternalNote(new NoteUpdate(
                        tenant.business(), created.id(), 0L, "ДРУГА БЕЛЕЖКА", BookingHookConfiguration.NOW))).join();
            }
        }));
        assertThat(failureOf(submitNote("a", created.id(), 0L, NOTE))).isInstanceOf(OutcomeUncertain.class);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(1L);

        // A failure after a no-op wrote nothing: a fixed internal failure, not an uncertain outcome.
        manualHooks.reset();
        CreatedAppointment second = create(cmd().start("11:00").note(NOTE));
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("pre-commit callback failed");
            }
        }));
        assertThat(failureOf(submitNote("b", second.id(), 0L, NOTE))).isInstanceOf(Failure.class);
        assertThat(fullRow(second.id()).get("version")).isEqualTo(0L);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(1);
    }

    @Test
    void aCommittedCancellationFollowedByAFailureIsNeverReportedAsAKnownRollbackAndNeverRepeated() {
        CreatedAppointment created = existing("10:00");
        atTheUpdate(() -> register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                throw new IllegalStateException("callback after the commit");
            }
        }));

        Throwable failure = failureOf(submitCancel("a", created));

        assertThat(failure).isNotInstanceOf(TemporarilyUnavailable.class).isInstanceOf(OutcomeUncertain.class);
        assertThat(fullRow(created.id()).get("status")).isEqualTo("CANCELLED");
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        // The time is really free now: the owner who reloads sees it.
        manualHooks.reset();
        assertThat(book(req().staff(tenant.staff()).start("10:00").build())).isInstanceOf(BookingResult.Created.class);
    }
}
