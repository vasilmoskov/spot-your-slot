package bg.spotyourslot.workforce.infrastructure;

import java.time.Instant;

public record StaffMemberProfileUpdateRow(
        String displayName,
        String contactEmail,
        String contactPhone,
        long expectedVersion,
        Instant updatedAt) {
}
