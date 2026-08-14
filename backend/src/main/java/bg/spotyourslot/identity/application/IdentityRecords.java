package bg.spotyourslot.identity.application;

import bg.spotyourslot.identity.domain.MembershipRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IdentityRecords {
    private IdentityRecords() {}

    public record User(
            UUID id,
            String email,
            String displayName,
            String passwordHash,
            boolean active,
            boolean locked,
            long credentialVersion,
            boolean platformAdmin) {}

    public record BusinessAccess(
            UUID id,
            String slug,
            String displayName,
            String status,
            MembershipRole role) {}

    public record Session(
            UUID id,
            User user,
            UUID activeBusinessId,
            long credentialVersion,
            Instant createdAt,
            Instant lastActivityAt,
            Instant absoluteExpiresAt,
            boolean revoked) {}

    public record LoginResult(String token, User user, List<BusinessAccess> businesses) {}

    public record DeliveredLink(UUID id, String kind, String recipient, String url, Instant createdAt) {}
}
