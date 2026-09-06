package bg.spotyourslot.business;

import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BusinessRecords {
    private BusinessRecords() {
    }

    public record BusinessSummary(
            UUID id,
            String slug,
            String displayName,
            BusinessType businessType,
            BusinessStatus status,
            String timezone,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public String businessTypeValue() {
            return businessType.name();
        }

        public String statusValue() {
            return status.name();
        }
    }

    public record BusinessDetails(
            UUID id,
            String slug,
            String displayName,
            BusinessType businessType,
            BusinessStatus status,
            String timezone,
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
        public String businessTypeValue() {
            return businessType.name();
        }

        public String statusValue() {
            return status.name();
        }
    }

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
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String addressDetails,
            String phone,
            String contactEmail) {
    }

    public record UpdateBusinessCommand(
            String slug,
            String displayName,
            BusinessType businessType,
            String timezone,
            String description,
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String addressDetails,
            String phone,
            String contactEmail,
            long expectedVersion) {
    }

    public static CreateBusinessCommand createCommand(
            String slug,
            String displayName,
            String businessType,
            String timezone,
            String description,
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String addressDetails,
            String phone,
            String contactEmail) {
        return new CreateBusinessCommand(
                slug,
                displayName,
                BusinessType.valueOf(businessType),
                timezone,
                description,
                city,
                postalCode,
                street,
                streetNumber,
                addressDetails,
                phone,
                contactEmail);
    }

    public static UpdateBusinessCommand updateCommand(
            String slug,
            String displayName,
            String businessType,
            String timezone,
            String description,
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String addressDetails,
            String phone,
            String contactEmail,
            long expectedVersion) {
        return new UpdateBusinessCommand(
                slug,
                displayName,
                BusinessType.valueOf(businessType),
                timezone,
                description,
                city,
                postalCode,
                street,
                streetNumber,
                addressDetails,
                phone,
                contactEmail,
                expectedVersion);
    }
}
