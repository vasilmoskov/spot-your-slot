package bg.spotyourslot.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationRateLimiter {
    static final int DEFAULT_MAX_ENTRIES = 10_000;
    static final int ATTEMPT_LIMIT = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Counter> counters = new HashMap<>();
    private final Clock clock;
    private final int maxEntries;

    @Autowired
    public AuthenticationRateLimiter(Clock clock) {
        this(clock, DEFAULT_MAX_ENTRIES);
    }

    public AuthenticationRateLimiter(Clock clock, int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    public synchronized boolean allow(String flow, String clientAddress, String sensitiveInput) {
        Instant now = clock.instant();
        removeExpired(now);

        String key = fingerprint(flow, clientAddress, sensitiveInput);
        Counter current = counters.get(key);
        if (current == null) {
            if (counters.size() >= maxEntries) {
                return false;
            }
            counters.put(key, new Counter(now, 1));
            return true;
        }
        if (current.count() >= ATTEMPT_LIMIT) {
            return false;
        }
        counters.put(key, new Counter(current.startedAt(), current.count() + 1));
        return true;
    }

    public synchronized int retainedEntries() {
        return counters.size();
    }

    private void removeExpired(Instant now) {
        Iterator<Counter> iterator = counters.values().iterator();
        while (iterator.hasNext()) {
            Counter counter = iterator.next();
            if (!now.isBefore(counter.startedAt().plus(WINDOW))) {
                iterator.remove();
            }
        }
    }

    private String fingerprint(String flow, String clientAddress, String sensitiveInput) {
        String material = flow + '\u0000' + clientAddress + '\u0000' + sensitiveInput;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Counter(Instant startedAt, int count) {}
}
