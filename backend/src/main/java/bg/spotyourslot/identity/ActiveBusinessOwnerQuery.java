package bg.spotyourslot.identity;

import java.util.UUID;

public interface ActiveBusinessOwnerQuery {
    boolean hasActiveBusinessOwner(UUID businessId);
}
