package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.PublicStaffAccess;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PublicStaffAccessService implements PublicStaffAccess {
    private final StaffMemberStore store;

    public PublicStaffAccessService(StaffMemberStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<PublicStaffMember> findBookableStaff(UUID businessId, UUID serviceId) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(serviceId, "serviceId");
        try {
            return store.findBookableStaff(businessId, serviceId).stream()
                    .map(row -> new PublicStaffMember(row.id(), row.displayName()))
                    .toList();
        } catch (StaffMemberPersistenceException exception) {
            throw new PublicStaffFailure(exception);
        }
    }
}
