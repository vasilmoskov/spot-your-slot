package bg.spotyourslot.workforce;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StaffMemberRecords {
    private StaffMemberRecords() {
    }

    public record StaffMemberDetails(
            UUID id,
            String displayName,
            String contactEmail,
            String contactPhone,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record StaffMemberPage(
            List<StaffMemberDetails> staffMembers,
            int page,
            int size,
            long totalElements) {
        public StaffMemberPage {
            staffMembers = List.copyOf(staffMembers);
        }
    }

    public record CreateStaffMemberCommand(
            String displayName,
            String contactEmail,
            String contactPhone) {
    }

    public record UpdateStaffMemberCommand(
            String displayName,
            String contactEmail,
            String contactPhone,
            Long expectedVersion) {
    }

    public record StaffMemberVersionCommand(Long expectedVersion) {
    }
}
