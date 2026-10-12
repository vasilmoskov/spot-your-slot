package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.StaffAvailabilityAccess;
import bg.spotyourslot.workforce.infrastructure.StaffAvailabilityReadStore;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StaffAvailabilityAccessService implements StaffAvailabilityAccess {
    private final StaffAvailabilityReadStore store;

    public StaffAvailabilityAccessService(StaffAvailabilityReadStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<EligibleStaffMember> findEligibleForService(UUID businessId, UUID serviceId) {
        return findEligibleForServices(businessId, List.of(serviceId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<EligibleStaffMember> findEligibleForServices(
            UUID businessId, Collection<UUID> serviceIds) {
        ServiceSelection.require(serviceIds);
        try {
            return store.findEligibleForServices(businessId, serviceIds).stream()
                    .map(row -> new EligibleStaffMember(row.id(), row.weeklyPeriods()))
                    .toList();
        } catch (UnexpectedFailure exception) {
            throw new StaffAvailabilityFailure(exception);
        }
    }
}
