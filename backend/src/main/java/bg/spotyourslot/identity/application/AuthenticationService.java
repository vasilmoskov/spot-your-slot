package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.application.IdentityRecords.LoginResult;
import bg.spotyourslot.identity.application.IdentityRecords.Session;
import bg.spotyourslot.identity.domain.EmailAddress;
import bg.spotyourslot.identity.domain.PasswordPolicy;
import bg.spotyourslot.identity.domain.SessionPolicy;
import bg.spotyourslot.identity.domain.TokenCodec;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private final IdentityStore store;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final TokenCodec tokens;
    private final Clock clock;

    public AuthenticationService(
            IdentityStore store,
            PasswordEncoder passwords,
            PasswordPolicy policy,
            TokenCodec tokens,
            Clock clock) {
        this.store = store;
        this.passwords = passwords;
        this.policy = policy;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public LoginResult login(String email, String password) {
        var user = store.userByEmail(EmailAddress.normalize(email))
                .filter(candidate -> candidate.active() && !candidate.locked())
                .orElseThrow(() -> new BadCredentialsException("invalid"));
        if (!passwords.matches(password, user.passwordHash())) {
            throw new BadCredentialsException("invalid");
        }
        Instant now = clock.instant();
        String token = tokens.create();
        store.createSession(
                UUID.randomUUID(),
                tokens.hash(token),
                user,
                now,
                now.plus(SessionPolicy.ABSOLUTE_LIFETIME));
        return new LoginResult(token, user, store.businesses(user.id()));
    }

    public Optional<Session> authenticate(String token) {
        return store.session(tokens.hash(token));
    }

    @Transactional
    public void logout(UUID sessionId) {
        store.revoke(sessionId, clock.instant());
    }

    @Transactional
    public void selectBusiness(UUID sessionId, UUID userId, UUID businessId) {
        store.business(userId, businessId)
                .orElseThrow(() -> new AccessDeniedException("Business access denied"));
        store.selectBusiness(sessionId, businessId);
    }

    @Transactional
    public IdentityRecords.User updateDisplayName(UUID userId, String displayName) {
        store.updateDisplayName(userId, displayName, clock.instant());
        return store.userById(userId).orElseThrow();
    }

    @Transactional
    public void changePassword(
            UUID sessionId, UUID userId, String current, String replacement) {
        var user = store.userById(userId).orElseThrow();
        if (!passwords.matches(current, user.passwordHash())) {
            throw new CurrentPasswordInvalid();
        }
        policy.validate(replacement);
        Instant now = clock.instant();
        store.updatePassword(userId, passwords.encode(replacement), now);
        store.updateSessionCredentialVersion(sessionId, user.credentialVersion() + 1);
        store.revokeOtherSessions(userId, sessionId, now);
    }
}
