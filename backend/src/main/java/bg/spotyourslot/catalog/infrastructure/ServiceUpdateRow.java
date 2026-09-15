package bg.spotyourslot.catalog.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;

public record ServiceUpdateRow(
        String name,
        String description,
        int durationMinutes,
        BigDecimal price,
        long expectedVersion,
        Instant updatedAt) {
}
