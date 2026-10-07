package bg.spotyourslot.booking.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A bounded set of fixed-window counters keyed by opaque 128-bit digests.
 *
 * <p><b>Window.</b> A counter's window starts at its first charge and lasts {@code window}; at
 * {@code start + window} (inclusive) the counter is gone and the next charge starts a fresh one.
 * Only <em>admitted</em> calls count; a rejected call changes nothing, so hammering a limited key
 * neither extends nor shortens its window.
 *
 * <p><b>Atomicity.</b> {@link #tryCharge} checks every charge of one decision and applies all of them
 * or none under one lock, so concurrent requests can never exceed a limit and a decision never
 * half-charges.
 *
 * <p><b>Bounded memory and expiry.</b> All counters share one window and are created in time order,
 * so a FIFO queue of counters is also ordered by expiry; expired counters are removed from its head
 * before every decision, in amortized constant time. The clock is clamped to never move backwards, so
 * a clock step can delay a removal but never expire an active counter early. At most
 * {@code maxEntries} counters exist. A decision that needs a new counter when none is free is
 * <em>rejected</em> (fail closed); an active counter is never evicted to make room, so saturation
 * cannot be used to reset a limit. The rejection tells the caller when the oldest counter expires.
 */
final class FixedWindowCounters {
    private final Clock clock;
    private final Duration window;
    private final int maxEntries;
    private final Map<Digest, Counter> counters = new HashMap<>();
    private final ArrayDeque<Counter> expiryOrder = new ArrayDeque<>();
    private Instant lastNow = Instant.MIN;

    FixedWindowCounters(Clock clock, Duration window, int maxEntries) {
        this.clock = clock;
        this.window = window;
        this.maxEntries = maxEntries;
    }

    /** One counter to charge, with the limit that applies to it. */
    record Charge(Digest key, int limit) {
    }

    /** The outcome of one decision. */
    record Decision(boolean allowed, long retryAfterSeconds) {
    }

    synchronized Decision tryCharge(List<Charge> charges) {
        Instant now = advance();
        expire(now);

        Set<Digest> distinct = new HashSet<>();
        int newCounters = 0;
        long retryMillis = 0;
        boolean limited = false;
        for (Charge charge : charges) {
            if (!distinct.add(charge.key())) {
                throw new IllegalArgumentException("A decision charges each counter once");
            }
            Counter counter = counters.get(charge.key());
            if (counter == null) {
                newCounters++;
            } else if (counter.count >= charge.limit()) {
                limited = true;
                retryMillis = Math.max(retryMillis, remainingMillis(counter, now));
            }
        }
        if (limited) {
            return new Decision(false, wholeSeconds(retryMillis));
        }
        if (counters.size() + newCounters > maxEntries) {
            Counter oldest = expiryOrder.peekFirst();
            long untilFree = oldest == null ? window.toMillis() : remainingMillis(oldest, now);
            return new Decision(false, wholeSeconds(untilFree));
        }
        for (Charge charge : charges) {
            Counter counter = counters.get(charge.key());
            if (counter == null) {
                counter = new Counter(charge.key(), now);
                counters.put(charge.key(), counter);
                expiryOrder.addLast(counter);
            }
            counter.count++;
        }
        return new Decision(true, 0);
    }

    synchronized int retainedEntries() {
        expire(advance());
        return counters.size();
    }

    private Instant advance() {
        Instant observed = clock.instant();
        if (observed.isAfter(lastNow)) {
            lastNow = observed;
        }
        return lastNow;
    }

    private void expire(Instant now) {
        Counter head = expiryOrder.peekFirst();
        while (head != null && !now.isBefore(head.startedAt.plus(window))) {
            expiryOrder.removeFirst();
            counters.remove(head.key);
            head = expiryOrder.peekFirst();
        }
    }

    private long remainingMillis(Counter counter, Instant now) {
        return Duration.between(now, counter.startedAt.plus(window)).toMillis();
    }

    private static long wholeSeconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    private static final class Counter {
        private final Digest key;
        private final Instant startedAt;
        private int count;

        private Counter(Digest key, Instant startedAt) {
            this.key = key;
            this.startedAt = startedAt;
        }
    }
}
