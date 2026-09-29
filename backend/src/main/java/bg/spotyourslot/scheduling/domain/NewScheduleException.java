package bg.spotyourslot.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A schedule exception to insert at version zero; the caller supplies its identity and instant. */
public record NewScheduleException(
        UUID id,
        UUID businessId,
        ScheduleExceptionContent content,
        Instant createdAt) {
    public NewScheduleException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
