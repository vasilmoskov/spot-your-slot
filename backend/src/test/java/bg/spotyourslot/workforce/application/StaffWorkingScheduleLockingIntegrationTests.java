package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.integration.ConcurrencyTestSupport;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleAdministration;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffMemberInactive;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleAdministrationDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
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

@Import(StaffWorkingScheduleLockingIntegrationTests.LockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffWorkingScheduleLockingIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired StaffWorkingScheduleAdministration schedules;
    @Autowired StaffMemberAdministration staffMembers;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired LockObservation observation;
    @Autowired PausingStaffMemberStore pausingStore;
    @Autowired ObservingRevisionBump revisionBump;

    @Test
    void replacementUsesBusinessMembershipStaffMemberThenScheduleRevisionLockOrder() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Ordered"));
        observation.clear();

        StaffWorkingScheduleAdministrationDetails result = schedules.replace(
                fixture.context(), staffMember.id(), command(List.of(monday()), 0));

        assertThat(result.version()).isEqualTo(1);
        assertThat(observation.events()).containsExactly(
                "BUSINESS", "MEMBERSHIP", "STAFF_MEMBER", "SCHEDULE_REVISION");
        assertThat(observation.backendPids()).hasSize(1);
        assertThat(revisionOf(fixture.businessId())).isEqualTo(1L);
    }

    @Test
    void replacementsOfDifferentStaffMembersOfOneBusinessSerializeBehindTheRevisionAndBothCommit() {
        Fixture fixture = fixture();
        StaffMemberDetails first = staffMembers.create(fixture.context(), create("First"));
        StaffMemberDetails second = staffMembers.create(fixture.context(), create("Second"));
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        firstPid.set(ConcurrencyTestSupport.backendPid(jdbc));
                        schedules.replace(
                                fixture.context(), first.id(), command(List.of(monday()), 0));
                        firstWritten.countDown();
                        await(release, "first replacement was not released");
                    }),
                    executor);
            await(firstWritten, "first replacement did not write");

            Probe probe = revisionBump.arm();
            CompletableFuture<StaffWorkingScheduleAdministrationDetails> waiter =
                    CompletableFuture.supplyAsync(
                            () -> schedules.replace(
                                    fixture.context(), second.id(), command(List.of(tuesday()), 0)),
                            executor);
            await(probe.attempted(), "second replacement did not reach the revision lock");
            ConcurrencyTestSupport.awaitBlockedBy(jdbc, probe.backendPid().get(), firstPid.get());
            assertThat(waiter).isNotCompleted();
            assertThat(revisionOf(fixture.businessId())).isZero();
            release.countDown();

            completed(holder);
            assertThat(completed(waiter).version()).isEqualTo(1);
            assertThat(revisionOf(fixture.businessId())).isEqualTo(2L);
            assertThat(schedules.get(fixture.context(), first.id()).periods())
                    .containsExactly(monday());
            assertThat(schedules.get(fixture.context(), second.id()).periods())
                    .containsExactly(tuesday());
        } finally {
            release.countDown();
        }
    }

    @Test
    void theLoserOfASameVersionRaceLeavesNoBumpBehind() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(fixture.context(), create("Loser"));
        schedules.replace(fixture.context(), staffMember.id(), command(List.of(monday()), 0));
        long baseline = revisionOf(fixture.businessId());

        assertThat(captureFailure(() -> schedules.replace(
                        fixture.context(), staffMember.id(), command(List.of(tuesday()), 0))))
                .isInstanceOf(ConcurrentUpdate.class);

        assertThat(revisionOf(fixture.businessId())).isEqualTo(baseline);
    }

    @Test
    void sameVersionReplacementsHaveExactlyOneWinnerAndNeverAMixedPeriodSet() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Raced"));
        ReplaceWorkingPeriodsCommand first = command(List.of(monday()), 0);
        ReplaceWorkingPeriodsCommand second = command(List.of(tuesday()), 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Outcome> left = raced(
                    executor, fixture.context(), staffMember.id(), first, ready, start);
            CompletableFuture<Outcome> right = raced(
                    executor, fixture.context(), staffMember.id(), second, ready, start);
            await(ready, "concurrent replacements did not become ready");
            start.countDown();

            List<Outcome> outcomes = List.of(completed(left), completed(right));
            assertThat(outcomes).containsExactlyInAnyOrder(
                    Outcome.SUCCESS, Outcome.CONCURRENT_UPDATE);

            StaffWorkingScheduleAdministrationDetails stored = schedules.get(
                    fixture.context(), staffMember.id());
            assertThat(stored.version()).isEqualTo(1);
            assertThat(stored.periods()).isIn(List.of(monday()), List.of(tuesday()));
            // Exactly one writer committed, so exactly one bump survived; the loser's rolled back.
            assertThat(revisionOf(fixture.businessId())).isEqualTo(1L);
        }
    }

    @Test
    void deactivationOwningStaffMemberRowFirstBlocksAndRejectsReplacement() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Deactivated first"));
        CountDownLatch deactivated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        Probe probe = pausingStore.armLockActiveState();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> deactivation = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        staffMembers.deactivate(
                                fixture.context(),
                                staffMember.id(),
                                new StaffMemberVersionCommand(0L));
                        deactivated.countDown();
                        await(commit, "deactivation was not released");
                    }),
                    executor);
            await(deactivated, "deactivation did not commit its StaffMember lock");

            CompletableFuture<Throwable> replacement = CompletableFuture.supplyAsync(
                    () -> captureFailure(() -> schedules.replace(
                            fixture.context(),
                            staffMember.id(),
                            command(List.of(monday()), 0))),
                    executor);
            await(probe.attempted(), "replacement did not attempt the StaffMember share lock");
            assertLockWait(probe.backendPid());
            assertThat(replacement).isNotCompleted();
            commit.countDown();

            completed(deactivation);
            assertThat(completed(replacement)).isInstanceOf(StaffMemberInactive.class);
            assertThat(staffMembers.get(fixture.context(), staffMember.id()).active()).isFalse();
            StaffWorkingScheduleAdministrationDetails unchanged = schedules.get(
                    fixture.context(), staffMember.id());
            assertThat(unchanged.version()).isZero();
            assertThat(unchanged.periods()).isEmpty();
        } finally {
            commit.countDown();
            pausingStore.disarmLockActiveState();
        }
    }

    @Test
    void replacementOwningStaffMemberRowFirstBlocksDeactivationThenBothComplete() {
        Fixture fixture = fixture();
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Replaced first"));
        CountDownLatch replaced = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> replacement = CompletableFuture.runAsync(
                    () -> transaction().executeWithoutResult(status -> {
                        schedules.replace(
                                fixture.context(),
                                staffMember.id(),
                                command(List.of(monday()), 0));
                        replaced.countDown();
                        await(commit, "replacement was not released");
                    }),
                    executor);
            await(replaced, "replacement did not acquire its StaffMember share lock");

            Probe probe = pausingStore.armDeactivate();
            CompletableFuture<Void> deactivation = CompletableFuture.runAsync(
                    () -> staffMembers.deactivate(
                            fixture.context(),
                            staffMember.id(),
                            new StaffMemberVersionCommand(0L)),
                    executor);
            await(probe.attempted(), "deactivation did not attempt the StaffMember write lock");
            assertLockWait(probe.backendPid());
            assertThat(deactivation).isNotCompleted();
            commit.countDown();

            completed(replacement);
            completed(deactivation);
            assertThat(staffMembers.get(fixture.context(), staffMember.id()).active()).isFalse();
            StaffWorkingScheduleAdministrationDetails result = schedules.get(
                    fixture.context(), staffMember.id());
            assertThat(result.version()).isEqualTo(1);
            assertThat(result.periods()).containsExactly(monday());
        } finally {
            commit.countDown();
            pausingStore.disarmDeactivate();
        }
    }

    private CompletableFuture<Outcome> raced(
            ExecutorService executor,
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            ReplaceWorkingPeriodsCommand command,
            CountDownLatch ready,
            CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            await(start, "concurrent replacement was not released");
            try {
                schedules.replace(context, staffMemberId, command);
                return Outcome.SUCCESS;
            } catch (ConcurrentUpdate exception) {
                return Outcome.CONCURRENT_UPDATE;
            }
        }, executor);
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

    private long revisionOf(UUID businessId) {
        return jdbc.sql("SELECT revision FROM business_schedule_revision WHERE business_id = :id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
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
                            :id,:slug,'Schedule Lock Test','OTHER','ACTIVE','Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", "schedule-lock-" + id)
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

    private OffsetDateTime databaseNow() {
        return NOW.atOffset(ZoneOffset.UTC);
    }

    private static CreateStaffMemberCommand create(String displayName) {
        return new CreateStaffMemberCommand(displayName, null, null);
    }

    private static WorkingPeriod monday() {
        return new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    private static WorkingPeriod tuesday() {
        return new WorkingPeriod(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(12, 0));
    }

    private static ReplaceWorkingPeriodsCommand command(
            List<WorkingPeriod> periods, long expectedVersion) {
        return new ReplaceWorkingPeriodsCommand(periods, expectedVersion);
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

    static final class ObservingBusinessScheduleContextAccess
            implements BusinessScheduleContextAccess {
        private final BusinessScheduleContextAccess delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;

        ObservingBusinessScheduleContextAccess(
                BusinessScheduleContextAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        @Override
        public Optional<BusinessScheduleContext> findScheduleContext(UUID businessId) {
            return delegate.findScheduleContext(businessId);
        }

        @Override
        public Optional<BusinessScheduleContext> lockScheduleContext(UUID businessId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("BUSINESS", pid);
            return delegate.lockScheduleContext(businessId);
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

    static final class ObservingRevisionBump implements ScheduleRevisionBump {
        private final ScheduleRevisionBump delegate;
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> probe = new AtomicReference<>();

        ObservingRevisionBump(
                ScheduleRevisionBump delegate, JdbcClient jdbc, LockObservation observation) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.observation = observation;
        }

        Probe arm() {
            Probe value = new Probe(new CountDownLatch(1), new AtomicInteger());
            probe.set(value);
            return value;
        }

        @Override
        public long advance(UUID businessId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("SCHEDULE_REVISION", pid);
            Probe current = probe.getAndSet(null);
            if (current != null) {
                current.backendPid().set(pid);
                current.attempted().countDown();
            }
            return delegate.advance(businessId);
        }
    }

    static class PausingStaffMemberStore extends StaffMemberStore {
        private final JdbcClient jdbc;
        private final LockObservation observation;
        private final AtomicReference<Probe> lockActiveStateProbe = new AtomicReference<>();
        private final AtomicReference<Probe> deactivateProbe = new AtomicReference<>();

        PausingStaffMemberStore(JdbcClient jdbc, LockObservation observation) {
            super(jdbc);
            this.jdbc = jdbc;
            this.observation = observation;
        }

        Probe armLockActiveState() {
            Probe value = new Probe(new CountDownLatch(1), new AtomicInteger());
            lockActiveStateProbe.set(value);
            return value;
        }

        void disarmLockActiveState() {
            lockActiveStateProbe.set(null);
        }

        Probe armDeactivate() {
            Probe value = new Probe(new CountDownLatch(1), new AtomicInteger());
            deactivateProbe.set(value);
            return value;
        }

        void disarmDeactivate() {
            deactivateProbe.set(null);
        }

        @Override
        public Optional<StaffMemberRow> lockActiveState(UUID businessId, UUID staffMemberId) {
            int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
            observation.record("STAFF_MEMBER", pid);
            Probe current = lockActiveStateProbe.get();
            if (current != null) {
                current.backendPid().set(pid);
                current.attempted().countDown();
            }
            return super.lockActiveState(businessId, staffMemberId);
        }

        @Override
        public Optional<StaffMemberRow> deactivate(
                UUID businessId, UUID staffMemberId, long expectedVersion, Instant updatedAt) {
            Probe current = deactivateProbe.get();
            if (current != null) {
                int pid = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
                current.backendPid().set(pid);
                current.attempted().countDown();
            }
            return super.deactivate(businessId, staffMemberId, expectedVersion, updatedAt);
        }
    }

    @TestConfiguration
    static class LockConfiguration {
        @Bean
        @Primary
        Clock fixedScheduleLockingClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        LockObservation scheduleLockObservation() {
            return new LockObservation();
        }

        @Bean
        @Primary
        PausingStaffMemberStore pausingStaffMemberStore(
                JdbcClient jdbc, LockObservation observation) {
            return new PausingStaffMemberStore(jdbc, observation);
        }

        @Bean
        @Primary
        ObservingRevisionBump observingRevisionBump(
                @Qualifier("scheduleRevisionService") ScheduleRevisionBump delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingRevisionBump(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        ObservingBusinessScheduleContextAccess observingBusinessScheduleContextAccess(
                @Qualifier("businessAdministrationService") BusinessScheduleContextAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingBusinessScheduleContextAccess(delegate, jdbc, observation);
        }

        @Bean
        @Primary
        ObservingOwnerAccess observingOwnerAccess(
                @Qualifier("businessAuthorizer") SelectedBusinessOwnerAccess delegate,
                JdbcClient jdbc,
                LockObservation observation) {
            return new ObservingOwnerAccess(delegate, jdbc, observation);
        }
    }
}
