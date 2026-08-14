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
public class InvitationService {
    private final IdentityStore store;
    private final TokenCodec tokens;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final DevelopmentMailbox mailbox;
    private final Clock clock;
    private final String origin;

    public InvitationService(
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
    public void invite(UUID businessId, String email, UUID creator) {
        if (!store.businessExists(businessId)) {
            throw new IllegalArgumentException("Business not found");
        }
        String normalized = EmailAddress.normalize(email);
        Instant now = clock.instant();
        store.lockInvitationGeneration(businessId, normalized);
        store.invalidateInvitations(businessId, normalized, now);
        String raw = tokens.create();
        store.createInvitation(
                businessId,
                normalized,
                tokens.hash(raw),
                creator,
                now,
                now.plus(Duration.ofHours(48)));
        mailbox.deliver("OWNER_INVITATION", normalized, origin + "/invitation?token=" + raw);
    }

    @Transactional
    public void accept(String token, String name, String password) {
        policy.validate(password);
        Instant now = clock.instant();
        var row = store.lockInvitation(tokens.hash(token))
                .filter(invitation -> !invitation.consumed()
                        && !invitation.invalidated()
                        && now.isBefore(invitation.expiresAt()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid invitation"));
        var existing = store.userByEmail(row.email());
        if (existing.isPresent()
                && (!existing.get().active()
                        || existing.get().locked()
                        || !passwords.matches(password, existing.get().passwordHash()))) {
            throw new IllegalArgumentException("Invalid invitation");
        }
        UUID userId = existing.map(user -> user.id())
                .orElseGet(() -> store.createUser(
                        row.email(), name, passwords.encode(password), now));
        store.grantOwner(row.businessId(), userId, now);
        store.consumeInvitation(row.id(), now);
    }
}
