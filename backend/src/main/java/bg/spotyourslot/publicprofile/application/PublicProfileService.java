package bg.spotyourslot.publicprofile.application;

import bg.spotyourslot.business.PublicBusinessProfileAccess;
import bg.spotyourslot.business.PublicBusinessProfileAccess.PublicAddress;
import bg.spotyourslot.business.PublicBusinessProfileAccess.PublicBusinessProfile;
import bg.spotyourslot.catalog.PublicServiceAccess;
import bg.spotyourslot.catalog.PublicServiceAccess.PublicService;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Resolves one ACTIVE Business by slug and its active Services as one coherent read. Issues at
 * most two SQL statements: the Business (skipped for a malformed or reserved slug) and, only for
 * an ACTIVE Business, its Services; the count does not depend on the number of Services.
 *
 * <p>The transaction is read-only and repeatable-read, so the Business and the Services come from
 * one snapshot. {@code REQUIRED} would silently join a weaker existing transaction and ignore the
 * declared isolation, so the effective isolation is verified before any read (the lesson recorded
 * for availability in ADR-0016). Nothing is written and no explicit lock is taken.
 */
@Service
public class PublicProfileService {
    private final PublicBusinessProfileAccess businesses;
    private final PublicServiceAccess services;

    public PublicProfileService(
            PublicBusinessProfileAccess businesses, PublicServiceAccess services) {
        this.businesses = businesses;
        this.services = services;
    }

    /** @return the profile of the ACTIVE Business, or empty when none is publicly available */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Optional<PublicProfileView> findBySlug(String slug) {
        requireSnapshotIsolation();
        return businesses.findActiveBySlug(slug).map(this::withServices);
    }

    private PublicProfileView withServices(PublicBusinessProfile profile) {
        List<PublicProfileView.Service> active = services.findActiveServices(profile.businessId())
                .stream()
                .map(this::service)
                .toList();
        return new PublicProfileView(
                profile.slug(),
                profile.displayName(),
                profile.businessType(),
                profile.description(),
                profile.phone(),
                address(profile.address()),
                active);
    }

    private PublicProfileView.Address address(PublicAddress address) {
        if (address == null) {
            return null;
        }
        return new PublicProfileView.Address(
                address.city(),
                address.postalCode(),
                address.street(),
                address.streetNumber(),
                address.details());
    }

    private PublicProfileView.Service service(PublicService service) {
        return new PublicProfileView.Service(
                service.id(),
                service.name(),
                service.description(),
                service.durationMinutes(),
                service.price());
    }

    private static void requireSnapshotIsolation() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        boolean snapshotCoherent = TransactionSynchronizationManager.isActualTransactionActive()
                && isolation != null
                && (isolation == Connection.TRANSACTION_REPEATABLE_READ
                        || isolation == Connection.TRANSACTION_SERIALIZABLE);
        if (!snapshotCoherent) {
            throw new IllegalStateException(
                    "The public profile requires a repeatable-read or serializable transaction");
        }
    }
}
