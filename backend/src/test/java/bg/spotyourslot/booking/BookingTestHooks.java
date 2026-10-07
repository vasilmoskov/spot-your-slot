package bg.spotyourslot.booking;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deterministic interception points inside the booking attempt, used only by tests. A hook runs on
 * the booking thread, inside the attempt's transaction, immediately before the named collaborator
 * is called (or after, for {@link Point#AFTER_CUSTOMER}); a test coordinates two transactions by
 * blocking in a hook on a latch. Hooks fire only on threads whose name starts with
 * {@value #BOOKING_THREAD_PREFIX}, so ordinary test code that calls a collaborator is never
 * intercepted. Nothing here sleeps.
 */
public final class BookingTestHooks {
    public static final String BOOKING_THREAD_PREFIX = "booking-";

    /** The ordered steps of one attempt that a test can pause at. */
    public enum Point {
        /** After the Business lock (the snapshot exists) and before the StaffMember lock. */
        BEFORE_STAFF_LOCK,
        /** After the StaffMember lock, immediately before the schedule revision guard. */
        BEFORE_GUARD,
        /** After the guard, before the Service lock. */
        BEFORE_SERVICE_LOCK,
        /** After every lock, before the availability calculation. */
        BEFORE_AVAILABILITY,
        /** After availability and assignment, before the Customer step. */
        BEFORE_CUSTOMER,
        /** After the Customer step, before the Appointment insert. */
        AFTER_CUSTOMER
    }

    /** One firing of a point: the booking thread and how many times this thread reached the point. */
    public record Invocation(Point point, String thread, int ordinal) {
    }

    @FunctionalInterface
    public interface Hook {
        void run(Invocation invocation) throws Exception;
    }

    /** What a firing observed about its own PostgreSQL transaction. */
    public record TransactionObservation(
            String thread, Point point, int ordinal, long transactionId, String snapshot, int backendPid) {
    }

    private final Map<Point, Hook> hooks = new EnumMap<>(Point.class);
    private final Map<String, AtomicInteger> ordinals = new ConcurrentHashMap<>();
    private final List<TransactionObservation> observations = new CopyOnWriteArrayList<>();
    private volatile JdbcClient observer;
    private volatile boolean scheduleGuardBypassed;

    /** Installs a hook, replacing any earlier hook at the same point. */
    public synchronized void on(Point point, Hook hook) {
        hooks.put(point, hook);
    }

    /** Records the PostgreSQL transaction identifier and snapshot at every firing from now on. */
    public void observeTransactions(JdbcClient jdbc) {
        this.observer = jdbc;
    }

    public List<TransactionObservation> observations() {
        return List.copyOf(observations);
    }

    public List<TransactionObservation> observationsOf(String thread) {
        List<TransactionObservation> mine = new ArrayList<>();
        for (TransactionObservation observation : observations) {
            if (observation.thread().equals(thread)) {
                mine.add(observation);
            }
        }
        return mine;
    }

    /**
     * CONTROL RUN ONLY: makes the wrapper around the schedule revision guard skip the real guard, so a
     * test can show that its interleaving really is the stale-availability race that the guard
     * exists to stop. Production wiring has no such switch.
     */
    public void bypassScheduleGuard(boolean bypass) {
        this.scheduleGuardBypassed = bypass;
    }

    boolean scheduleGuardBypassed() {
        return scheduleGuardBypassed;
    }

    public synchronized void reset() {
        scheduleGuardBypassed = false;
        hooks.clear();
        ordinals.clear();
        observations.clear();
        observer = null;
    }

    /** Called by the test wrappers around the collaborators. */
    void fire(Point point) {
        String thread = Thread.currentThread().getName();
        if (!thread.startsWith(BOOKING_THREAD_PREFIX)) {
            return;
        }
        int ordinal = ordinals.computeIfAbsent(thread + "/" + point, key -> new AtomicInteger())
                .incrementAndGet();
        Invocation invocation = new Invocation(point, thread, ordinal);
        JdbcClient jdbc = observer;
        if (jdbc != null) {
            observations.add(new TransactionObservation(
                    thread,
                    point,
                    ordinal,
                    jdbc.sql("SELECT txid_current()").query(Long.class).single(),
                    jdbc.sql("SELECT pg_current_snapshot()::text").query(String.class).single(),
                    jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single()));
        }
        Hook hook;
        synchronized (this) {
            hook = hooks.get(point);
        }
        if (hook != null) {
            try {
                hook.run(invocation);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("test hook failed", failure);
            }
        }
    }
}
