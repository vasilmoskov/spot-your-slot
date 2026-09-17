package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.application.IdentityRecords.BusinessAccess;
import bg.spotyourslot.identity.domain.MembershipRole;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BusinessAuthorizer implements SelectedBusinessOwnerAccess {
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Authorization authorize(UUID userId, UUID businessId) {
        return authorization(store.hasActiveOwnerMembership(userId, businessId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Authorization lockAndAuthorize(UUID userId, UUID businessId) {
        return authorization(store.lockActiveOwnerMembership(userId, businessId));
    }

    private Authorization authorization(boolean granted) {
        return granted ? Authorization.GRANTED : Authorization.DENIED;
    }
}
