package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.identity.application.AuthenticationService;
import bg.spotyourslot.identity.application.DevelopmentMailbox;
import bg.spotyourslot.identity.application.IdentityRecords;
import bg.spotyourslot.identity.application.InvitationService;
import bg.spotyourslot.identity.application.RecoveryService;
import bg.spotyourslot.identity.domain.TokenCodec;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;

@Sql(
        statements =
                "TRUNCATE user_session,password_reset,owner_invitation,membership,platform_role,app_user,business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class IdentityLifecycleIntegrationTests extends PostgresIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired InvitationService invitations;
    @Autowired RecoveryService recovery;
    @Autowired DevelopmentMailbox mailbox;
    @Autowired AuthenticationService authentication;
    @Autowired TokenCodec tokens;

    private UUID admin;
    private UUID business;
    private OffsetDateTime now;

    @BeforeEach
    void fixture() {
        now = OffsetDateTime.now(ZoneOffset.UTC);
        admin = user("admin@example.invalid", "Admin", "administrator passphrase");
        business = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,'business-a','Business A','OTHER','ACTIVE','Europe/Sofia',:now,:now)
                        """)
                .param("id", business)
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',:now)")
                .param("id", admin)
                .param("now", now)
                .update();
    }

    @Test
    void invitationIsHashOnlySingleUseAndCreatesOwner() {
        invitations.invite(business, "owner@example.invalid", admin);
        String token = token(last("OWNER_INVITATION", "owner@example.invalid").url());
        assertThat(jdbc.sql("SELECT token_hash FROM owner_invitation").query(String.class).single())
                .doesNotContain(token);
        invitations.accept(token, "Owner", "owner secure passphrase");
        assertThat(jdbc.sql("SELECT role FROM membership").query(String.class).single())
                .isEqualTo("BUSINESS_OWNER");
        assertThatThrownBy(() -> invitations.accept(token, "Owner", "owner secure passphrase"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invitationExpirationInvalidationAndReplacementAreEnforced() {
        invitations.invite(business, "expired@example.invalid", admin);
        String expired = token(last("OWNER_INVITATION", "expired@example.invalid").url());
        jdbc.sql("UPDATE owner_invitation SET expires_at=now() WHERE token_hash=:hash")
                .param("hash", tokens.hash(expired))
                .update();
        assertThatThrownBy(() -> invitations.accept(expired, "Owner", "owner secure passphrase"))
                .isInstanceOf(IllegalArgumentException.class);

        invitations.invite(business, "replacement@example.invalid", admin);
        String first = token(last("OWNER_INVITATION", "replacement@example.invalid").url());
        invitations.invite(business, "replacement@example.invalid", admin);
        String second = token(last("OWNER_INVITATION", "replacement@example.invalid").url());
        assertThatThrownBy(() -> invitations.accept(first, "Owner", "owner secure passphrase"))
                .isInstanceOf(IllegalArgumentException.class);
        invitations.accept(second, "Owner", "owner secure passphrase");
    }

    @Test
    void resetIsHashOnlySingleUseAndRevokesAllSessions() {
        user("owner@example.invalid", "Owner", "original secure password");
        authentication.login("owner@example.invalid", "original secure password");
        authentication.login("owner@example.invalid", "original secure password");
        recovery.request("owner@example.invalid");
        String token = token(last("PASSWORD_RESET", "owner@example.invalid").url());
        assertThat(jdbc.sql("SELECT token_hash FROM password_reset").query(String.class).single())
                .doesNotContain(token);
        recovery.reset(token, "replacement secure password");
        assertThat(jdbc.sql("SELECT count(*) FROM user_session WHERE revoked_at IS NOT NULL")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThatThrownBy(() -> recovery.reset(token, "another secure password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordChangeKeepsCurrentSessionAndRevokesEveryOtherSession() {
        UUID userId = user("change@example.invalid", "Owner", "original secure password");
        var currentLogin = authentication.login(
                "change@example.invalid", "original secure password");
        var otherLogin = authentication.login(
                "change@example.invalid", "original secure password");
        var currentSession = authentication.authenticate(currentLogin.token()).orElseThrow();
        var otherSession = authentication.authenticate(otherLogin.token()).orElseThrow();

        authentication.changePassword(
                currentSession.id(),
                userId,
                "original secure password",
                "replacement secure password");

        assertThat(authentication.authenticate(currentLogin.token()).orElseThrow().revoked()).isFalse();
        assertThat(authentication.authenticate(otherLogin.token()).orElseThrow().revoked()).isTrue();
        assertThatThrownBy(() -> authentication.login(
                        "change@example.invalid", "original secure password"))
                .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        assertThatCode(() -> authentication.login(
                        "change@example.invalid", "replacement secure password"))
                .doesNotThrowAnyException();
        assertThat(otherSession.id()).isNotEqualTo(currentSession.id());
    }

    @Test
    void resetExpirationReplacementAndReplayAreEnforced() {
        user("reset@example.invalid", "Reset", "original secure password");
        recovery.request("reset@example.invalid");
        String expired = token(last("PASSWORD_RESET", "reset@example.invalid").url());
        jdbc.sql("UPDATE password_reset SET expires_at=now() WHERE token_hash=:hash")
                .param("hash", tokens.hash(expired))
                .update();
        assertThatThrownBy(() -> recovery.reset(expired, "replacement secure password"))
                .isInstanceOf(IllegalArgumentException.class);

        recovery.request("reset@example.invalid");
        String first = token(last("PASSWORD_RESET", "reset@example.invalid").url());
        recovery.request("reset@example.invalid");
        String second = token(last("PASSWORD_RESET", "reset@example.invalid").url());
        assertThatThrownBy(() -> recovery.reset(first, "replacement secure password"))
                .isInstanceOf(IllegalArgumentException.class);
        recovery.reset(second, "replacement secure password");
        assertThatThrownBy(() -> recovery.reset(second, "replacement secure password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentInvitationIssuanceLeavesExactlyOneActiveGeneration() {
        runTogether(
                () -> invitations.invite(business, "race@example.invalid", admin),
                () -> invitations.invite(business, "race@example.invalid", admin));
        assertThat(activeInvitations("race@example.invalid")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM owner_invitation WHERE normalized_email=:email")
                        .param("email", "race@example.invalid")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void concurrentResetIssuanceLeavesExactlyOneActiveGeneration() {
        UUID user = user("reset-race@example.invalid", "Reset", "original secure password");
        runTogether(
                () -> recovery.request("reset-race@example.invalid"),
                () -> recovery.request("reset-race@example.invalid"));
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM password_reset
                                WHERE user_id=:user AND consumed_at IS NULL AND invalidated_at IS NULL
                                """)
                        .param("user", user)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void concurrentInvitationConsumptionSucceedsExactlyOnce() {
        invitations.invite(business, "consume-race@example.invalid", admin);
        String token = token(last("OWNER_INVITATION", "consume-race@example.invalid").url());
        List<Boolean> outcomes = runTogetherWithOutcomes(
                () -> invitations.accept(token, "Owner", "owner secure passphrase"),
                () -> invitations.accept(token, "Owner", "owner secure passphrase"));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void concurrentResetConsumptionSucceedsExactlyOnce() {
        user("consume-reset@example.invalid", "Reset", "original secure password");
        recovery.request("consume-reset@example.invalid");
        String token = token(last("PASSWORD_RESET", "consume-reset@example.invalid").url());
        List<Boolean> outcomes = runTogetherWithOutcomes(
                () -> recovery.reset(token, "replacement secure password"),
                () -> recovery.reset(token, "replacement secure password"));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void forgotPasswordDoesNotRevealUnknownAccount() {
        assertThatCode(() -> recovery.request("unknown@example.invalid")).doesNotThrowAnyException();
    }

    private int activeInvitations(String email) {
        return jdbc.sql("""
                        SELECT count(*) FROM owner_invitation
                        WHERE normalized_email=:email AND consumed_at IS NULL AND invalidated_at IS NULL
                        """)
                .param("email", email)
                .query(Integer.class)
                .single();
    }

    private void runTogether(Runnable first, Runnable second) {
        List<Boolean> outcomes = runTogetherWithOutcomes(first, second);
        assertThat(outcomes).containsOnly(true);
    }

    private List<Boolean> runTogetherWithOutcomes(Runnable first, Runnable second) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Boolean> firstResult = submitted(executor, ready, start, first);
            CompletableFuture<Boolean> secondResult = submitted(executor, ready, start, second);
            ready.await();
            start.countDown();
            return List.of(firstResult.join(), secondResult.join());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private CompletableFuture<Boolean> submitted(
            ExecutorService executor, CountDownLatch ready, CountDownLatch start, Runnable action) {
        Supplier<Boolean> task = () -> {
            ready.countDown();
            try {
                start.await();
                action.run();
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            } catch (RuntimeException exception) {
                return false;
            }
        };
        return CompletableFuture.supplyAsync(task, executor);
    }

    private UUID user(String email, String name, String password) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,:name,:hash,:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", email)
                .param("name", name)
                .param("hash", encoder.encode(password))
                .param("now", now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now)
                .update();
        return id;
    }

    private IdentityRecords.DeliveredLink last(String kind, String recipient) {
        return mailbox.messages().stream()
                .filter(message -> message.kind().equals(kind))
                .filter(message -> message.recipient().equals(recipient))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private String token(String url) {
        String query = URI.create(url).getQuery();
        return query.substring(query.indexOf('=') + 1);
    }
}
