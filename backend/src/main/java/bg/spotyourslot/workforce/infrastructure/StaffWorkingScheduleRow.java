package bg.spotyourslot.workforce.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record StaffWorkingScheduleRow(
        UUID businessId,
        UUID staffMemberId,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
