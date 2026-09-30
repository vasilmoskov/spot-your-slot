package bg.spotyourslot.publicprofile.web;

import bg.spotyourslot.publicprofile.application.PublicProfileView;
import java.math.BigDecimal;
import java.util.List;

/**
 * The public HTTP contract. Every property is an explicit allowlist entry; there is no
 * identifier, status, version, timestamp, contact email, timezone, or currency.
 */
public final class PublicProfileHttpRecords {
    private PublicProfileHttpRecords() {
    }

    public record PublicProfileResponse(
            String slug,
            String displayName,
            String businessType,
            String description,
            String phone,
            AddressResponse address,
            List<ServiceResponse> services) {
        static PublicProfileResponse from(PublicProfileView view) {
            return new PublicProfileResponse(
                    view.slug(),
                    view.displayName(),
                    view.businessType(),
                    view.description(),
                    view.phone(),
                    view.address() == null ? null : AddressResponse.from(view.address()),
                    view.services().stream().map(ServiceResponse::from).toList());
        }
    }

    public record AddressResponse(
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String details) {
        static AddressResponse from(PublicProfileView.Address address) {
            return new AddressResponse(
                    address.city(),
                    address.postalCode(),
                    address.street(),
                    address.streetNumber(),
                    address.details());
        }
    }

    public record ServiceResponse(
            String name,
            String description,
            int durationMinutes,
            BigDecimal price) {
        static ServiceResponse from(PublicProfileView.Service service) {
            return new ServiceResponse(
                    service.name(),
                    service.description(),
                    service.durationMinutes(),
                    service.price());
        }
    }
}
