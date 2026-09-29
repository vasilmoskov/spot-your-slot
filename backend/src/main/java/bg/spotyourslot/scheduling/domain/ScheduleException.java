package bg.spotyourslot.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored, versioned schedule exception aggregate. This is a persistence
 * shape and is deliberately independent of the engine's calculation inputs;
 * {@link ScheduleExceptionInputs} translates it.
 */
public record ScheduleException(
        UUID id,
        UUID businessId,
        ScheduleExceptionContent content,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public ScheduleException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("Version must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Updated time must not precede creation");
        }
    }
}
