package bg.spotyourslot.workforce.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record StaffMemberRow(
        UUID id,
        UUID businessId,
        String displayName,
        String contactEmail,
        String contactPhone,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
