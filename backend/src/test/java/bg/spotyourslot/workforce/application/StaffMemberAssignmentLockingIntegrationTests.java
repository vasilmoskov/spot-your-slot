package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.business.BusinessLifecycleAccess;
import bg.spotyourslot.business.BusinessLifecycleAccess.BusinessLifecycle;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceReferenceAccess;
import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReference;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ServiceInactive;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberAssignments;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.infrastructure.StaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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

@Import(StaffMemberAssignmentLockingIntegrationTests.LockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffMemberAssignmentLockingIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired StaffMemberAdministration staffMembers;
    @Autowired ServiceAdministration services;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LockObservation observation;
    @Autowired ObservingBusinessLifecycleAccess businesses;
    @Autowired ObservingServiceReferenceAccess serviceReferences;
    @Autowired PausingStaffMemberStore pausingStore;
    @Autowired AdjustableClock clock;

    @Test
    void replacementUsesBusinessMembershipStaffMemberThenServiceLockOrder() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Ordered"));
        UUID serviceId = service(fixture.businessId(), "Ordered service", true);
        observation.clear();
        serviceReferences.expectGuard(staffMember.id(), 1);

        var result = staffMembers.replaceServiceAssignments(
                fixture.context(),
                staffMember.id(),
                assignments(List.of(serviceId), staffMember.version()));

        assertThat(result.version()).isEqualTo(1);
        assertThat(observation.events())
                .containsExactly("BUSINESS", "MEMBERSHIP", "STAFF_MEMBER", "SERVICE");
        assertThat(observation.backendPids()).hasSize(1);
    }

    @Test
    void assignmentListingUsesOneRepeatableReadSnapshotWithoutBlockingReplacement() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Snapshot reader"));
        UUID originalService = service(fixture.businessId(), "Original service", true);
        UUID replacementService = service(fixture.businessId(), "Replacement service", true);
        var original = staffMembers.replaceServiceAssignments(
                fixture.context(),
                staffMember.id(),
                assignments(List.of(originalService), staffMember.version()));
        ReadProbe probe = pausingStore.pauseAfterNextFind(
                fixture.businessId(), staffMember.id());
        observation.clear();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<StaffMemberAssignments> listing =
                    CompletableFuture.supplyAsync(
                            () -> staffMembers.listServiceAssignments(
                                    fixture.context(), staffMember.id()),
                            executor);
            await(probe.observed(), "assignment listing did not observe the StaffMember row");
            assertThat(probe.isolation()).hasValue("repeatable read");
            assertThat(probe.readOnly()).hasValue("on");

            clock.set(NOW.plusSeconds(60));
            CompletableFuture<StaffMemberAssignments> replacement =
                    CompletableFuture.supplyAsync(
                            () -> staffMembers.replaceServiceAssignments(
                                    fixture.context(),
                                    staffMember.id(),
                                    assignments(
                                            List.of(replacementService), original.version())),
                            executor);
            var committed = completed(replacement);

            assertThat(listing).isNotCompleted();
            assertThat(observation.backendPids())
                    .hasSize(1)
                    .doesNotContain(probe.backendPid().get());
            probe.resume().countDown();

            var snapshot = completed(listing);
            assertThat(snapshot.version()).isEqualTo(original.version());
            assertThat(snapshot.createdAt()).isEqualTo(original.createdAt());
            assertThat(snapshot.updatedAt()).isEqualTo(original.updatedAt());
            assertThat(snapshot.services())
                    .extracting(summary -> summary.id())
                    .containsExactly(originalService);

            var fresh = staffMembers.listServiceAssignments(
                    fixture.context(), staffMember.id());
            assertThat(fresh.version()).isEqualTo(committed.version());
            assertThat(fresh.createdAt()).isEqualTo(committed.createdAt());
            assertThat(fresh.updatedAt()).isEqualTo(committed.updatedAt());
            assertThat(fresh.services())
                    .extracting(summary -> summary.id())
                    .containsExactly(replacementService);
        } finally {
            probe.resume().countDown();
            pausingStore.disarm();
            clock.reset();
        }
    }

    @Test
    void sameVersionAssignmentReplacementsHaveExactlyOneWinner() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("One winner"));
        UUID first = service(fixture.businessId(), "First", true);
        UUID second = service(fixture.businessId(), "Second", true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        observation.clear();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Outcome> left = replacement(
                    executor, fixture.context(), staffMember, List.of(first), ready, start);
            CompletableFuture<Outcome> right = replacement(
                    executor, fixture.context(), staffMember, List.of(second), ready, start);
            await(ready, "assignment replacements were not ready");
            start.countDown();

            assertThat(List.of(completed(left), completed(right)))
                    .containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.CONCURRENT_UPDATE);
        }

        assertThat(observation.backendPids()).hasSize(2);
        assertThat(staffMembers.get(fixture.context(), staffMember.id()).version()).isEqualTo(1);
        assertThat(assignedIds(staffMember.id())).hasSize(1).containsAnyOf(first, second);
    }

    @Test
    void assignmentRacesWithProfileAndLifecycleThroughTheSharedVersion() {
        Fixture fixture = fixture();
        StaffMemberDetails original = staffMembers.create(
                fixture.context(), create("Shared aggregate"));
        UUID serviceId = service(fixture.businessId(), "Shared", true);

        List<Outcome> profileRace = race(
                () -> staffMembers.replaceServiceAssignments(
                        fixture.context(),
                        original.id(),
                        assignments(List.of(serviceId), original.version())),
                () -> staffMembers.update(
                        fixture.context(),
                        original.id(),
                        new UpdateStaffMemberCommand("Profile", null, null, original.version())));
        assertThat(profileRace)
                .containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.CONCURRENT_UPDATE);

        StaffMemberDetails afterProfileRace = staffMembers.get(
                fixture.context(), original.id());
        List<Outcome> lifecycleRace = race(
                () -> staffMembers.replaceServiceAssignments(
                        fixture.context(),
                        original.id(),
                        assignments(List.of(serviceId), afterProfileRace.version())),
                () -> staffMembers.deactivate(
                        fixture.context(),
                        original.id(),
                        new StaffMemberVersionCommand(afterProfileRace.version())));
        assertThat(lifecycleRace)
                .containsExactlyInAnyOrder(Outcome.SUCCESS, Outcome.CONCURRENT_UPDATE);
        assertThat(staffMembers.get(fixture.context(), original.id()).version())
                .isEqualTo(afterProfileRace.version() + 1);
    }

    @Test
    void committedServiceDeactivationIsObservedAfterAssignmentWaitsForShareLock() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Waits for service"));
        UUID serviceId = service(fixture.businessId(), "Race service", true);
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Probe probe = serviceReferences.arm();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> deactivation = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        services.deactivate(
                                fixture.context(), serviceId, new ServiceVersionCommand(0L));
                        deactivated.countDown();
                        await(commit, "Service deactivation was not released");
                    }),
                    executor);
            await(deactivated, "Service was not deactivated inside its transaction");

            CompletableFuture<Throwable> replacement = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> staffMembers.replaceServiceAssignments(
                            fixture.context(),
                            staffMember.id(),
                            assignments(List.of(serviceId), staffMember.version()))),
                    executor);
            await(probe.attempted(), "assignment did not attempt the Service share lock");
            assertLockWait(probe.backendPid());
            assertThat(replacement).isNotCompleted();
            commit.countDown();

            completed(deactivation);
            assertThat(completed(replacement)).isInstanceOf(ServiceInactive.class);
            assertThat(staffMembers.get(fixture.context(), staffMember.id()).version()).isZero();
            assertThat(assignedIds(staffMember.id())).isEmpty();
        } finally {
            commit.countDown();
            serviceReferences.disarm();
        }
    }

    @Test
    void acceptedAssignmentShareLockMakesServiceDeactivationWaitAndPreservesRelationship() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Holds service"));
        UUID serviceId = service(fixture.businessId(), "Protected service", true);
        CountDownLatch assigned = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> replacement = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        staffMembers.replaceServiceAssignments(
                                fixture.context(),
                                staffMember.id(),
                                assignments(List.of(serviceId), staffMember.version()));
                        assigned.countDown();
                        await(commit, "assignment transaction was not released");
                    }),
                    executor);
            await(assigned, "assignment did not acquire its Service share lock");

            Probe mutationProbe = businesses.arm();
            CompletableFuture<Void> deactivation = CompletableFuture.runAsync(
                    () -> services.deactivate(
                            fixture.context(), serviceId, new ServiceVersionCommand(0L)),
                    executor);
            await(mutationProbe.attempted(), "Service mutation did not start");
            assertLockWait(mutationProbe.backendPid());
            assertThat(deactivation).isNotCompleted();
            commit.countDown();

            completed(replacement);
            completed(deactivation);
            assertThat(assignedIds(staffMember.id())).containsExactly(serviceId);
            assertThat(jdbc.sql("SELECT active FROM service WHERE id=:id")
                            .param("id", serviceId)
                            .query(Boolean.class)
                            .single())
                    .isFalse();
        } finally {
            commit.countDown();
            businesses.disarm();
        }
    }

    private CompletableFuture<Outcome> replacement(
            ExecutorService executor,
            AuthenticatedBusinessContext context,
            StaffMemberDetails staffMember,
            List<UUID> desired,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start, "assignment race was not released");
            return outcome(() -> staffMembers.replaceServiceAssignments(
                    context,
                    staffMember.id(),
                    assignments(desired, staffMember.version())));
        }, executor);
    }

    private List<Outcome> race(Runnable first, Runnable second) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Outcome> left = raced(executor, first, ready, start);
            CompletableFuture<Outcome> right = raced(executor, second, ready, start);
            await(ready, "aggregate mutations were not ready");
            start.countDown();
            return List.of(completed(left), completed(right));
        }
    }

    private CompletableFuture<Outcome> raced(
            ExecutorService executor,
            Runnable operation,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start, "aggregate race was not released");
            return outcome(operation);
        }, executor);
    }

    private Outcome outcome(Runnable operation) {
        try {
            operation.run();
            return Outcome.SUCCESS;
        } catch (ConcurrentUpdate exception) {
            return Outcome.CONCURRENT_UPDATE;
        }
    }

    private Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
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

    private Fixture fixture() {
        UUID businessId = business();
        UUID userId = user();
        membership(businessId, userId);
        return new Fixture(businessId, userId, new TestContext(userId, businessId));
    }

    private UUID business() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Assignment Lock Test','OTHER','ACTIVE','Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", "assignment-lock-" + id)
                .param("now", databaseNow())
                .update();
        return id;
    }

    private UUID user() {
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

    private void membership(UUID businessId, UUID userId) {
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:businessId,:userId,'BUSINESS_OWNER',true,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .param("userId", userId)
                .param("now", databaseNow())
                .update();
    }

    private UUID service(UUID businessId, String name, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (:id,:businessId,:name,NULL,30,:price,:active,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", name)
                .param("price", new BigDecimal("20.00"))
                .param("active", active)
                .param("now", databaseNow())
                .update();
        return id;
    }

    private List<UUID> assignedIds(UUID staffMemberId) {
        return jdbc.sql("""
                        SELECT service_id FROM staff_member_service
                        WHERE staff_member_id=:staffMemberId ORDER BY service_id
                        """)
                .param("staffMemberId", staffMemberId)
                .query(UUID.class)
                .list();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private java.time.OffsetDateTime databaseNow() {
        return NOW.atOffset(ZoneOffset.UTC);
    }

    private static CreateStaffMemberCommand create(String name) {
        return new CreateStaffMemberCommand(name, null, null);
    }

    private static ReplaceServiceAssignmentsCommand assignments(
            List<UUID> serviceIds, long version) {
        return new ReplaceServiceAssignmentsCommand(serviceIds, version);
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

    private enum Outcome {
        SUCCESS,
        CONCURRENT_UPDATE
    }

    private record Fixture(
            UUID businessId, UUID userId, AuthenticatedBusinessContext context) {
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

    private record ReadProbe(
            UUID businessId,
            UUID staffMemberId,
            CountDownLatch observed,
            CountDownLatch resume,
            AtomicInteger backendPid,
            AtomicReference<String> isolation,
            AtomicReference<String> readOnly) {
    }

    static class PausingStaffMemberStore extends StaffMemberStore {
        private final JdbcClient jdbc;
        private final AtomicReference<ReadProbe> probe = new AtomicReference<>();

        PausingStaffMemberStore(JdbcClient jdbc) {
            super(jdbc);
            this.jdbc = jdbc;
        }

        ReadProbe pauseAfterNextFind(UUID businessId, UUID staffMemberId) {
            ReadProbe value = new ReadProbe(
                    businessId,
                    staffMemberId,
                    new CountDownLatch(1),
                    new CountDownLatch(1),
                    new AtomicInteger(),
                    new AtomicReference<>(),
                    new AtomicReference<>());
            probe.set(value);
            return value;
        }

        void disarm() {
            probe.set(null);
        }

        @Override
        public Optional<StaffMemberRow> findByBusinessIdAndId(
                UUID businessId, UUID staffMemberId) {
            Optional<StaffMemberRow> result = super.findByBusinessIdAndId(
                    businessId, staffMemberId);
            ReadProbe current = probe.get();
            if (current != null
                    && current.businessId().equals(businessId)
                    && current.staffMemberId().equals(staffMemberId)
                    && probe.compareAndSet(current, null)) {
                current.backendPid().set(jdbc.sql("SELECT pg_backend_pid()")
                        .query(Integer.class)
                        .single());
                current.isolation().set(jdbc.sql("SHOW transaction_isolation")
                        .query(String.class)
                        .single());
                current.readOnly().set(jdbc.sql("SHOW transaction_read_only")
                        .query(String.class)
                        .single());
                current.observed().countDown();
                awaitProbe(current.resume());
            }
            return result;
        }

        private void awaitProbe(CountDownLatch latch) {
            try {
                if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("assignment listing was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("assignment listing was interrupted", exception);
            }
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(NOW);

        void set(Instant instant) {
            current.set(instant);
        }

        void reset() {
            current.set(NOW);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
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

    static final class ObservingBusinessLifecycleAccess implements BusinessLifecycleAccess {
        private final BusinessLifecycleAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        ObservingBusinessLifecycleAccess(
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
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("BUSINESS", pid);
            Probe current = probe.get();
            if (current != null) {
                current.backendPid().set(pid);
                current.attempted().countDown();
            }
            return delegate.lockLifecycle(businessId);
        }
    }

    static final class ObservingOwnerAccess implements SelectedBusinessOwnerAccess {
        private final SelectedBusinessOwnerAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;

        ObservingOwnerAccess(
                SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        @Override
        public Authorization authorize(UUID userId, UUID businessId) {
            return delegate.authorize(userId, businessId);
        }

        @Override
        public Authorization lockAndAuthorize(UUID userId, UUID businessId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("MEMBERSHIP", pid);
            return delegate.lockAndAuthorize(userId, businessId);
        }
    }

    static final class ObservingServiceReferenceAccess implements ServiceReferenceAccess {
        private final ServiceReferenceAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();
        private final AtomicReference<ExpectedGuard> expectedGuard = new AtomicReference<>();

        ObservingServiceReferenceAccess(
                ServiceReferenceAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        void expectGuard(UUID staffMemberId, long version) {
            expectedGuard.set(new ExpectedGuard(staffMemberId, version));
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
        public List<ServiceReference> findReferences(
                UUID businessId, java.util.Collection<UUID> serviceIds) {
            return delegate.findReferences(businessId, serviceIds);
        }

        @Override
        public List<ServiceReference> lockReferences(
                UUID businessId, java.util.Collection<UUID> serviceIds) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            ExpectedGuard guard = expectedGuard.getAndSet(null);
            if (guard != null) {
                long version = jdbc.sql("SELECT version FROM staff_member WHERE id=:id")
                        .param("id", guard.staffMemberId())
                        .query(Long.class)
                        .single();
                assertThat(version).isEqualTo(guard.version());
                observation.record("STAFF_MEMBER", pid);
            }
            observation.record("SERVICE", pid);
            Probe current = probe.get();
            if (current != null) {
                current.backendPid().set(pid);
                current.attempted().countDown();
            }
            return delegate.lockReferences(businessId, serviceIds);
        }

        private record ExpectedGuard(UUID staffMemberId, long version) {
        }
    }

    @TestConfiguration
    static class LockConfiguration {
        @Bean
        @Primary
        AdjustableClock assignmentLockClock() {
            return new AdjustableClock();
        }

        @Bean
        @Primary
        PausingStaffMemberStore pausingStaffMemberStore(JdbcClient jdbc) {
            return new PausingStaffMemberStore(jdbc);
        }

        @Bean
        LockObservation assignmentLockObservation() {
            return new LockObservation();
        }

        @Bean
        @Primary
        ObservingBusinessLifecycleAccess observingBusinessLifecycleAccess(
                @Qualifier("businessAdministrationService") BusinessLifecycleAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingBusinessLifecycleAccess(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        ObservingOwnerAccess observingOwnerAccess(
                @Qualifier("businessAuthorizer") SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingOwnerAccess(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        ObservingServiceReferenceAccess observingServiceReferenceAccess(
                @Qualifier("serviceReferenceAccessService") ServiceReferenceAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingServiceReferenceAccess(delegate, jdbc, observation);
        }
    }
}
