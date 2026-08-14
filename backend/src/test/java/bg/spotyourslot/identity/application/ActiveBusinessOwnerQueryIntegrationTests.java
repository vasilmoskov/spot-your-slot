package bg.spotyourslot.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.identity.ActiveBusinessOwnerQuery;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements =
                "TRUNCATE user_session,password_reset,owner_invitation,membership,platform_role,app_user,business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ActiveBusinessOwnerQueryIntegrationTests extends PostgresIntegrationTest {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(10);
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 14, 10, 0, 0, 0, ZoneOffset.UTC);

    @Autowired ActiveBusinessOwnerQuery query;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void returnsTrueForActiveOwnerOfRequestedBusiness() {
        UUID businessId = business("active-owner-business");
        membership(businessId, user("active-owner@example.invalid"), "BUSINESS_OWNER", true);

        assertThat(inTransaction(() -> query.hasActiveBusinessOwner(businessId))).isTrue();
    }

    @Test
    void returnsFalseWhenBusinessHasNoMembership() {
        UUID businessId = business("no-membership-business");

        assertThat(inTransaction(() -> query.hasActiveBusinessOwner(businessId))).isFalse();
    }

    @Test
    void returnsFalseForInactiveOwner() {
        UUID businessId = business("inactive-owner-business");
        membership(businessId, user("inactive-owner@example.invalid"), "BUSINESS_OWNER", false);

        assertThat(inTransaction(() -> query.hasActiveBusinessOwner(businessId))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void returnsFalseForActiveNonOwnerRole(String role) {
        UUID businessId = business("non-owner-" + role.toLowerCase());
        membership(
                businessId,
                user(role.toLowerCase() + "@example.invalid"),
                role,
                true);

        assertThat(inTransaction(() -> query.hasActiveBusinessOwner(businessId))).isFalse();
    }

    @Test
    void returnsFalseWhenOwnerBelongsToAnotherBusiness() {
        UUID requestedBusiness = business("requested-business");
        UUID otherBusiness = business("other-business");
        membership(
                otherBusiness,
                user("other-business-owner@example.invalid"),
                "BUSINESS_OWNER",
                true);

        assertThat(inTransaction(() -> query.hasActiveBusinessOwner(requestedBusiness))).isFalse();
    }

    @Test
    void returnsTrueWithMultipleActiveOwners() {
        UUID businessId = business("multiple-owners-business");
        membership(
                businessId,
                user("multiple-owner-one@example.invalid"),
                "BUSINESS_OWNER",
                true);
        membership(
                businessId,
                user("multiple-owner-two@example.invalid"),
                "BUSINESS_OWNER",
                true);

        assertThat(inTransaction(() -> query.hasActiveBusinessOwner(businessId))).isTrue();
    }

    @Test
    void findsOwnerAmongUnrelatedInactiveAndNonOwnerMemberships() {
        UUID businessId = business("mixed-memberships-business");
        membership(
                businessId,
                user("mixed-inactive@example.invalid"),
                "BUSINESS_OWNER",
                false);
        membership(
                businessId,
                user("mixed-manager@example.invalid"),
                "MANAGER",
                true);
        membership(
                businessId,
                user("mixed-owner@example.invalid"),
                "BUSINESS_OWNER",
                true);

        assertThat(inTransaction(() -> query.hasActiveBusinessOwner(businessId))).isTrue();
    }

    @Test
    void rejectsNullBusinessIdInsideExistingTransaction() {
        assertThatThrownBy(() -> inTransaction(() -> query.hasActiveBusinessOwner(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Business ID is required");
    }

    @Test
    void rejectsInvocationWithoutExistingTransaction() {
        UUID businessId = business("mandatory-transaction-business");

        assertThatThrownBy(() -> query.hasActiveBusinessOwner(businessId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void shareLockPreventsOwnerDeactivationUntilQueryTransactionCompletes() {
        UUID businessId = business("locked-owner-business");
        UUID membershipId = membership(
                businessId,
                user("locked-owner@example.invalid"),
                "BUSINESS_OWNER",
                true);
        CountDownLatch ownerLocked = new CountDownLatch(1);
        CountDownLatch releaseOwnerLock = new CountDownLatch(1);
        CountDownLatch updaterReady = new CountDownLatch(1);
        var queryBackendPid = new AtomicInteger();
        var updaterBackendPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CompletableFuture<Boolean> ownerQuery = null;
        CompletableFuture<Integer> deactivation = null;

        try {
            ownerQuery = CompletableFuture.supplyAsync(
                    () -> holdOwnerLock(
                            businessId,
                            queryBackendPid,
                            ownerLocked,
                            releaseOwnerLock),
                    executor);
            await(ownerLocked, "owner query did not acquire its lock");

            deactivation = CompletableFuture.supplyAsync(
                    () -> deactivateOwner(membershipId, updaterBackendPid, updaterReady),
                    executor);
            await(updaterReady, "owner deactivation did not reach its update");

            LockWait lockWait = awaitLockWait(updaterBackendPid.get());
            assertThat(lockWait.waitEventType()).isEqualTo("Lock");
            assertThat(lockWait.waitEvent()).isNotBlank();
            assertThat(active(membershipId)).isTrue();
            assertThat(deactivation).isNotCompleted();

            releaseOwnerLock.countDown();

            assertThat(completed(ownerQuery)).isTrue();
            assertThat(completed(deactivation)).isEqualTo(1);
            assertThat(active(membershipId)).isFalse();
            assertThat(queryBackendPid.get()).isPositive();
            assertThat(updaterBackendPid.get()).isPositive();
            assertThat(queryBackendPid.get()).isNotEqualTo(updaterBackendPid.get());

            System.out.printf(
                    "ActiveBusinessOwnerQuery lock evidence: queryPid=%d, updaterPid=%d, waitEventType=%s, waitEvent=%s%n",
                    queryBackendPid.get(),
                    updaterBackendPid.get(),
                    lockWait.waitEventType(),
                    lockWait.waitEvent());
        } finally {
            releaseOwnerLock.countDown();
            cancelIfIncomplete(ownerQuery);
            cancelIfIncomplete(deactivation);
            executor.shutdownNow();
            awaitTermination(executor);
        }
    }

    private boolean holdOwnerLock(
            UUID businessId,
            AtomicInteger backendPid,
            CountDownLatch ownerLocked,
            CountDownLatch releaseOwnerLock) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            backendPid.set(backendPid());
            boolean result = query.hasActiveBusinessOwner(businessId);
            ownerLocked.countDown();
            await(releaseOwnerLock, "owner query lock was not released");
            return result;
        });
    }

    private int deactivateOwner(
            UUID membershipId,
            AtomicInteger backendPid,
            CountDownLatch updaterReady) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            backendPid.set(backendPid());
            updaterReady.countDown();
            return jdbc.sql("UPDATE membership SET active = false WHERE id = :membershipId")
                    .param("membershipId", membershipId)
                    .update();
        });
    }

    private LockWait awaitLockWait(int backendPid) {
        long deadline = System.nanoTime() + COORDINATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var wait = jdbc.sql("""
                            SELECT wait_event_type, wait_event
                            FROM pg_stat_activity
                            WHERE pid = :backendPid
                              AND wait_event_type = 'Lock'
                            """)
                    .param("backendPid", backendPid)
                    .query((resultSet, rowNumber) -> new LockWait(
                            resultSet.getString("wait_event_type"),
                            resultSet.getString("wait_event")))
                    .optional();
            if (wait.isPresent()) {
                return wait.orElseThrow();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("updater did not enter a PostgreSQL lock wait");
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()")
                .query(Integer.class)
                .single();
    }

    private boolean active(UUID membershipId) {
        return jdbc.sql("SELECT active FROM membership WHERE id = :membershipId")
                .param("membershipId", membershipId)
                .query(Boolean.class)
                .single();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private UUID business(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, :displayName, 'OTHER', 'DRAFT', 'Europe/Sofia',
                            :now, :now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("displayName", "Business " + slug)
                .param("now", NOW)
                .update();
        return id;
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id, normalized_email, display_name, password_hash,
                            password_changed_at, created_at, updated_at)
                        VALUES (
                            :id, :email, :displayName, :passwordHash,
                            :now, :now, :now)
                        """)
                .param("id", id)
                .param("email", email)
                .param("displayName", "Test User")
                .param("passwordHash", "test-password-hash")
                .param("now", NOW)
                .update();
        return id;
    }

    private UUID membership(UUID businessId, UUID userId, String role, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO membership(
                            id, business_id, user_id, role, active, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :userId, :role, :active, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("userId", userId)
                .param("role", role)
                .param("active", active)
                .param("now", NOW)
                .update();
        return id;
    }

    private void await(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(failureMessage);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private <T> T completed(CompletableFuture<T> future) {
        try {
            return future.get(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError(exception);
        }
    }

    private void cancelIfIncomplete(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private void awaitTermination(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(
                    COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("concurrency executor did not terminate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record LockWait(String waitEventType, String waitEvent) {
    }
}
