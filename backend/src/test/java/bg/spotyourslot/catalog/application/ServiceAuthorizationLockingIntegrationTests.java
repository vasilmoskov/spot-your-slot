package bg.spotyourslot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessAccessDenied;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessSuspended;
import bg.spotyourslot.catalog.ServiceApplicationException.ConcurrentUpdate;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(ServiceAuthorizationLockingIntegrationTests.LockProbeConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ServiceAuthorizationLockingIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

    @Autowired ServiceAdministration services;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PausingBusinessLifecycleAccess businesses;
    @Autowired PausingSelectedBusinessOwnerAccess owners;
    @Autowired LockOrderRecorder order;

    @Test
    void twoSameVersionApplicationUpdatesHaveExactlyOneWinner() {
        Fixture fixture = fixture();
        ServiceDetails original = services.create(fixture.context(), create("Original"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var firstCommand = new UpdateServiceCommand(
                "First winner", "First description", 40, new BigDecimal("21.00"), 0L);
        var secondCommand = new UpdateServiceCommand(
                "Second winner", "Second description", 50, new BigDecimal("31.00"), 0L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<MutationOutcome> first = updateConcurrently(
                    executor, fixture.context(), original, firstCommand, ready, start);
            CompletableFuture<MutationOutcome> second = updateConcurrently(
                    executor, fixture.context(), original, secondCommand, ready, start);
            await(ready, "concurrent updates did not become ready");
            start.countDown();

            List<MutationOutcome> outcomes = List.of(completed(first), completed(second));
            assertThat(outcomes).containsExactlyInAnyOrder(
                    MutationOutcome.SUCCESS, MutationOutcome.CONCURRENT_UPDATE);
            ServiceDetails stored = services.get(fixture.context(), original.id());
            assertThat(stored.version()).isEqualTo(1);
            assertThat(stored).satisfiesAnyOf(
                    result -> assertMatches(result, firstCommand),
                    result -> assertMatches(result, secondCommand));
            assertThat(stored.active()).isTrue();
            assertThat(stored.createdAt()).isEqualTo(original.createdAt());
            assertThat(stored.updatedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void committedSuspensionIsObservedAfterBusinessSharedLockWait() {
        Fixture fixture = fixture();
        ServiceDetails original = services.create(fixture.context(), create("Protected"));
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Probe probe = businesses.arm();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Integer> suspension = CompletableFuture.supplyAsync(
                    () -> holdBusinessSuspension(fixture.businessId(), updated, commit), executor);
            await(updated, "Business suspension did not acquire its row lock");
            order.clear();
            CompletableFuture<Throwable> mutation = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> services.update(
                            fixture.context(),
                            original.id(),
                            update("Must not apply", original.version()))),
                    executor);
            await(probe.attempted(), "Service mutation did not attempt the Business lock");

            LockWait wait = awaitLockWait(probe.backendPid().get());
            assertThat(wait.waitEventType()).isEqualTo("Lock");
            assertThat(mutation).isNotCompleted();
            commit.countDown();

            assertThat(completed(suspension)).isEqualTo(1);
            assertThat(completed(mutation)).isInstanceOf(BusinessSuspended.class);
            assertThat(order.snapshot()).containsExactly("BUSINESS", "MEMBERSHIP");
            assertUnchanged(original);
        } finally {
            commit.countDown();
            businesses.disarm();
        }
    }

    @Test
    void committedMembershipDeactivationIsObservedAfterOrderedSharedLockWait() {
        Fixture fixture = fixture();
        ServiceDetails original = services.create(fixture.context(), create("Protected"));
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Probe probe = owners.arm();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Integer> deactivation = CompletableFuture.supplyAsync(
                    () -> holdMembershipDeactivation(fixture.membershipId(), updated, commit),
                    executor);
            await(updated, "Membership deactivation did not acquire its row lock");
            order.clear();
            CompletableFuture<Throwable> mutation = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> services.update(
                            fixture.context(),
                            original.id(),
                            update("Must not apply", original.version()))),
                    executor);
            await(probe.attempted(), "Service mutation did not attempt the Membership lock");

            LockWait wait = awaitLockWait(probe.backendPid().get());
            assertThat(wait.waitEventType()).isEqualTo("Lock");
            assertThat(mutation).isNotCompleted();
            commit.countDown();

            assertThat(completed(deactivation)).isEqualTo(1);
            assertThat(completed(mutation)).isInstanceOf(BusinessAccessDenied.class);
            assertThat(order.snapshot()).containsExactly("BUSINESS", "MEMBERSHIP");
            assertUnchanged(original);
        } finally {
            commit.countDown();
            owners.disarm();
        }
    }

    private CompletableFuture<MutationOutcome> updateConcurrently(
            ExecutorService executor,
            AuthenticatedBusinessContext context,
            ServiceDetails original,
            UpdateServiceCommand command,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start, "concurrent update was not released");
            try {
                services.update(context, original.id(), command);
                return MutationOutcome.SUCCESS;
            } catch (ConcurrentUpdate exception) {
                return MutationOutcome.CONCURRENT_UPDATE;
            }
        }, executor);
    }

    private int holdBusinessSuspension(
            UUID businessId, CountDownLatch updated, CountDownLatch commit) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int count = jdbc.sql("UPDATE business SET status='SUSPENDED' WHERE id=:id")
                    .param("id", businessId)
                    .update();
            updated.countDown();
            await(commit, "Business suspension was not released");
            return count;
        });
    }

    private int holdMembershipDeactivation(
            UUID membershipId, CountDownLatch updated, CountDownLatch commit) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int count = jdbc.sql("UPDATE membership SET active=false WHERE id=:id")
                    .param("id", membershipId)
                    .update();
            updated.countDown();
            await(commit, "Membership deactivation was not released");
            return count;
        });
    }

    private LockWait awaitLockWait(int backendPid) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<LockWait> wait = jdbc.sql("""
                            SELECT wait_event_type,wait_event
                            FROM pg_stat_activity
                            WHERE pid=:pid AND wait_event_type='Lock'
                            """)
                    .param("pid", backendPid)
                    .query((resultSet, rowNumber) -> new LockWait(
                            resultSet.getString("wait_event_type"),
                            resultSet.getString("wait_event")))
                    .optional();
            if (wait.isPresent()) {
                return wait.orElseThrow();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Service mutation did not enter a PostgreSQL lock wait");
    }

    private void assertUnchanged(ServiceDetails original) {
        ServiceSnapshot snapshot = jdbc.sql("""
                        SELECT name,description,duration_minutes,price,active,version,
                               created_at,updated_at
                        FROM service WHERE id=:id
                        """)
                .param("id", original.id())
                .query((resultSet, rowNumber) -> new ServiceSnapshot(
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getInt("duration_minutes"),
                        resultSet.getBigDecimal("price"),
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
        assertThat(snapshot).isEqualTo(new ServiceSnapshot(
                original.name(),
                original.description(),
                original.durationMinutes(),
                original.price(),
                original.active(),
                original.version(),
                original.createdAt(),
                original.updatedAt()));
    }

    private void assertMatches(ServiceDetails details, UpdateServiceCommand command) {
        assertThat(details.name()).isEqualTo(command.name());
        assertThat(details.description()).isEqualTo(command.description());
        assertThat(details.durationMinutes()).isEqualTo(command.durationMinutes());
        assertThat(details.price()).isEqualByComparingTo(command.price());
    }

    private Fixture fixture() {
        UUID businessId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        OffsetDateTime now = NOW.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Lock Business','OTHER','ACTIVE','Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", businessId)
                .param("slug", "lock-" + businessId.toString().substring(0, 8))
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,
                            password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Lock User','hash',:now,:now,:now)
                        """)
                .param("id", userId)
                .param("email", userId + "@example.invalid")
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:businessId,:userId,'BUSINESS_OWNER',true,:now,:now)
                        """)
                .param("id", membershipId)
                .param("businessId", businessId)
                .param("userId", userId)
                .param("now", now)
                .update();
        return new Fixture(
                businessId,
                membershipId,
                new TestContext(userId, businessId));
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return new AssertionError("Expected mutation failure");
        } catch (Throwable failure) {
            return failure;
        }
    }

    private void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private <T> T completed(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AssertionError(exception);
        }
    }

    private static CreateServiceCommand create(String name) {
        return new CreateServiceCommand(name, "Description", 30, new BigDecimal("20.00"));
    }

    private static UpdateServiceCommand update(String name, long version) {
        return new UpdateServiceCommand(
                name, "Updated description", 45, new BigDecimal("25.50"), version);
    }

    private enum MutationOutcome {
        SUCCESS,
        CONCURRENT_UPDATE
    }

    private record Fixture(
            UUID businessId,
            UUID membershipId,
            AuthenticatedBusinessContext context) {
    }

    private record TestContext(UUID userId, UUID businessId)
            implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(businessId);
        }
    }

    private record Probe(CountDownLatch attempted, AtomicInteger backendPid) {
    }

    private record LockWait(String waitEventType, String waitEvent) {
    }

    private record ServiceSnapshot(
            String name,
            String description,
            int durationMinutes,
            BigDecimal price,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    static final class LockOrderRecorder {
        private final List<String> events = new ArrayList<>();

        synchronized void record(String event) {
            events.add(event);
        }

        synchronized void clear() {
            events.clear();
        }

        synchronized List<String> snapshot() {
            return List.copyOf(events);
        }
    }

    static final class PausingBusinessLifecycleAccess implements BusinessLifecycleAccess {
        private final BusinessLifecycleAccess delegate;
        private final JdbcClient jdbc;
        private final LockOrderRecorder order;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        PausingBusinessLifecycleAccess(
                BusinessLifecycleAccess delegate, JdbcClient jdbc, LockOrderRecorder order) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.order = order;
        }

        Probe arm() {
            Probe value = new Probe(new CountDownLatch(1), new AtomicInteger());
            probe.set(value);
            return value;
        }

        void disarm() {
            probe.set(null);
        }

        @Override
        public Optional<BusinessLifecycle> findLifecycle(UUID businessId) {
            return delegate.findLifecycle(businessId);
        }

        @Override
        public Optional<BusinessLifecycle> lockLifecycle(UUID businessId) {
            order.record("BUSINESS");
            Probe current = probe.get();
            if (current != null) {
                current.backendPid().set(jdbc.sql("SELECT pg_backend_pid()")
                        .query(Integer.class)
                        .single());
                current.attempted().countDown();
            }
            return delegate.lockLifecycle(businessId);
        }
    }

    static final class PausingSelectedBusinessOwnerAccess
            implements SelectedBusinessOwnerAccess {
        private final SelectedBusinessOwnerAccess delegate;
        private final JdbcClient jdbc;
        private final LockOrderRecorder order;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        PausingSelectedBusinessOwnerAccess(
                SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockOrderRecorder order) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.order = order;
        }

        Probe arm() {
            Probe value = new Probe(new CountDownLatch(1), new AtomicInteger());
            probe.set(value);
            return value;
        }

        void disarm() {
            probe.set(null);
        }

        @Override
        public Authorization authorize(UUID userId, UUID businessId) {
            return delegate.authorize(userId, businessId);
        }

        @Override
        public Authorization lockAndAuthorize(UUID userId, UUID businessId) {
            order.record("MEMBERSHIP");
            Probe current = probe.get();
            if (current != null) {
                current.backendPid().set(jdbc.sql("SELECT pg_backend_pid()")
                        .query(Integer.class)
                        .single());
                current.attempted().countDown();
            }
            return delegate.lockAndAuthorize(userId, businessId);
        }
    }

    @TestConfiguration
    static class LockProbeConfiguration {
        @Bean
        @Primary
        Clock fixedServiceAuthorizationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        LockOrderRecorder lockOrderRecorder() {
            return new LockOrderRecorder();
        }

        @Bean
        @Primary
        PausingBusinessLifecycleAccess pausingBusinessLifecycleAccess(
                @Qualifier("businessAdministrationService") BusinessLifecycleAccess delegate,
                JdbcClient jdbc,
                LockOrderRecorder order) {
            return new PausingBusinessLifecycleAccess(delegate, jdbc, order);
        }

        @Bean
        @Primary
        PausingSelectedBusinessOwnerAccess pausingSelectedBusinessOwnerAccess(
                @Qualifier("businessAuthorizer") SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockOrderRecorder order) {
            return new PausingSelectedBusinessOwnerAccess(delegate, jdbc, order);
        }
    }
}
