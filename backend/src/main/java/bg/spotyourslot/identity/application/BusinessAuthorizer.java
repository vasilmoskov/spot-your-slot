package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.application.IdentityRecords.BusinessAccess;
import bg.spotyourslot.identity.domain.MembershipRole;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class BusinessAuthorizer {
    private final IdentityStore store;
    public BusinessAuthorizer(IdentityStore store) {
        this.store = store;
    }

    public BusinessAccess require(AuthenticatedUser auth, UUID businessId, MembershipRole... allowed) {
        var access = store.business(auth.user().id(), businessId)
                .orElseThrow(() -> new AccessDeniedException("Business access denied"));
        if (Arrays.stream(allowed).noneMatch(access.role()::equals)) {
            throw new AccessDeniedException("Role denied");
        }
        return access;
    }
}
