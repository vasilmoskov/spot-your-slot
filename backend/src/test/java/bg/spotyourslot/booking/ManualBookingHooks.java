package bg.spotyourslot.booking;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic interception points of the owner-created Appointment attempt that the guest hooks
 * ({@link BookingTestHooks}) do not cover, used only by tests. A hook runs on the attempt's thread,
 * inside its transaction, immediately before the named collaborator is called; a test coordinates
 * two transactions by blocking in a hook on a latch. Hooks fire only on threads whose name starts
 * with {@value BookingTestHooks#BOOKING_THREAD_PREFIX}. Nothing here sleeps.
 *
 * <p>The guest hook points that the Business attempt shares ({@code BEFORE_STAFF_LOCK},
 * {@code BEFORE_GUARD}, {@code BEFORE_SERVICE_LOCK}, {@code BEFORE_AVAILABILITY}) are fired by the
 * same wrappers and registered on {@link BookingTestHooks}.
 */
public final class ManualBookingHooks {
    /** The extra steps of one attempt that a test can pause at. */
    public enum Point {
        /** After the Business lock (the snapshot exists), before the owner Membership lock. */
        BEFORE_OWNER_LOCK,
        /** After the owner Membership lock, before the plain Customer reference read. */
        BEFORE_CUSTOMER_READ,
        /** After every decision and the availability calculation, immediately before the insert. */
        BEFORE_INSERT
    }

    /** One firing of a point: the attempt's thread and how many times this thread reached the point. */
    public record Invocation(Point point, String thread, int ordinal) {
    }

    @FunctionalInterface
    public interface Hook {
        void run(Invocation invocation) throws Exception;
    }

    private final Map<Point, Hook> hooks = new EnumMap<>(Point.class);
    private final Map<String, AtomicInteger> ordinals = new ConcurrentHashMap<>();

    public synchronized void on(Point point, Hook hook) {
        hooks.put(point, hook);
    }

    public synchronized void reset() {
        hooks.clear();
        ordinals.clear();
    }

    void fire(Point point) {
        String thread = Thread.currentThread().getName();
        if (!thread.startsWith(BookingTestHooks.BOOKING_THREAD_PREFIX)) {
            return;
        }
        int ordinal = ordinals.computeIfAbsent(thread + "/" + point, key -> new AtomicInteger())
                .incrementAndGet();
        Hook hook;
        synchronized (this) {
            hook = hooks.get(point);
        }
        if (hook != null) {
            try {
                hook.run(new Invocation(point, thread, ordinal));
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("test hook failed", failure);
            }
        }
    }
}
