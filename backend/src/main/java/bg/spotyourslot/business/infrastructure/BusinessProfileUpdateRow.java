package bg.spotyourslot.business.infrastructure;

import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.BusinessTimezone;
import bg.spotyourslot.business.domain.BusinessType;
import java.time.Instant;

public record BusinessProfileUpdateRow(
        BusinessSlug slug,
        String displayName,
        BusinessType businessType,
        BusinessTimezone timezone,
        String description,
        String address,
        String phone,
        String contactEmail,
        long expectedVersion,
        Instant updatedAt) {}
