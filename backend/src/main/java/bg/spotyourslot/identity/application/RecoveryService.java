package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.domain.EmailAddress;
import bg.spotyourslot.identity.domain.PasswordPolicy;
import bg.spotyourslot.identity.domain.TokenCodec;
import bg.spotyourslot.identity.infrastructure.IdentityStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryService {
    private final IdentityStore store;
    private final TokenCodec tokens;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final DevelopmentMailbox mailbox;
    private final Clock clock;
    private final String origin;

    public RecoveryService(
            IdentityStore store,
            TokenCodec tokens,
            PasswordEncoder passwords,
            PasswordPolicy policy,
            DevelopmentMailbox mailbox,
            Clock clock,
            @Value("${spotyourslot.security.allowed-origin}") String origin) {
        this.store = store;
        this.tokens = tokens;
        this.passwords = passwords;
        this.policy = policy;
        this.mailbox = mailbox;
        this.clock = clock;
        this.origin = origin;
    }

    @Transactional
    public void request(String email) {
        String normalized = EmailAddress.normalize(email);
        store.userByEmail(normalized)
                .filter(user -> user.active() && !user.locked())
                .ifPresent(user -> issueReset(user.id(), normalized));
    }

    private void issueReset(UUID userId, String normalizedEmail) {
        Instant now = clock.instant();
        store.lockResetGeneration(userId);
        store.invalidateResets(userId, now);
        String raw = tokens.create();
        store.createReset(userId, tokens.hash(raw), now, now.plus(Duration.ofMinutes(30)));
        mailbox.deliver(
                "PASSWORD_RESET", normalizedEmail, origin + "/password-reset?token=" + raw);
    }

    @Transactional
    public void reset(String token, String password) {
        policy.validate(password);
        Instant now = clock.instant();
        var row = store.lockReset(tokens.hash(token))
                .filter(reset -> !reset.consumed()
                        && !reset.invalidated()
                        && now.isBefore(reset.expiresAt()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid reset"));
        store.updatePassword(row.userId(), passwords.encode(password), now);
        store.consumeReset(row.id(), now);
        store.invalidateResets(row.userId(), now);
        store.revokeAllSessions(row.userId(), now);
    }
}
