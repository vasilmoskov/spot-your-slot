package bg.spotyourslot.workforce.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record NewStaffWorkingScheduleRow(UUID businessId, UUID staffMemberId, Instant createdAt) {
}
