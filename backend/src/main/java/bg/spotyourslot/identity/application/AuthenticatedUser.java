package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.application.IdentityRecords.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public record AuthenticatedUser(UUID sessionId, User user, UUID activeBusinessId)
        implements Authentication, AuthenticatedBusinessContext {
    @Override
    public UUID userId() {
        return user.id();
    }

    @Override
    public Optional<UUID> selectedBusinessId() {
        return Optional.ofNullable(activeBusinessId);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return user.platformAdmin()
                ? List.of(new SimpleGrantedAuthority("PLATFORM_ADMIN"))
                : List.of();
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return user;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean value) {
        if (!value) {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public String getName() {
        return user.id().toString();
    }
}
