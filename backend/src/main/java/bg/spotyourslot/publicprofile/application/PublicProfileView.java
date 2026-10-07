package bg.spotyourslot.publicprofile.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The application-level public profile: the approved allowlist and nothing else. It is built from
 * purpose-built published records, never from an administration DTO, so it carries no identifier.
 */
public record PublicProfileView(
        String slug,
        String displayName,
        String businessType,
        String description,
        String phone,
        Address address,
        List<Service> services) {
    public record Address(
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String details) {
    }

    public record Service(
            UUID id,
            String name,
            String description,
            int durationMinutes,
            BigDecimal price) {
    }
}
