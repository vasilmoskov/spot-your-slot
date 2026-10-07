package bg.spotyourslot.catalog.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

/** The explicit column list of the public Service read; no version, timestamp, or lifecycle data. */
public record PublicServiceRow(
        UUID id,
        String name,
        String description,
        int durationMinutes,
        BigDecimal price) {
}
