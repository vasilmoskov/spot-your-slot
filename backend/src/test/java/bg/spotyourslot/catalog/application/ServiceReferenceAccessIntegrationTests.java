package bg.spotyourslot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.catalog.ServiceReferenceAccess;
import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReference;
import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReferenceFailure;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ServiceReferenceAccessIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired ServiceReferenceAccess references;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void resolvesOnlyTenantReferencesInCatalogOrderIncludingInactiveServices() {
        UUID businessId = business();
        UUID foreignBusiness = business();
        UUID second = service(businessId, UUID.randomUUID(), "Б услуга", true);
        UUID firstInactive = service(businessId, UUID.randomUUID(), "А услуга", false);
        UUID foreign = service(foreignBusiness, UUID.randomUUID(), "Foreign", true);

        List<ServiceReference> result = transaction().execute(status ->
                references.findReferences(
                        businessId, List.of(second, foreign, firstInactive)));

        assertThat(result).containsExactly(
                new ServiceReference(firstInactive, "А услуга", false),
                new ServiceReference(second, "Б услуга", true));
        List<ServiceReference> empty = transaction().execute(status ->
                references.findReferences(businessId, List.of()));
        assertThat(empty).isEmpty();
    }

    @Test
    void sharedReferenceLockBlocksAConcurrentServiceMutation() {
        UUID businessId = business();
        UUID serviceId = service(businessId, UUID.randomUUID(), "Locked", true);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger referencePid = new AtomicInteger();
        AtomicInteger mutationPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<List<ServiceReference>> holder = CompletableFuture.supplyAsync(
                    () -> transaction().execute(status -> {
                        referencePid.set(backendPid());
                        List<ServiceReference> found = references.lockReferences(
                                businessId, List.of(serviceId));
                        locked.countDown();
                        await(release, "reference lock was not released");
                        return found;
                    }),
                    executor);
            await(locked, "reference lock was not acquired");

            CompletableFuture<Integer> mutation = CompletableFuture.supplyAsync(
                    () -> transaction().execute(status -> {
                        mutationPid.set(backendPid());
                        return jdbc.sql("""
                                        UPDATE service
                                        SET active=false, version=version+1
                                        WHERE business_id=:businessId AND id=:serviceId
                                        """)
                                .param("businessId", businessId)
                                .param("serviceId", serviceId)
                                .update();
                    }),
                    executor);

            assertLockWait(mutationPid);
            assertThat(mutation).isNotCompleted();
            assertThat(referencePid.get()).isNotEqualTo(mutationPid.get());
            release.countDown();

            assertThat(completed(holder))
                    .containsExactly(new ServiceReference(serviceId, "Locked", true));
            assertThat(completed(mutation)).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void locksRequestedServicesInPostgresqlUuidOrder() {
        UUID businessId = business();
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        service(businessId, lower, "Lower", true);
        service(businessId, higher, "Higher", true);
        CountDownLatch lowerLocked = new CountDownLatch(1);
        CountDownLatch releaseLower = new CountDownLatch(1);
        AtomicInteger referencePid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            CompletableFuture<Integer> lowerHolder = CompletableFuture.supplyAsync(
                    () -> transaction().execute(status -> {
                        int pid = backendPid();
                        int updated = jdbc.sql("""
                                        UPDATE service SET updated_at=updated_at
                                        WHERE business_id=:businessId AND id=:serviceId
                                        """)
                                .param("businessId", businessId)
                                .param("serviceId", lower)
                                .update();
                        lowerLocked.countDown();
                        await(releaseLower, "lower UUID lock was not released");
                        return pid + updated;
                    }),
                    executor);
            await(lowerLocked, "lower UUID was not locked");

            CompletableFuture<List<ServiceReference>> orderedLock =
                    CompletableFuture.supplyAsync(
                            () -> transaction().execute(status -> {
                                referencePid.set(backendPid());
                                return references.lockReferences(
                                        businessId, List.of(higher, lower));
                            }),
                            executor);
            assertLockWait(referencePid);

            CompletableFuture<Integer> higherMutation = CompletableFuture.supplyAsync(
                    () -> transaction().execute(status -> jdbc.sql("""
                                    UPDATE service SET active=false
                                    WHERE business_id=:businessId AND id=:serviceId
                                    """)
                            .param("businessId", businessId)
                            .param("serviceId", higher)
                            .update()),
                    executor);

            assertThat(completed(higherMutation)).isEqualTo(1);
            assertThat(orderedLock).isNotCompleted();
            releaseLower.countDown();

            assertThat(completed(orderedLock)).extracting(ServiceReference::id)
                    .containsExactly(lower, higher);
            assertThat(completed(lowerHolder)).isPositive();
        } finally {
            releaseLower.countDown();
        }
    }

    @Test
    void sanitizesCatalogPersistenceFailureAndPreservesItsCause() {
        UUID businessId = business();
        UUID serviceId = service(businessId, UUID.randomUUID(), "Failure", true);
        jdbc.sql("ALTER TABLE service RENAME TO unavailable_service").update();

        try {
            assertThatThrownBy(() -> transaction().execute(status ->
                            references.findReferences(businessId, List.of(serviceId))))
                    .isInstanceOf(ServiceReferenceFailure.class)
                    .hasMessage("Service reference access failed")
                    .hasCauseInstanceOf(RuntimeException.class)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain("unavailable_service")
                            .doesNotContain("relation"));
        } finally {
            jdbc.sql("ALTER TABLE unavailable_service RENAME TO service").update();
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void assertLockWait(AtomicInteger backendPid) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            int pid = backendPid.get();
            if (pid > 0) {
                Optional<String> wait = jdbc.sql("""
                                SELECT wait_event_type
                                FROM pg_stat_activity
                                WHERE pid=:pid AND wait_event_type='Lock'
                                """)
                        .param("pid", pid)
                        .query(String.class)
                        .optional();
                if (wait.isPresent()) {
                    assertThat(wait).contains("Lock");
                    return;
                }
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("PostgreSQL lock wait was not observed");
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private UUID business() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Reference Test','OTHER','ACTIVE','Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", "reference-" + id)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID service(UUID businessId, UUID id, String name, boolean active) {
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (
                            :id,:businessId,:name,NULL,30,:price,:active,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", name)
                .param("price", new BigDecimal("20.00"))
                .param("active", active)
                .param("now", now)
                .update();
        return id;
    }

    private void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }

    private <T> T completed(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent operation was interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError("concurrent operation did not complete", exception);
        }
    }
}
