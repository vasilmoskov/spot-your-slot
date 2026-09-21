package bg.spotyourslot.workforce.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record NewStaffMemberRow(
        UUID id,
        UUID businessId,
        String displayName,
        String contactEmail,
        String contactPhone,
        Instant createdAt) {
}
