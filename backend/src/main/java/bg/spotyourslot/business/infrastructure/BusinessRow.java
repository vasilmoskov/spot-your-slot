package bg.spotyourslot.business.infrastructure;

import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessTimezone;
import bg.spotyourslot.business.domain.BusinessType;
import java.time.Instant;
import java.util.UUID;

public record BusinessRow(
        UUID id,
        BusinessSlug slug,
        String displayName,
        BusinessType businessType,
        BusinessStatus status,
        BusinessTimezone timezone,
        String description,
        String city,
        String postalCode,
        String street,
        String streetNumber,
        String addressDetails,
        String phone,
        String contactEmail,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
