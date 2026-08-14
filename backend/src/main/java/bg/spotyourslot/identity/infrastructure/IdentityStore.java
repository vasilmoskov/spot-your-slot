package bg.spotyourslot.identity.infrastructure;

import bg.spotyourslot.identity.application.IdentityRecords.BusinessAccess;
import bg.spotyourslot.identity.application.IdentityRecords.Session;
import bg.spotyourslot.identity.application.IdentityRecords.User;
import bg.spotyourslot.identity.domain.MembershipRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityStore {
    private final JdbcClient jdbc;

    public IdentityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> userByEmail(String email) {
        return jdbc.sql("""
                SELECT u.*, EXISTS (SELECT 1 FROM platform_role p WHERE p.user_id=u.id AND p.role='PLATFORM_ADMIN') platform_admin
                FROM app_user u WHERE normalized_email=:email
                """)
                .param("email", email)
                .query(this::user)
                .optional();
    }

    public Optional<User> userById(UUID id) {
        return jdbc.sql("""
                SELECT u.*, EXISTS (SELECT 1 FROM platform_role p WHERE p.user_id=u.id AND p.role='PLATFORM_ADMIN') platform_admin
                FROM app_user u WHERE id=:id
                """)
                .param("id", id)
                .query(this::user)
                .optional();
    }

    public List<BusinessAccess> businesses(UUID userId) {
        return jdbc.sql("""
                SELECT b.id,b.slug,b.display_name,b.status,m.role FROM membership m
                JOIN business b ON b.id=m.business_id
                WHERE m.user_id=:userId AND m.active=true ORDER BY b.display_name,b.id
                """)
                .param("userId", userId)
                .query((rs, rowNumber) -> new BusinessAccess(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        MembershipRole.valueOf(rs.getString("role"))))
                .list();
    }

    public Optional<BusinessAccess> business(UUID userId, UUID businessId) {
        return jdbc.sql("""
                SELECT b.id,b.slug,b.display_name,b.status,m.role FROM membership m
                JOIN business b ON b.id=m.business_id
                WHERE m.user_id=:userId AND m.business_id=:businessId AND m.active=true
                """)
                .param("userId", userId)
                .param("businessId", businessId)
                .query((rs, rowNumber) -> new BusinessAccess(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("display_name"),
                        rs.getString("status"),
                        MembershipRole.valueOf(rs.getString("role"))))
                .optional();
    }

    public boolean lockActiveBusinessOwner(UUID businessId) {
        return jdbc.sql("""
                        SELECT id
                        FROM membership
                        WHERE business_id = :businessId
                          AND role = 'BUSINESS_OWNER'
                          AND active = true
                        ORDER BY id
                        LIMIT 1
                        FOR SHARE
                        """)
                .param("businessId", businessId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public void createSession(UUID id, String hash, User user, Instant now, Instant expires) {
        jdbc.sql("""
                INSERT INTO user_session(id,token_hash,user_id,credential_version,created_at,last_activity_at,absolute_expires_at)
                VALUES (:id,:hash,:userId,:version,:now,:now,:expires)
                """)
                .param("id", id)
                .param("hash", hash)
                .param("userId", user.id())
                .param("version", user.credentialVersion())
                .param("now", db(now))
                .param("expires", db(expires))
                .update();
    }

    public Optional<Session> session(String hash) {
        return jdbc.sql("""
                SELECT s.id session_id,s.active_business_id,s.credential_version session_credential_version,s.created_at session_created,s.last_activity_at,
                       s.absolute_expires_at,s.revoked_at,u.*,
                       EXISTS (SELECT 1 FROM platform_role p WHERE p.user_id=u.id AND p.role='PLATFORM_ADMIN') platform_admin
                FROM user_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=:hash
                """)
                .param("hash", hash)
                .query((rs, rowNumber) -> new Session(
                        rs.getObject("session_id", UUID.class),
                        user(rs, rowNumber),
                        rs.getObject("active_business_id", UUID.class),
                        rs.getLong("session_credential_version"),
                        rs.getObject("session_created", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_activity_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("absolute_expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("revoked_at") != null))
                .optional();
    }

    public void touch(UUID id, Instant now) {
        jdbc.sql("UPDATE user_session SET last_activity_at=:now WHERE id=:id")
                .param("now", db(now))
                .param("id", id)
                .update();
    }

    public void selectBusiness(UUID sessionId, UUID businessId) {
        jdbc.sql("UPDATE user_session SET active_business_id=:businessId WHERE id=:id")
                .param("businessId", businessId)
                .param("id", sessionId)
                .update();
    }

    public void clearBusiness(UUID sessionId) {
        jdbc.sql("UPDATE user_session SET active_business_id=NULL WHERE id=:id")
                .param("id", sessionId)
                .update();
    }

    public void revoke(UUID id, Instant now) {
        jdbc.sql("UPDATE user_session SET revoked_at=COALESCE(revoked_at,:now) WHERE id=:id")
                .param("now", db(now))
                .param("id", id)
                .update();
    }

    public void revokeOtherSessions(UUID userId, UUID current, Instant now) {
        jdbc.sql("UPDATE user_session SET revoked_at=:now WHERE user_id=:userId AND id<>:current AND revoked_at IS NULL")
                .param("now", db(now))
                .param("userId", userId)
                .param("current", current)
                .update();
    }

    public void revokeAllSessions(UUID userId, Instant now) {
        jdbc.sql("UPDATE user_session SET revoked_at=:now WHERE user_id=:userId AND revoked_at IS NULL")
                .param("now", db(now))
                .param("userId", userId)
                .update();
    }

    public void updatePassword(UUID userId, String hash, Instant now) {
        jdbc.sql("UPDATE app_user SET password_hash=:hash,password_changed_at=:now,credential_version=credential_version+1,updated_at=:now WHERE id=:id")
                .param("hash", hash)
                .param("now", db(now))
                .param("id", userId)
                .update();
    }

    public void updateSessionCredentialVersion(UUID sessionId, long version) {
        jdbc.sql("UPDATE user_session SET credential_version=:version WHERE id=:id")
                .param("version", version)
                .param("id", sessionId)
                .update();
    }

    public UUID createUser(String email, String name, String hash, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,normalized_email,display_name,password_hash,password_changed_at,created_at,updated_at) VALUES (:id,:email,:name,:hash,:now,:now,:now)")
                .param("id", id)
                .param("email", email)
                .param("name", name)
                .param("hash", hash)
                .param("now", db(now))
                .update();
        return id;
    }

    public void grantPlatformAdmin(UUID userId, Instant now) {
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',:now) ON CONFLICT DO NOTHING")
                .param("id", userId)
                .param("now", db(now))
                .update();
    }

    public void grantOwner(UUID businessId, UUID userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO membership(id,business_id,user_id,role,active,created_at,updated_at) VALUES (:id,:businessId,:userId,'BUSINESS_OWNER',true,:now,:now)
                        ON CONFLICT (user_id,business_id) DO UPDATE SET role='BUSINESS_OWNER',active=true,updated_at=:now
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .param("userId", userId)
                .param("now", db(now))
                .update();
    }

    public void invalidateInvitations(UUID businessId, String email, Instant now) {
        jdbc.sql("UPDATE owner_invitation SET invalidated_at=:now WHERE business_id=:businessId AND normalized_email=:email AND consumed_at IS NULL AND invalidated_at IS NULL")
                .param("now", db(now))
                .param("businessId", businessId)
                .param("email", email)
                .update();
    }
    public void lockInvitationGeneration(UUID businessId, String email) {
        advisoryLock("owner-invitation:" + businessId + ":" + email);
    }
    public UUID createInvitation(
            UUID businessId,
            String email,
            String hash,
            UUID creator,
            Instant now,
            Instant expiry) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO owner_invitation(id,business_id,normalized_email,token_hash,created_by,created_at,expires_at) VALUES (:id,:businessId,:email,:hash,:creator,:now,:expiry)")
                .param("id", id)
                .param("businessId", businessId)
                .param("email", email)
                .param("hash", hash)
                .param("creator", creator)
                .param("now", db(now))
                .param("expiry", db(expiry))
                .update();
        return id;
    }

    public Optional<InvitationRow> lockInvitation(String hash) {
        return jdbc.sql("SELECT id,business_id,normalized_email,expires_at,consumed_at,invalidated_at FROM owner_invitation WHERE token_hash=:hash FOR UPDATE")
                .param("hash", hash)
                .query((rs, rowNumber) -> new InvitationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("business_id", UUID.class),
                        rs.getString("normalized_email"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("consumed_at") != null,
                        rs.getObject("invalidated_at") != null))
                .optional();
    }

    public void consumeInvitation(UUID id, Instant now) {
        jdbc.sql("UPDATE owner_invitation SET consumed_at=:now WHERE id=:id AND consumed_at IS NULL AND invalidated_at IS NULL")
                .param("now", db(now))
                .param("id", id)
                .update();
    }

    public void invalidateResets(UUID userId, Instant now) {
        jdbc.sql("UPDATE password_reset SET invalidated_at=:now WHERE user_id=:userId AND consumed_at IS NULL AND invalidated_at IS NULL")
                .param("now", db(now))
                .param("userId", userId)
                .update();
    }
    public void lockResetGeneration(UUID userId) {
        advisoryLock("password-reset:" + userId);
    }
    public UUID createReset(UUID userId, String hash, Instant now, Instant expiry) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO password_reset(id,user_id,token_hash,created_at,expires_at) VALUES (:id,:userId,:hash,:now,:expiry)")
                .param("id", id)
                .param("userId", userId)
                .param("hash", hash)
                .param("now", db(now))
                .param("expiry", db(expiry))
                .update();
        return id;
    }

    public Optional<ResetRow> lockReset(String hash) {
        return jdbc.sql("SELECT id,user_id,expires_at,consumed_at,invalidated_at FROM password_reset WHERE token_hash=:hash FOR UPDATE")
                .param("hash", hash)
                .query((rs, rowNumber) -> new ResetRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("consumed_at") != null,
                        rs.getObject("invalidated_at") != null))
                .optional();
    }

    public void consumeReset(UUID id, Instant now) {
        jdbc.sql("UPDATE password_reset SET consumed_at=:now WHERE id=:id AND consumed_at IS NULL AND invalidated_at IS NULL")
                .param("now", db(now))
                .param("id", id)
                .update();
    }

    public boolean businessExists(UUID id) {
        return jdbc.sql("SELECT count(*) FROM business WHERE id=:id")
                        .param("id", id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private void advisoryLock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key)
                .query((resultSet, rowNumber) -> rowNumber)
                .single();
    }

    private User user(ResultSet rs, int row) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("normalized_email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBoolean("active"),
                rs.getBoolean("locked"),
                rs.getLong("credential_version"),
                rs.getBoolean("platform_admin"));
    }

    public record InvitationRow(
            UUID id,
            UUID businessId,
            String email,
            Instant expiresAt,
            boolean consumed,
            boolean invalidated) {}

    public record ResetRow(
            UUID id,
            UUID userId,
            Instant expiresAt,
            boolean consumed,
            boolean invalidated) {}

    private static OffsetDateTime db(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
