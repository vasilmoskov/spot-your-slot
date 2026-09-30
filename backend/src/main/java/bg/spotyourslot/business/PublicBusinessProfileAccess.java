package bg.spotyourslot.business;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow published read contract for the public Business profile (ADR-0017). It exposes only
 * the approved public facts of an ACTIVE Business, never {@code BusinessDetails}, a persistence
 * row, the contact email, the timezone, the lifecycle status, a version, or a timestamp. It
 * writes nothing and takes no lock.
 */
public interface PublicBusinessProfileAccess {
    /**
     * Reads inside the caller's transaction; a caller without one is rejected.
     *
     * <p>The slug is canonicalized (stripped and lowercased) and validated here, so the slug
     * grammar has one owner. A malformed slug and a slug on the reserved public-root list return
     * empty without issuing any SQL. Every other lookup issues exactly one statement whose
     * predicate is {@code status = 'ACTIVE'}, so a DRAFT, a SUSPENDED, and an unknown Business
     * are indistinguishable to the caller.
     *
     * @return the public profile of the ACTIVE Business, or empty when none is public
     */
    Optional<PublicBusinessProfile> findActiveBySlug(String slug);

    /**
     * @param businessId identifies the Business for internal orchestration only and must never
     *        be serialized publicly
     * @param businessType the BusinessType enum name
     * @param address {@code null} when every address part is empty
     */
    record PublicBusinessProfile(
            UUID businessId,
            String slug,
            String displayName,
            String businessType,
            String description,
            String phone,
            PublicAddress address) {
        public PublicBusinessProfile {
            Objects.requireNonNull(businessId, "businessId");
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(businessType, "businessType");
        }
    }

    record PublicAddress(
            String city,
            String postalCode,
            String street,
            String streetNumber,
            String details) {
    }
}
