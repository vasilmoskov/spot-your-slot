package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdentitySchemaIntegrationTests extends PostgresIntegrationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void flywayPreservesPhaseTwoTablesAndExcludesUnimplementedDomainTables() {
        var tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema='public' ORDER BY table_name")
                .query(String.class)
                .list();

        assertThat(tables)
                .contains(
                        "business",
                        "app_user",
                        "membership",
                        "platform_role",
                        "user_session",
                        "owner_invitation",
                        "password_reset")
                .doesNotContain("appointment", "customer");
    }

    @Test
    void membershipAcceptsAllRolesAndPreventsDuplicates() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID business = UUID.randomUUID();
        jdbc.sql("INSERT INTO business(id,slug,display_name,business_type,status,timezone,created_at,updated_at) VALUES (:id,:slug,'Business A','OTHER','ACTIVE','Europe/Sofia',:now,:now)")
                .param("id", business)
                .param("slug", "business-" + business.toString().substring(0, 8))
                .param("now", now)
                .update();

        for (String role : new String[] {"BUSINESS_OWNER", "MANAGER", "STAFF"}) {
            UUID user = UUID.randomUUID();
            jdbc.sql("INSERT INTO app_user(id,normalized_email,display_name,password_hash,password_changed_at,created_at,updated_at) VALUES (:id,:email,'Test User','hash',:now,:now,:now)")
                    .param("id", user)
                    .param("email", user + "@example.invalid")
                    .param("now", now)
                    .update();
            jdbc.sql("INSERT INTO membership(id,business_id,user_id,role,created_at,updated_at) VALUES (:id,:business,:user,:role,:now,:now)")
                    .param("id", UUID.randomUUID())
                    .param("business", business)
                    .param("user", user)
                    .param("role", role)
                    .param("now", now)
                    .update();

            assertThatThrownBy(() -> jdbc.sql("INSERT INTO membership(id,business_id,user_id,role,created_at,updated_at) VALUES (:id,:business,:user,:role,:now,:now)")
                            .param("id", UUID.randomUUID())
                            .param("business", business)
                            .param("user", user)
                            .param("role", role)
                            .param("now", now)
                            .update())
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void normalizedEmailIsUnique() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID first = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user(id,normalized_email,display_name,password_hash,password_changed_at,created_at,updated_at) VALUES (:id,'unique@example.invalid','A','hash',:now,:now,:now)")
                .param("id", first)
                .param("now", now)
                .update();

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_user(id,normalized_email,display_name,password_hash,password_changed_at,created_at,updated_at) VALUES (:id,'unique@example.invalid','B','hash',:now,:now,:now)")
                        .param("id", UUID.randomUUID())
                        .param("now", now)
                        .update())
                .isInstanceOf(Exception.class);
    }
}
