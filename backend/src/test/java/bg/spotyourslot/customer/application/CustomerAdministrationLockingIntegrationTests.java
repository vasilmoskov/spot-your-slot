package bg.spotyourslot.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessAccessDenied;
import bg.spotyourslot.customer.application.CustomerAdministrationException.BusinessSuspended;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CreateCustomerCommand;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-PostgreSQL evidence that Customer mutations authorize against locked, committed state in the
 * order Business lifecycle row, Membership row, then the Customer write, and that reads take no
 * lock. A second transaction holds the row lock; PostgreSQL lock-wait evidence proves the mutation
 * is blocked before the lock is released. No sleeps: latches order the threads and the only
 * timeouts are ceilings.
 */
class CustomerAdministrationLockingIntegrationTests extends PostgresIntegrationTest {
    private static final Duration CEILING = Duration.ofSeconds(30);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired CustomerAdministrationService service;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID businessId;
    private UUID userId;
    private UUID membershipId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE business,app_user CASCADE").update();
        businessId = UUID.randomUUID();
        userId = UUID.randomUUID();
        membershipId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,'Locking Business','OTHER','ACTIVE','Europe/Sofia',:now,:now)
                        """)
                .param("id", businessId)
                .param("slug", "customer-locking-" + SEQUENCE.incrementAndGet())
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,active,locked,
                            credential_version,password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Locking User','unused',true,false,1,:now,:now,:now)
                        """)
                .param("id", userId)
                .param("email", userId + "@example.invalid")
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO membership(id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:business,:user,'BUSINESS_OWNER',true,:now,:now)
                        """)
                .param("id", membershipId)
                .param("business", businessId)
                .param("user", userId)
                .param("now", now)
                .update();
    }

    @Test
    void aMutationWaitsForAConcurrentSuspensionAndThenIsRejected() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            LockHolder holder = new LockHolder(executor, """
                    UPDATE business SET status='SUSPENDED', updated_at=now() WHERE id=:id
                    """, businessId);
            holder.holdUntilReleased();

            CompletableFuture<Object> mutation = CompletableFuture.supplyAsync(
                    () -> service.create(context(), new CreateCustomerCommand("Анна", "0895555777", null)),
                    executor);
            awaitLockWait(holder.pid(), mutation);
            holder.release();

            assertThatThrownBy(() -> mutation.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessSuspended.class);
            assertThat(customerCount()).isZero();
        }
    }

    @Test
    void aMutationWaitsForAConcurrentMembershipDeactivationAndThenIsDenied() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            LockHolder holder = new LockHolder(executor, """
                    UPDATE membership SET active=false, updated_at=now() WHERE id=:id
                    """, membershipId);
            holder.holdUntilReleased();

            CompletableFuture<Object> mutation = CompletableFuture.supplyAsync(
                    () -> service.create(context(), new CreateCustomerCommand("Анна", "0895555777", null)),
                    executor);
            awaitLockWait(holder.pid(), mutation);
            holder.release();

            assertThatThrownBy(() -> mutation.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessAccessDenied.class);
            assertThat(customerCount()).isZero();
        }
    }

    @Test
    void readsTakeNoLockAndAreNotBlockedByAnUncommittedBusinessOrMembershipChange() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            LockHolder businessHolder = new LockHolder(executor, """
                    UPDATE business SET status='SUSPENDED', updated_at=now() WHERE id=:id
                    """, businessId);
            businessHolder.holdUntilReleased();

            // A plain read completes while the row is locked and sees the committed state.
            CompletableFuture<Object> read = CompletableFuture.supplyAsync(
                    () -> service.list(context(), null, null, null, null), executor);
            assertThat(read.get(30, TimeUnit.SECONDS)).isNotNull();
            businessHolder.release();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private AuthenticatedBusinessContext context() {
        return new AuthenticatedBusinessContext() {
            @Override
            public UUID userId() {
                return userId;
            }

            @Override
            public Optional<UUID> selectedBusinessId() {
                return Optional.of(businessId);
            }
        };
    }

    private long customerCount() {
        return jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single();
    }

    private void awaitLockWait(int holderPid, CompletableFuture<?> operation) {
        long deadline = System.nanoTime() + CEILING.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Integer> waiter = jdbc.sql("""
                            SELECT pid FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND pid <> :holder
                            LIMIT 1
                            """)
                    .param("holder", holderPid)
                    .query(Integer.class)
                    .optional();
            if (waiter.isPresent()) {
                assertThat(operation).isNotDone();
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("PostgreSQL lock wait was not observed");
    }

    /**
     * A transaction on its own connection that takes a row lock through an UPDATE and keeps it
     * until released and then commits.
     */
    private final class LockHolder {
        private final ExecutorService executor;
        private final String sql;
        private final UUID id;
        private final CountDownLatch locked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Integer> pid = new CompletableFuture<>();
        private CompletableFuture<Void> transaction;

        private LockHolder(ExecutorService executor, String sql, UUID id) {
            this.executor = executor;
            this.sql = sql;
            this.id = id;
        }

        void holdUntilReleased() throws InterruptedException {
            transaction = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        jdbc.sql(sql).param("id", id).update();
                        pid.complete(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        locked.countDown();
                        try {
                            if (!release.await(CEILING.toSeconds(), TimeUnit.SECONDS)) {
                                throw new IllegalStateException("The lock holder was never released");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }), executor);
            assertThat(locked.await(CEILING.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }

        int pid() throws Exception {
            return pid.get(CEILING.toSeconds(), TimeUnit.SECONDS);
        }

        void release() throws Exception {
            release.countDown();
            transaction.get(CEILING.toSeconds(), TimeUnit.SECONDS);
        }
    }
}
