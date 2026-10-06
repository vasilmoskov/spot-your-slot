package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deterministic coordination for PostgreSQL concurrency tests: bounded latch and future waits and
 * lock-wait evidence taken from PostgreSQL itself. Nothing here sleeps for a fixed time; the only
 * polling is a bounded spin on the observed database state.
 */
public final class ConcurrencyTestSupport {
    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    private ConcurrencyTestSupport() {
    }

    public static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }

    public static <T> T completed(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent operation was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("concurrent operation did not complete", exception);
        }
    }

    public static int backendPid(JdbcClient jdbc) {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    /**
     * Waits until PostgreSQL reports that {@code waiterPid} is blocked on a lock held by exactly
     * the transaction running in {@code holderPid}. The statement observed is the one that is
     * actually waiting, so this is evidence of a real lock conflict between those two backends.
     */
    public static void awaitBlockedBy(JdbcClient observer, int waiterPid, int holderPid) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            List<Integer> blockers = blockers(observer, waiterPid);
            if (blockers.contains(holderPid)) {
                String waitEvent = observer.sql("""
                                SELECT wait_event_type FROM pg_stat_activity WHERE pid = :pid
                                """)
                        .param("pid", waiterPid)
                        .query(String.class)
                        .optional()
                        .orElse(null);
                assertThat(waitEvent).isEqualTo("Lock");
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("PostgreSQL did not report backend " + waiterPid
                + " blocked by backend " + holderPid);
    }

    /**
     * Waits until exactly one backend other than the observer is blocked by {@code holderPid} and
     * returns it with the statement it is executing. Used when the blocked statement belongs to a
     * service that opens its own transaction, so its backend is not known to the test.
     */
    public static WaitingBackend awaitWaiterBlockedBy(JdbcClient observer, int holderPid) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            List<WaitingBackend> waiting = observer.sql("""
                            SELECT pid, wait_event_type, query
                            FROM pg_stat_activity
                            WHERE :holder = ANY(pg_blocking_pids(pid))
                            """)
                    .param("holder", holderPid)
                    .query((resultSet, rowNumber) -> new WaitingBackend(
                            resultSet.getInt("pid"),
                            resultSet.getString("wait_event_type"),
                            resultSet.getString("query")))
                    .list();
            if (waiting.size() == 1 && "Lock".equals(waiting.get(0).waitEventType())) {
                return waiting.get(0);
            }
            assertThat(waiting.size()).as("backends blocked by %d", holderPid).isLessThanOrEqualTo(1);
            Thread.onSpinWait();
        }
        throw new AssertionError("No PostgreSQL backend was observed blocked by backend " + holderPid);
    }

    public record WaitingBackend(int pid, String waitEventType, String query) {
    }

    /** Asserts, at this instant, that {@code backendPid} is not waiting for any lock. */
    public static void assertNotBlocked(JdbcClient observer, int backendPid) {
        assertThat(blockers(observer, backendPid)).isEmpty();
    }

    private static List<Integer> blockers(JdbcClient observer, int pid) {
        return observer.sql("SELECT unnest(pg_blocking_pids(:pid))")
                .param("pid", pid)
                .query(Integer.class)
                .list();
    }
}
