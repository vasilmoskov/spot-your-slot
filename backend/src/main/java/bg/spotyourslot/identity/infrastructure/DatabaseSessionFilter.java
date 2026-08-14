package bg.spotyourslot.identity.infrastructure;

import bg.spotyourslot.identity.application.AuthenticatedUser;
import bg.spotyourslot.identity.domain.SessionPolicy;
import bg.spotyourslot.identity.domain.TokenCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class DatabaseSessionFilter extends OncePerRequestFilter {
    public static final String COOKIE = "SPOTYOURSESSION";
    private final IdentityStore store;
    private final TokenCodec tokens;
    private final Clock clock;
    private final SessionPolicy policy = new SessionPolicy();

    public DatabaseSessionFilter(IdentityStore store, TokenCodec tokens, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getCookies() == null
                ? null
                : Arrays.stream(request.getCookies())
                        .filter(cookie -> COOKIE.equals(cookie.getName()))
                        .map(Cookie::getValue)
                        .findFirst()
                        .orElse(null);
        if (raw != null) {
            store.session(tokens.hash(raw)).ifPresent(session -> {
                Instant now = clock.instant();
                if (!session.revoked()
                        && session.user().active()
                        && !session.user().locked()
                        && session.user().credentialVersion() == session.credentialVersion()
                        && !policy.expired(
                                now, session.lastActivityAt(), session.absoluteExpiresAt())) {
                    UUID activeBusinessId = session.activeBusinessId();
                    if (activeBusinessId != null
                            && store.business(session.user().id(), activeBusinessId).isEmpty()) {
                        store.clearBusiness(session.id());
                        activeBusinessId = null;
                    }
                    store.touch(session.id(), now);
                    SecurityContextHolder.getContext()
                            .setAuthentication(new AuthenticatedUser(
                                    session.id(), session.user(), activeBusinessId));
                } else {
                    store.revoke(session.id(), now);
                }
            });
        }
        chain.doFilter(request, response);
    }
}
