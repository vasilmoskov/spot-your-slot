package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two real PostgreSQL transactions ordered only by latches and by lock-wait evidence taken from
 * PostgreSQL itself ({@code pg_stat_activity} and {@code pg_blocking_pids}); nothing sleeps. A first
 * transaction performs its write and holds it open on a latch; a second transaction starts, and the
 * test proves it is waiting on a PostgreSQL lock (or, where no conflict is possible, that it
 * finishes while the first is still open) before the first is committed or rolled back.
 */
final class AppointmentRace {
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ExecutorService executor;
    private final PlatformTransactionManager transactionManager;
    private final JdbcClient jdbc;
    private final int firstIsolation;
    private final int secondIsolation;
    private final CountDownLatch firstWrote = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final CountDownLatch secondStarted = new CountDownLatch(1);
    private final AtomicInteger firstBackendPid = new AtomicInteger();
    private final AtomicInteger secondBackendPid = new AtomicInteger();
    private volatile boolean commit = true;
    private CompletableFuture<Object> first;
    private CompletableFuture<Object> second;

    AppointmentRace(
            ExecutorService executor,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc) {
        this(executor, transactionManager, jdbc,
                TransactionTemplate.ISOLATION_DEFAULT, TransactionTemplate.ISOLATION_DEFAULT);
    }

    AppointmentRace(
            ExecutorService executor,
            PlatformTransactionManager transactionManager,
            JdbcClient jdbc,
            int firstIsolation,
            int secondIsolation) {
        this.executor = executor;
        this.transactionManager = transactionManager;
        this.jdbc = jdbc;
        this.firstIsolation = firstIsolation;
        this.secondIsolation = secondIsolation;
    }

    /** The marker thrown inside the first transaction to make it roll back. */
    static final class RollbackRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RollbackRequested() {
            super("rollback requested", null, false, false);
        }
    }

    void holdFirst(Supplier<Object> operation) {
        first = CompletableFuture.supplyAsync(() -> attempt(() -> template(firstIsolation).execute(
                status -> {
                    firstBackendPid.set(backendPid());
                    Object result = operation.get();
                    firstWrote.countDown();
                    await(releaseFirst, "the first transaction was not released");
                    if (!commit) {
                        throw new RollbackRequested();
                    }
                    return result;
                })), executor);
        await(firstWrote, "the first transaction did not perform its write");
    }

    void startSecond(Supplier<Object> operation) {
        second = CompletableFuture.supplyAsync(() -> attempt(() -> template(secondIsolation).execute(
                status -> {
                    secondBackendPid.set(backendPid());
                    secondStarted.countDown();
                    return operation.get();
                })), executor);
        await(secondStarted, "the second transaction did not start");
    }

    int firstBackendPid() {
        return firstBackendPid.get();
    }

    int secondBackendPid() {
        return secondBackendPid.get();
    }

    /**
     * Waits until PostgreSQL reports the second backend waiting on a lock held by exactly the first
     * transaction's backend.
     */
    void assertSecondWaitsOnTheFirst() {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            boolean waiting = jdbc.sql("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE pid = :second
                              AND wait_event_type = 'Lock'
                              AND :first = ANY(pg_blocking_pids(pid))
                            """)
                    .param("second", secondBackendPid.get())
                    .param("first", firstBackendPid.get())
                    .query(Long.class)
                    .single() > 0;
            if (waiting) {
                assertThat(second).isNotDone();
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("PostgreSQL lock wait of the second on the first was not observed");
    }

    Object secondOutcomeWhileFirstIsOpen() {
        assertThat(first).isNotDone();
        Object outcome = completed(second);
        assertThat(first).isNotDone();
        return outcome;
    }

    void commitFirst() {
        releaseFirst.countDown();
    }

    void rollBackFirst() {
        commit = false;
        releaseFirst.countDown();
    }

    Object firstOutcome() {
        return completed(first);
    }

    Object secondOutcome() {
        return completed(second);
    }

    private TransactionTemplate template(int isolation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(isolation);
        return template;
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private static Object attempt(Supplier<Object> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static Object completed(CompletableFuture<Object> future) {
        try {
            return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError("the operation did not complete", failure);
        }
    }

    private static void await(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError(failure);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, interrupted);
        }
    }
}
