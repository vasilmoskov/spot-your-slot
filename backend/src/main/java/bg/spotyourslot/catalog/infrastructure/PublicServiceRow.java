package bg.spotyourslot.catalog.infrastructure;

import java.math.BigDecimal;

/** The explicit column list of the public Service read; no identifier or lifecycle data. */
public record PublicServiceRow(
        String name,
        String description,
        int durationMinutes,
        BigDecimal price) {
}
