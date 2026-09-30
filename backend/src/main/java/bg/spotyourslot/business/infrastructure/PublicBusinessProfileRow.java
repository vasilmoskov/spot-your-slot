package bg.spotyourslot.business.infrastructure;

import java.util.UUID;

/**
 * The explicit column list of the public profile read. It deliberately omits the contact email,
 * timezone, status, version, and timestamps.
 */
public record PublicBusinessProfileRow(
        UUID id,
        String slug,
        String displayName,
        String businessType,
        String description,
        String city,
        String postalCode,
        String street,
        String streetNumber,
        String addressDetails,
        String phone) {
}
