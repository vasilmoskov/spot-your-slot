package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.ActiveBusinessOwnerQuery;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActiveBusinessOwnerQueryService implements ActiveBusinessOwnerQuery {
    private final IdentityStore store;

    public ActiveBusinessOwnerQueryService(IdentityStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasActiveBusinessOwner(UUID businessId) {
        if (businessId == null) {
            throw new IllegalArgumentException("Business ID is required");
        }
        return store.lockActiveBusinessOwner(businessId);
    }
}
