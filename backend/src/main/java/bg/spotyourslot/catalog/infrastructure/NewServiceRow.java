package bg.spotyourslot.catalog.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record NewServiceRow(
        UUID id,
        UUID businessId,
        String name,
        String description,
        int durationMinutes,
        BigDecimal price,
        Instant createdAt) {
}
