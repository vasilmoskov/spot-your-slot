package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess.StaffMemberReference;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffMemberReferenceAccessIntegrationTests extends PostgresIntegrationTest {
    @Autowired StaffMemberReferenceAccess references;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void requiresAnActiveCallerTransactionSoTheLockLivesUntilItEnds() {
        UUID businessId = business();
        UUID staffMemberId = staffMember(businessId, true);

        assertThatThrownBy(() -> references.lockReference(businessId, staffMemberId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void returnsOnlyIdAndActiveStateForSameBusinessStaffMembers() {
        UUID businessId = business();
        UUID active = staffMember(businessId, true);
        UUID inactive = staffMember(businessId, false);

        assertThat(lock(businessId, active))
                .contains(new StaffMemberReference(active, true));
        assertThat(lock(businessId, inactive))
                .contains(new StaffMemberReference(inactive, false));
        assertThat(StaffMemberReference.class.getRecordComponents()).hasSize(2);
    }

    @Test
    void missingAndForeignStaffMembersAreIndistinguishable() {
        UUID businessA = business();
        UUID businessB = business();
        UUID inA = staffMember(businessA, true);

        assertThat(lock(businessB, inA)).isEmpty();
        assertThat(lock(businessA, UUID.randomUUID())).isEmpty();
    }

    private Optional<StaffMemberReference> lock(UUID businessId, UUID staffMemberId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> references.lockReference(businessId, staffMemberId));
    }

    private UUID business() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (:id,:slug,'Reference Test','OTHER','ACTIVE','Europe/Sofia',
                                :now,:now)
                        """)
                .param("id", id)
                .param("slug", "staff-reference-" + id)
                .param("now", now())
                .update();
        return id;
    }

    private UUID staffMember(UUID businessId, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,contact_email,contact_phone,
                            active,version,created_at,updated_at)
                        VALUES (:id,:businessId,'Reference Staff',NULL,NULL,:active,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("active", active)
                .param("now", now())
                .update();
        return id;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.of(2026, 9, 29, 8, 0, 0, 0, ZoneOffset.UTC);
    }
}
