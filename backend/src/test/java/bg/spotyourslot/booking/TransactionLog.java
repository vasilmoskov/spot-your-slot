package bg.spotyourslot.booking;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionDefinition;

/**
 * A process-wide record of the transactions that booking threads began, committed, and rolled back,
 * written by {@link RecordingJpaTransactionManager}. It shows which transactions a booking started
 * itself and with what definition, and whether any outer transaction was ever suspended.
 */
public final class TransactionLog {
    /** One transaction that was actually begun (never a joined one). */
    public record Begin(String thread, int propagation, int isolation, boolean readOnly, int serial) {
    }

    private static final List<Begin> BEGINS = new CopyOnWriteArrayList<>();
    private static final List<String> ENDS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SUSPENSIONS = new AtomicInteger();

    private TransactionLog() {
    }

    static void begin(TransactionDefinition definition) {
        String thread = Thread.currentThread().getName();
        if (thread.startsWith(BookingTestHooks.BOOKING_THREAD_PREFIX)) {
            BEGINS.add(new Begin(
                    thread,
                    definition.getPropagationBehavior(),
                    definition.getIsolationLevel(),
                    definition.isReadOnly(),
                    BEGINS.size()));
        }
    }

    static void end(String kind) {
        String thread = Thread.currentThread().getName();
        if (thread.startsWith(BookingTestHooks.BOOKING_THREAD_PREFIX)) {
            ENDS.add(thread + ":" + kind);
        }
    }

    static void suspension() {
        if (Thread.currentThread().getName().startsWith(BookingTestHooks.BOOKING_THREAD_PREFIX)) {
            SUSPENSIONS.incrementAndGet();
        }
    }

    public static void clear() {
        BEGINS.clear();
        ENDS.clear();
        SUSPENSIONS.set(0);
    }

    /** The transactions a booking thread began, in order. */
    public static List<Begin> begins(String thread) {
        return BEGINS.stream().filter(begin -> begin.thread().equals(thread)).toList();
    }

    /** How the transactions of one thread ended, in order: {@code thread:commit} or {@code thread:rollback}. */
    public static List<String> endsOf(String thread) {
        return ENDS.stream().filter(end -> end.startsWith(thread + ":")).toList();
    }

    /** Outer transactions suspended on any booking thread; always zero without REQUIRES_NEW. */
    public static int suspensions() {
        return SUSPENSIONS.get();
    }
}
