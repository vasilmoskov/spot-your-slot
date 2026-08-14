package bg.spotyourslot.identity.domain;

import java.time.Duration;
import java.time.Instant;

public final class SessionPolicy {
    public static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(12);
    public static final Duration IDLE_TIMEOUT = Duration.ofHours(2);

    public boolean expired(Instant now, Instant lastActivity, Instant absoluteExpiry) {
        return !now.isBefore(absoluteExpiry) || !now.isBefore(lastActivity.plus(IDLE_TIMEOUT));
    }
}
