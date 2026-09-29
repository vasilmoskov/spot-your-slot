package bg.spotyourslot.integration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A controllable UTC clock that also counts how often it is read. */
public final class MutableTestClock extends Clock {
    private final AtomicReference<Instant> now;
    private final AtomicInteger reads = new AtomicInteger();

    public MutableTestClock(Instant initial) {
        this.now = new AtomicReference<>(initial);
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public int reads() {
        return reads.get();
    }

    public void resetReads() {
        reads.set(0);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("Zone changes are not supported");
    }

    @Override
    public Instant instant() {
        reads.incrementAndGet();
        return now.get();
    }
}
