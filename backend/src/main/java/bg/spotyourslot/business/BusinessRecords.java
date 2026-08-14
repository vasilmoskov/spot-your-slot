package bg.spotyourslot.business;

import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BusinessRecords {
    private BusinessRecords() {}

    public record BusinessSummary(
            UUID id,
            String slug,
            String displayName,
            BusinessType businessType,
            BusinessStatus status,
            String timezone,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record BusinessDetails(
            UUID id,
            String slug,
            String displayName,
            BusinessType businessType,
            BusinessStatus status,
            String timezone,
            String description,
            String address,
            String phone,
            String contactEmail,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record BusinessPage(
            List<BusinessSummary> businesses,
            int page,
            int size,
            long totalElements) {
        public BusinessPage {
            businesses = List.copyOf(businesses);
        }
    }

    public record CreateBusinessCommand(
            String slug,
            String displayName,
            BusinessType businessType,
            String timezone,
            String description,
            String address,
            String phone,
            String contactEmail) {}

    public record UpdateBusinessCommand(
            String slug,
            String displayName,
            BusinessType businessType,
            String timezone,
            String description,
            String address,
            String phone,
            String contactEmail,
            long expectedVersion) {}
}
