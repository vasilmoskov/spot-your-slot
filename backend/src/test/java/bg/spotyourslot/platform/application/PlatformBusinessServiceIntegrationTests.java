package bg.spotyourslot.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.identity.ActiveBusinessOwnerQuery;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.platform.MissingActiveOwner;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@Import(PlatformBusinessServiceIntegrationTests.PlatformTestConfiguration.class)
@Sql(
        statements =
                "TRUNCATE user_session,password_reset,owner_invitation,membership,platform_role,app_user,business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PlatformBusinessServiceIntegrationTests extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final OffsetDateTime DATABASE_NOW = NOW.atOffset(ZoneOffset.UTC);

    @Autowired PlatformBusinessService service;
    @Autowired RecordingActiveBusinessOwnerQuery activeOwners;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void resetOwnerQueryCount() {
        activeOwners.reset();
    }

    @Test
    void activationWithoutOwnerFailsAndLeavesDraftUnchanged() {
        var draft = service.create(createCommand("missing-owner"));

        assertThatThrownBy(() -> service.activateDraft(draft.id(), draft.version()))
                .isInstanceOf(MissingActiveOwner.class);
        assertThat(service.get(draft.id()).status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(activeOwners.invocations()).isEqualTo(1);
    }

    @Test
    void activationWithActiveOwnerSucceedsInsidePlatformTransaction() {
        var draft = service.create(createCommand("active-owner"));
        ownerMembership(draft.id(), true, "active-owner@example.invalid");

        var active = service.activateDraft(draft.id(), draft.version());

        assertThat(active.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(active.version()).isEqualTo(1);
        assertThat(activeOwners.invocations()).isEqualTo(1);
    }

    @Test
    void inactiveOwnerDoesNotQualify() {
        var draft = service.create(createCommand("inactive-owner"));
        ownerMembership(draft.id(), false, "inactive-owner@example.invalid");

        assertThatThrownBy(() -> service.activateDraft(draft.id(), draft.version()))
                .isInstanceOf(MissingActiveOwner.class);
        assertThat(service.get(draft.id()).status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(activeOwners.invocations()).isEqualTo(1);
    }

    @Test
    void staleVersionIsRejectedBeforeOwnerLookup() {
        var draft = service.create(createCommand("stale-before-owner"));
        ownerMembership(draft.id(), true, "stale-owner@example.invalid");

        assertThatThrownBy(() -> service.activateDraft(draft.id(), draft.version() + 1))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThat(activeOwners.invocations()).isZero();
    }

    @Test
    void wrongLifecycleStatusIsRejectedBeforeOwnerLookup() {
        var draft = service.create(createCommand("wrong-status"));
        setStatusAndVersion(draft.id(), "ACTIVE", draft.version());

        assertThatThrownBy(() -> service.activateDraft(draft.id(), draft.version()))
                .isInstanceOf(InvalidLifecycleTransition.class);
        assertThat(activeOwners.invocations()).isZero();
    }

    @Test
    void ownerFromAnotherBusinessCannotAuthorizeActivation() {
        var requested = service.create(createCommand("requested-business"));
        var other = service.create(createCommand("owner-business"));
        ownerMembership(other.id(), true, "other-owner@example.invalid");

        assertThatThrownBy(() ->
                        service.activateDraft(requested.id(), requested.version()))
                .isInstanceOf(MissingActiveOwner.class);
        assertThat(service.get(requested.id()).status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(activeOwners.invocations()).isEqualTo(1);
    }

    @Test
    void suspensionAndReactivationDoNotQueryOwnerReadiness() {
        var draft = service.create(createCommand("later-lifecycle"));
        setStatusAndVersion(draft.id(), "ACTIVE", 4);

        var suspended = service.suspendActive(draft.id(), 4);
        var active = service.reactivateSuspended(suspended.id(), suspended.version());

        assertThat(suspended.status()).isEqualTo(BusinessStatus.SUSPENDED);
        assertThat(suspended.version()).isEqualTo(5);
        assertThat(active.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(active.version()).isEqualTo(6);
        assertThat(activeOwners.invocations()).isZero();
    }

    private CreateBusinessCommand createCommand(String slug) {
        return new CreateBusinessCommand(
                slug,
                "Business " + slug,
                BusinessType.OTHER,
                "Europe/Sofia",
                null,
                null,
                null,
                null);
    }

    private void ownerMembership(UUID businessId, boolean active, String email) {
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id, normalized_email, display_name, password_hash,
                            password_changed_at, created_at, updated_at)
                        VALUES (
                            :id, :email, 'Owner', 'test-password-hash',
                            :now, :now, :now)
                        """)
                .param("id", userId)
                .param("email", email)
                .param("now", DATABASE_NOW)
                .update();
        jdbc.sql("""
                        INSERT INTO membership(
                            id, business_id, user_id, role, active, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :userId, 'BUSINESS_OWNER', :active, :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .param("userId", userId)
                .param("active", active)
                .param("now", DATABASE_NOW)
                .update();
    }

    private void setStatusAndVersion(UUID businessId, String status, long version) {
        jdbc.sql("""
                        UPDATE business
                        SET status = :status, version = :version
                        WHERE id = :businessId
                        """)
                .param("status", status)
                .param("version", version)
                .param("businessId", businessId)
                .update();
    }

    @TestConfiguration
    static class PlatformTestConfiguration {
        @Bean
        @Primary
        Clock fixedPlatformBusinessClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        RecordingActiveBusinessOwnerQuery recordingActiveBusinessOwnerQuery(
                @Qualifier("activeBusinessOwnerQueryService")
                        ActiveBusinessOwnerQuery delegate) {
            return new RecordingActiveBusinessOwnerQuery(delegate);
        }
    }

    static final class RecordingActiveBusinessOwnerQuery
            implements ActiveBusinessOwnerQuery {
        private final ActiveBusinessOwnerQuery delegate;
        private final AtomicInteger invocations = new AtomicInteger();

        private RecordingActiveBusinessOwnerQuery(ActiveBusinessOwnerQuery delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasActiveBusinessOwner(UUID businessId) {
            invocations.incrementAndGet();
            return delegate.hasActiveBusinessOwner(businessId);
        }

        int invocations() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
        }
    }
}
