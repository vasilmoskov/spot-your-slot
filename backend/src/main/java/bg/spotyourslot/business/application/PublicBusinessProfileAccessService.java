package bg.spotyourslot.business.application;

import bg.spotyourslot.business.PublicBusinessProfileAccess;
import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.ReservedBusinessSlugs;
import bg.spotyourslot.business.infrastructure.BusinessStore;
import bg.spotyourslot.business.infrastructure.PublicBusinessProfileRow;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PublicBusinessProfileAccessService implements PublicBusinessProfileAccess {
    private final BusinessStore store;

    public PublicBusinessProfileAccessService(BusinessStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<PublicBusinessProfile> findActiveBySlug(String slug) {
        Optional<BusinessSlug> canonical = canonicalPublicSlug(slug);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        return store.findActivePublicProfileBySlug(canonical.orElseThrow()).map(this::profile);
    }

    /**
     * A malformed slug can never identify a Business, and a reserved public root is never a
     * public page, so neither reaches the database.
     */
    private Optional<BusinessSlug> canonicalPublicSlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        BusinessSlug canonical;
        try {
            canonical = new BusinessSlug(slug);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (ReservedBusinessSlugs.isReserved(canonical)) {
            return Optional.empty();
        }
        return Optional.of(canonical);
    }

    private PublicBusinessProfile profile(PublicBusinessProfileRow row) {
        return new PublicBusinessProfile(
                row.id(),
                row.slug(),
                row.displayName(),
                row.businessType(),
                row.description(),
                row.phone(),
                address(row));
    }

    private PublicAddress address(PublicBusinessProfileRow row) {
        if (row.city() == null
                && row.postalCode() == null
                && row.street() == null
                && row.streetNumber() == null
                && row.addressDetails() == null) {
            return null;
        }
        return new PublicAddress(
                row.city(),
                row.postalCode(),
                row.street(),
                row.streetNumber(),
                row.addressDetails());
    }
}
