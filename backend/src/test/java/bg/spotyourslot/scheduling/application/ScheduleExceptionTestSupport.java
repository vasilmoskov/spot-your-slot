package bg.spotyourslot.scheduling.application;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Direct-SQL fixtures for the PostgreSQL-backed schedule-exception application tests. */
final class ScheduleExceptionTestSupport {
    static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final JdbcClient jdbc;

    ScheduleExceptionTestSupport(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A Business with the given lifecycle status and one active BUSINESS_OWNER. */
    Fixture fixture(String businessStatus) {
        UUID businessId = business(businessStatus);
        UUID userId = user();
        membership(businessId, userId, "BUSINESS_OWNER", true);
        return new Fixture(businessId, userId, new TestContext(userId, businessId));
    }

    UUID business(String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Schedule Exception Test','OTHER',:status,'Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", "schedule-exception-" + SEQUENCE.incrementAndGet() + "-" + id)
                .param("status", status)
                .param("now", databaseNow())
                .update();
        return id;
    }

    UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,
                            password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'User','hash',:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", id + "@example.invalid")
                .param("now", databaseNow())
                .update();
        return id;
    }

    void membership(UUID businessId, UUID userId, String role, boolean active) {
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:businessId,:userId,:role,:active,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .param("userId", userId)
                .param("role", role)
                .param("active", active)
                .param("now", databaseNow())
                .update();
    }

    UUID staffMember(UUID businessId, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,contact_email,contact_phone,
                            active,version,created_at,updated_at)
                        VALUES (:id,:businessId,'Schedule Exception Staff',NULL,NULL,
                                :active,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("active", active)
                .param("now", databaseNow())
                .update();
        return id;
    }

    void setStaffMemberActive(UUID staffMemberId, boolean active) {
        jdbc.sql("UPDATE staff_member SET active = :active WHERE id = :id")
                .param("active", active)
                .param("id", staffMemberId)
                .update();
    }

    void setBusinessStatus(UUID businessId, String status) {
        jdbc.sql("UPDATE business SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", businessId)
                .update();
    }

    long exceptionCount(UUID businessId) {
        return jdbc.sql("SELECT COUNT(*) FROM schedule_exception WHERE business_id = :id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    long periodCount(UUID exceptionId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM schedule_exception_period
                        WHERE exception_id = :id
                        """)
                .param("id", exceptionId)
                .query(Long.class)
                .single();
    }

    long storedVersion(UUID exceptionId) {
        return jdbc.sql("SELECT version FROM schedule_exception WHERE id = :id")
                .param("id", exceptionId)
                .query(Long.class)
                .single();
    }

    static OffsetDateTime databaseNow() {
        return NOW.atOffset(ZoneOffset.UTC);
    }

    record Fixture(UUID businessId, UUID userId, AuthenticatedBusinessContext context) {
    }

    record TestContext(UUID userId, UUID businessId) implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.ofNullable(businessId);
        }
    }
}
