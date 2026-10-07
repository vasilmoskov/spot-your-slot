package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.booking.BookingResult.Replayed;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import java.sql.SQLException;
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
 * Commit-phase outcomes with REAL PostgreSQL and the real Spring transaction manager. Every fault here
 * is produced inside a real attempt by a transaction synchronization or a misbehaving collaborator;
 * nothing is a mocked manager. The classification rules with injected statuses are in
 * {@code GuestBookingServiceTests}.
 *
 * <p>What is real: the database really commits or really does not, and Spring really reports the
 * completion status that the orchestration classifies by. What is synthetic: the exceptions that the
 * test callbacks throw (their content is chosen to be as misleading as possible).
 */
class GuestBookingCommitFailureIntegrationTests extends BookingIntegrationTest {
    private static void register(TransactionSynchronization synchronization) {
        TransactionSynchronizationManager.registerSynchronization(synchronization);
    }

    // ---- failures after a real commit ------------------------------------------------------

    static Stream<Supplier<RuntimeException>> misleadingAfterCommitFailures() {
        return Stream.of(
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "40001"))),
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "40P01"))),
                () -> new TransactionSystemException("callback", new RuntimeException(new SQLException("x", "25P02"))),
                () -> new UnexpectedRollbackException("a callback claims a rollback"),
                () -> new IllegalStateException("plain failure after the commit"));
    }

    @ParameterizedTest
    @MethodSource("misleadingAfterCommitFailures")
    void anAfterCommitFailureWhateverItContainsIsUncertainNeverRetriedAndTheSameAttemptReplays(
            Supplier<RuntimeException> failure) {
        // REAL: the Appointment and the Customer are committed; then a callback throws.
        hooks.on(Point.AFTER_CUSTOMER, invocation -> register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                throw failure.get();
            }
        }));
        Req request = req();

        BookingResult uncertain = result(submit("a", request.build()));

        assertThat(uncertain).isEqualTo(new BookingResult.OutcomeUncertain());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(threadOf("a") + ":commit");

        hooks.reset();
        BookingResult repeated = result(submit("b", request.copy().build()));

        assertThat(repeated).isInstanceOf(Replayed.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    // ---- a real rollback that Spring performs because a pre-commit callback failed ------------

    @Test
    void aPreCommitCallbackThatFailsWithAConflictIsAProvenRollbackAndIsRetriedInANewTransaction() {
        // REAL: a plain runtime exception from a pre-commit callback makes Spring roll the transaction
        // back (nothing is committed) and report ROLLED_BACK. (A TransactionException subtype would be
        // treated by Spring as a failed commit and, by default, not rolled back: not used here.)
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.ordinal() == 1) {
                register(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new IllegalStateException("callback", new SQLException("x", "40001"));
                    }
                });
            }
        });

        BookingResult result = result(submit("a", req().build()));

        assertThat(result).isInstanceOf(Created.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":commit");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aPreCommitCallbackThatFailsOtherwiseIsAKnownRollbackAndIsNotRetried() {
        hooks.on(Point.AFTER_CUSTOMER, invocation -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("pre-commit callback failed");
            }
        }));

        assertThat(result(submit("a", req().build()))).isEqualTo(new BookingResult.TemporarilyUnavailable());

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    // ---- silent rollback ----------------------------------------------------------------------

    @Test
    void aCollaboratorThatSwallowsAFailedStatementInTheBodyCannotProduceAFalseSuccess() {
        // REAL: the failed statement aborts the PostgreSQL transaction; the Appointment insert that
        // follows fails with 25P02 inside the body, so the attempt is a proven rollback.
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            try {
                jdbc.sql("SELECT 1 / 0").query().singleRow();
            } catch (RuntimeException swallowed) {
                // deliberately continue the aborted transaction
            }
        });

        assertThat(result(submit("a", req().build()))).isEqualTo(new BookingResult.TemporarilyUnavailable());

        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @Test
    void aPreCommitCallbackThatSwallowsAFailedStatementIsNeverReportedAsCreated() {
        // REAL: after the body (and its last read) the transaction is aborted by a callback. PostgreSQL
        // answers COMMIT with a silent ROLLBACK, the driver and JPA report success, and Spring says
        // COMMITTED. Only the verification read after the completion reveals that nothing persisted.
        hooks.on(Point.AFTER_CUSTOMER, invocation -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                try {
                    jdbc.sql("SELECT 1 / 0").query().singleRow();
                } catch (RuntimeException swallowed) {
                    // deliberately continue the aborted transaction
                }
            }
        }));
        Req request = req();

        BookingResult result = result(submit("a", request.build()));

        assertThat(result).isNotInstanceOf(Created.class);
        assertThat(result).isEqualTo(new BookingResult.TemporarilyUnavailable());
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
        // No automatic retry: exactly one transaction was begun.
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);

        // The same attempt can be repeated safely and now creates the Appointment exactly once.
        hooks.reset();
        assertThat(result(submit("b", request.copy().build()))).isInstanceOf(Created.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    // ---- uncertainty that is real --------------------------------------------------------------

    @Test
    void realConnectionTerminationBeforeCommitIsUncertainEvenThoughNothingWasCommitted() {
        // REAL: the backend is terminated just before COMMIT. The orchestration cannot know whether
        // the server committed, so it reports uncertainty; the database in fact holds nothing.
        hooks.on(Point.AFTER_CUSTOMER, invocation -> register(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                jdbc.sql("SELECT pg_terminate_backend(pg_backend_pid())").query().singleRow();
            }
        }));
        Req request = req();

        BookingResult result = result(submit("a", request.build()));

        assertThat(result).isEqualTo(new BookingResult.OutcomeUncertain());
        assertThat(totalAppointments()).isZero();
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        // The identical attempt can be repeated safely and now creates the Appointment.
        hooks.reset();
        assertThat(result(submit("b", request.copy().build()))).isInstanceOf(Created.class);
    }
}
