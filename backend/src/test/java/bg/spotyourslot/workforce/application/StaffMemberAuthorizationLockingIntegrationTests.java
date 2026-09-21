package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

@Import(StaffMemberAuthorizationLockingIntegrationTests.LockProbeConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffMemberAuthorizationLockingIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired StaffMemberAdministration staffMembers;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PausingBusinessLifecycleAccess businesses;
    @Autowired PausingSelectedBusinessOwnerAccess owners;
    @Autowired LockObservation observation;

    @Test
    void twoSameVersionApplicationUpdatesHaveExactlyOneWinnerOnSeparateConnections() {
        Fixture fixture = fixture();
        StaffMemberDetails original = staffMembers.create(
                fixture.context(), create("Original"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var firstCommand = update("First winner", original.version());
        var secondCommand = update("Second winner", original.version());
        observation.clear();

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
            assertThat(observation.backendPids()).hasSize(2).allMatch(pid -> pid > 0);
            StaffMemberDetails stored = staffMembers.get(
                    fixture.context(), original.id());
            assertThat(stored.version()).isEqualTo(1);
            assertThat(stored.displayName()).isIn(
                    firstCommand.displayName(), secondCommand.displayName());
            assertThat(stored.active()).isTrue();
            assertThat(stored.createdAt()).isEqualTo(original.createdAt());
            assertThat(stored.updatedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void committedSuspensionIsObservedAfterBusinessLifecycleLockWait() {
        Fixture fixture = fixture();
        StaffMemberDetails original = staffMembers.create(
                fixture.context(), create("Protected"));
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Probe probe = businesses.arm();
        observation.clear();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Integer> suspension = CompletableFuture.supplyAsync(
                    () -> holdBusinessSuspension(
                            fixture.businessId(), updated, commit),
                    executor);
            await(updated, "Business suspension did not acquire its row lock");
            CompletableFuture<Throwable> mutation = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> staffMembers.update(
                            fixture.context(),
                            original.id(),
                            update("Must not apply", original.version()))),
                    executor);
            await(probe.attempted(), "StaffMember mutation did not attempt the Business lock");

            assertLockWait(probe.backendPid().get());
            assertThat(mutation).isNotCompleted();
            commit.countDown();

            assertThat(completed(suspension)).isEqualTo(1);
            assertThat(completed(mutation)).isInstanceOf(BusinessSuspended.class);
            assertThat(observation.events()).containsExactly("BUSINESS", "MEMBERSHIP");
            assertUnchanged(original);
        } finally {
            commit.countDown();
            businesses.disarm();
        }
    }

    @Test
    void committedOwnerDeactivationIsObservedAfterOrderedMembershipLockWait() {
        Fixture fixture = fixture();
        StaffMemberDetails original = staffMembers.create(
                fixture.context(), create("Protected"));
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Probe probe = owners.arm();
        observation.clear();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Integer> deactivation = CompletableFuture.supplyAsync(
                    () -> holdMembershipDeactivation(
                            fixture.membershipId(), updated, commit),
                    executor);
            await(updated, "Membership deactivation did not acquire its row lock");
            CompletableFuture<Throwable> mutation = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> staffMembers.update(
                            fixture.context(),
                            original.id(),
                            update("Must not apply", original.version()))),
                    executor);
            await(probe.attempted(), "StaffMember mutation did not attempt the Membership lock");

            assertLockWait(probe.backendPid().get());
            assertThat(mutation).isNotCompleted();
            commit.countDown();

            assertThat(completed(deactivation)).isEqualTo(1);
            assertThat(completed(mutation)).isInstanceOf(BusinessAccessDenied.class);
            assertThat(observation.events()).containsExactly("BUSINESS", "MEMBERSHIP");
            assertUnchanged(original);
        } finally {
            commit.countDown();
            owners.disarm();
        }
    }

    private CompletableFuture<MutationOutcome> updateConcurrently(
            ExecutorService executor,
            AuthenticatedBusinessContext context,
            StaffMemberDetails original,
            UpdateStaffMemberCommand command,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start, "concurrent update was not released");
            try {
                staffMembers.update(context, original.id(), command);
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

    private void assertLockWait(int backendPid) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<String> wait = jdbc.sql("""
                            SELECT wait_event_type
                            FROM pg_stat_activity
                            WHERE pid=:pid AND wait_event_type='Lock'
                            """)
                    .param("pid", backendPid)
                    .query(String.class)
                    .optional();
            if (wait.isPresent()) {
                assertThat(wait).contains("Lock");
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("StaffMember mutation did not enter a PostgreSQL lock wait");
    }

    private void assertUnchanged(StaffMemberDetails original) {
        StaffMemberSnapshot stored = jdbc.sql("""
                        SELECT display_name,contact_email,contact_phone,active,version,
                               created_at,updated_at
                        FROM staff_member WHERE id=:id
                        """)
                .param("id", original.id())
                .query((resultSet, rowNumber) -> new StaffMemberSnapshot(
                        resultSet.getString("display_name"),
                        resultSet.getString("contact_email"),
                        resultSet.getString("contact_phone"),
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
        assertThat(stored).isEqualTo(new StaffMemberSnapshot(
                original.displayName(),
                original.contactEmail(),
                original.contactPhone(),
                original.active(),
                original.version(),
                original.createdAt(),
                original.updatedAt()));
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
                .param("slug", "staff-lock-" + businessId.toString().substring(0, 8))
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

    private static CreateStaffMemberCommand create(String displayName) {
        return new CreateStaffMemberCommand(
                displayName, "member@example.invalid", "+359 888 123 456");
    }

    private static UpdateStaffMemberCommand update(String displayName, long version) {
        return new UpdateStaffMemberCommand(
                displayName, "updated@example.invalid", "+359 888 654 321", version);
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

    private record StaffMemberSnapshot(
            String displayName,
            String contactEmail,
            String contactPhone,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    static final class LockObservation {
        private final List<String> events = new ArrayList<>();
        private final Set<Integer> backendPids = new HashSet<>();

        synchronized void record(String event, int backendPid) {
            events.add(event);
            backendPids.add(backendPid);
        }

        synchronized void clear() {
            events.clear();
            backendPids.clear();
        }

        synchronized List<String> events() {
            return List.copyOf(events);
        }

        synchronized Set<Integer> backendPids() {
            return Collections.unmodifiableSet(new HashSet<>(backendPids));
        }
    }

    static final class PausingBusinessLifecycleAccess implements BusinessLifecycleAccess {
        private final BusinessLifecycleAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        PausingBusinessLifecycleAccess(
                BusinessLifecycleAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
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
            int backendPid = jdbc.sql("SELECT pg_backend_pid()")
                    .query(Integer.class)
                    .single();
            observation.record("BUSINESS", backendPid);
            Probe current = probe.get();
            if (current != null) {
                current.backendPid().set(backendPid);
                current.attempted().countDown();
            }
            return delegate.lockLifecycle(businessId);
        }
    }

    static final class PausingSelectedBusinessOwnerAccess
            implements SelectedBusinessOwnerAccess {
        private final SelectedBusinessOwnerAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        PausingSelectedBusinessOwnerAccess(
                SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
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
            int backendPid = jdbc.sql("SELECT pg_backend_pid()")
                    .query(Integer.class)
                    .single();
            observation.record("MEMBERSHIP", backendPid);
            Probe current = probe.get();
            if (current != null) {
                current.backendPid().set(backendPid);
                current.attempted().countDown();
            }
            return delegate.lockAndAuthorize(userId, businessId);
        }
    }

    @TestConfiguration
    static class LockProbeConfiguration {
        @Bean
        @Primary
        Clock fixedStaffMemberAuthorizationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        LockObservation lockObservation() {
            return new LockObservation();
        }

        @Bean
        @Primary
        PausingBusinessLifecycleAccess pausingBusinessLifecycleAccess(
                @Qualifier("businessAdministrationService") BusinessLifecycleAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new PausingBusinessLifecycleAccess(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        PausingSelectedBusinessOwnerAccess pausingSelectedBusinessOwnerAccess(
                @Qualifier("businessAuthorizer") SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new PausingSelectedBusinessOwnerAccess(delegate, jdbc, observation);
        }
    }
}
