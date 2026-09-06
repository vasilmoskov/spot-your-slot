package bg.spotyourslot.platform.web;

import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.BusinessPage;
import bg.spotyourslot.business.BusinessRecords.BusinessSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlatformBusinessHttpRecords {
    private PlatformBusinessHttpRecords() {
    }

    public record CreateBusinessRequest(
            @NotBlank String slug,
            @NotBlank String displayName,
            @NotBlank String businessType,
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

    public record UpdateBusinessRequest(
            @NotBlank String slug,
            @NotBlank String displayName,
            @NotBlank String businessType,
            @NotBlank String timezone,
            String description,
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String addressDetails,
            String phone,
            String contactEmail,
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record LifecycleRequest(@NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record BusinessSummaryResponse(
            UUID id,
            String slug,
            String displayName,
            String businessType,
            String status,
            String timezone,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static BusinessSummaryResponse from(BusinessSummary business) {
            return new BusinessSummaryResponse(
                    business.id(),
                    business.slug(),
                    business.displayName(),
                    business.businessTypeValue(),
                    business.statusValue(),
                    business.timezone(),
                    business.version(),
                    business.createdAt(),
                    business.updatedAt());
        }
    }

    public record BusinessDetailsResponse(
            UUID id,
            String slug,
            String displayName,
            String businessType,
            String status,
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
        static BusinessDetailsResponse from(BusinessDetails business) {
            return new BusinessDetailsResponse(
                    business.id(),
                    business.slug(),
                    business.displayName(),
                    business.businessTypeValue(),
                    business.statusValue(),
                    business.timezone(),
                    business.description(),
                    business.city(),
                    business.postalCode(),
                    business.street(),
                    business.streetNumber(),
                    business.addressDetails(),
                    business.phone(),
                    business.contactEmail(),
                    business.version(),
                    business.createdAt(),
                    business.updatedAt());
        }
    }

    public record BusinessPageResponse(
            List<BusinessSummaryResponse> businesses,
            int page,
            int size,
            long totalElements) {
        static BusinessPageResponse from(BusinessPage page) {
            return new BusinessPageResponse(
                    page.businesses().stream()
                            .map(BusinessSummaryResponse::from)
                            .toList(),
                    page.page(),
                    page.size(),
                    page.totalElements());
        }
    }
}
