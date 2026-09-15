package bg.spotyourslot.catalog.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceRow(
        UUID id,
        UUID businessId,
        String name,
        String description,
        int durationMinutes,
        BigDecimal price,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
