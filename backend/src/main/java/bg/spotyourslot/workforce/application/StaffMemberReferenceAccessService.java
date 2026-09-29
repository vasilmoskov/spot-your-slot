package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.StaffMemberReferenceAccess;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StaffMemberReferenceAccessService implements StaffMemberReferenceAccess {
    private final StaffMemberStore store;

    public StaffMemberReferenceAccessService(StaffMemberStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StaffMemberReference> lockReference(UUID businessId, UUID staffMemberId) {
        try {
            return store.lockActiveState(businessId, staffMemberId)
                    .map(row -> new StaffMemberReference(row.id(), row.active()));
        } catch (UnexpectedFailure exception) {
            throw new StaffMemberReferenceFailure(exception);
        }
    }
}
