package bg.spotyourslot.identity;

import java.util.UUID;

public interface SelectedBusinessOwnerAccess {
    Authorization authorize(UUID userId, UUID businessId);

    Authorization lockAndAuthorize(UUID userId, UUID businessId);

    enum Authorization {
        GRANTED,
        DENIED
    }
}
