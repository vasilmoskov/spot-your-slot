package bg.spotyourslot.business.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugConflict;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugImmutable;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugReserved;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InputField;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.business.domain.ReservedBusinessSlugs;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(BusinessAdministrationServiceIntegrationTests.FixedClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessAdministrationServiceIntegrationTests extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    BusinessAdministration administration;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void createsDraftWithNormalizedValuesDefaultTimezoneAndFixedTimestamps() {
        var created = administration.create(new CreateBusinessCommand(
                "  Created-Business  ",
                "  Бизнес име  ",
                BusinessType.BEAUTY_STUDIO,
                null,
                "  Описание  ",
                "  София  ",
                "  1000  ",
                "  Примерна  ",
                "  1  ",
                "  вход А  ",
                "  +359 2 000 0000  ",
                "  CONTACT@EXAMPLE.INVALID  "));

        assertThat(created.slug()).isEqualTo("created-business");
        assertThat(created.displayName()).isEqualTo("Бизнес име");
        assertThat(created.status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(created.timezone()).isEqualTo("Europe/Sofia");
        assertThat(created.description()).isEqualTo("Описание");
        assertThat(created.city()).isEqualTo("София");
        assertThat(created.addressDetails()).isEqualTo("вход А");
        assertThat(created.phone()).isEqualTo("+359 2 000 0000");
        assertThat(created.contactEmail()).isEqualTo("contact@example.invalid");
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        assertThat(administration.get(created.id())).isEqualTo(created);
    }

    @Test
    void listsDeterministicallyWithBoundedPaginationAndCount() {
        // Display names: "Business first", "Business same-higher", "Business same-lower" —
        // the default ascending display-name order places "first" ahead of both "same-*"
        // entries, and "same-higher" ahead of "same-lower".
        UUID firstId = insertBusiness("first", NOW.minusSeconds(2));
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertBusiness(lowerId, "same-lower", NOW.minusSeconds(1));
        insertBusiness(higherId, "same-higher", NOW.minusSeconds(1));

        var page = administration.list(0, 10, null, null);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.businesses()).extracting(summary -> summary.id())
                .containsExactly(firstId, higherId, lowerId);
    }

    @Test
    void sortAndDirectionAreAppliedThroughTheApplicationLayer() {
        insertBusiness("zulu-business", NOW);
        insertBusiness("alpha-business", NOW);

        var ascending = administration.list(0, 10, "slug", "asc");
        var descending = administration.list(0, 10, "slug", "desc");

        assertThat(ascending.businesses()).extracting(summary -> summary.slug())
                .containsExactly("alpha-business", "zulu-business");
        assertThat(descending.businesses()).extracting(summary -> summary.slug())
                .containsExactly("zulu-business", "alpha-business");
    }

    @Test
    void rejectsInvalidPaginationSortAndDirection() {
        assertThatThrownBy(() -> administration.list(-1, 10, null, null))
                .isInstanceOf(InvalidInput.class)
                .extracting(failure -> ((InvalidInput) failure).field())
                .isEqualTo(InputField.PAGE);
        assertThatThrownBy(() -> administration.list(0, 51, null, null))
                .isInstanceOf(InvalidInput.class)
                .extracting(failure -> ((InvalidInput) failure).field())
                .isEqualTo(InputField.SIZE);
        assertThatThrownBy(() -> administration.list(0, 10, "unknown", null))
                .isInstanceOf(InvalidInput.class)
                .extracting(failure -> ((InvalidInput) failure).field())
                .isEqualTo(InputField.SORT);
        assertThatThrownBy(() -> administration.list(0, 10, null, "sideways"))
                .isInstanceOf(InvalidInput.class)
                .extracting(failure -> ((InvalidInput) failure).field())
                .isEqualTo(InputField.DIRECTION);
    }

    @Test
    void reportsMissingBusiness() {
        UUID missing = UUID.fromString("00000000-0000-0000-0000-000000000099");

        assertThatThrownBy(() -> administration.get(missing))
                .isInstanceOf(BusinessNotFound.class);
    }

    @Test
    void updatesApprovedProfileAndIncrementsVersionExactlyOnce() {
        var created = administration.create(createCommand("profile-original"));
        var updated = administration.update(
                created.id(), updateCommand("profile-updated", created.version()));

        assertThat(updated.slug()).isEqualTo("profile-updated");
        assertThat(updated.displayName()).isEqualTo("Updated Business");
        assertThat(updated.businessType()).isEqualTo(BusinessType.MASSAGE_STUDIO);
        assertThat(updated.status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(updated.timezone()).isEqualTo("Europe/London");
        assertThat(updated.description()).isEqualTo("Updated description");
        assertThat(updated.city()).isEqualTo("Plovdiv");
        assertThat(updated.addressDetails()).isEqualTo("Updated address");
        assertThat(updated.phone()).isEqualTo("+359 2 111 1111");
        assertThat(updated.contactEmail()).isEqualTo("updated@example.invalid");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.createdAt()).isEqualTo(NOW);
        assertThat(updated.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsStaleProfileUpdateWithoutChangingData() {
        var created = administration.create(createCommand("stale-profile"));

        assertThatThrownBy(() -> administration.update(
                        created.id(), updateCommand("not-applied", 1)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThat(administration.get(created.id())).isEqualTo(created);
    }

    @Test
    void translatesDuplicateSlugOnCreateAndProfileUpdate() {
        var existing = administration.create(createCommand("existing-slug"));
        var target = administration.create(createCommand("target-slug"));

        assertThatThrownBy(() -> administration.create(createCommand("existing-slug")))
                .isInstanceOf(BusinessSlugConflict.class)
                .hasNoCause();
        assertThatThrownBy(() -> administration.update(
                        target.id(), updateCommand(existing.slug(), target.version())))
                .isInstanceOf(BusinessSlugConflict.class)
                .hasNoCause();
    }

    @Test
    void appliesAllNamedLifecycleTransitionsWithoutOwnerReadinessChecks() {
        var draft = administration.create(createCommand("lifecycle-business"));

        var active = administration.activateDraft(draft.id(), draft.version());
        var suspended = administration.suspendActive(active.id(), active.version());
        var reactivated =
                administration.reactivateSuspended(suspended.id(), suspended.version());

        assertThat(active.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(active.version()).isEqualTo(1);
        assertThat(suspended.status()).isEqualTo(BusinessStatus.SUSPENDED);
        assertThat(suspended.version()).isEqualTo(2);
        assertThat(reactivated.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(reactivated.version()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidAndStaleLifecycleOperationsWithoutMutation() {
        var draft = administration.create(createCommand("rejected-lifecycle"));

        assertThatThrownBy(() -> administration.suspendActive(draft.id(), draft.version()))
                .isInstanceOf(InvalidLifecycleTransition.class);
        var active = administration.activateDraft(draft.id(), draft.version());
        assertThatThrownBy(() -> administration.suspendActive(active.id(), draft.version()))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThat(administration.get(active.id())).isEqualTo(active);
    }

    @Test
    void concurrentSameVersionProfileWritesProduceOneSuccessAndOneConflict() {
        var created = administration.create(createCommand("concurrent-profile"));

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> administration.update(
                        created.id(), updateCommand("first-writer", created.version())),
                () -> administration.update(
                        created.id(), updateCommand("second-writer", created.version())));

        assertSeparateConnectionsOneSuccessAndOneConflict(outcomes);
        var stored = administration.get(created.id());
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.slug()).isIn("first-writer", "second-writer");
    }

    @Test
    void concurrentSameVersionLifecycleWritesProduceOneSuccessAndOneConflict() {
        var created = administration.create(createCommand("concurrent-lifecycle"));

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> administration.activateDraft(created.id(), created.version()),
                () -> administration.activateDraft(created.id(), created.version()));

        assertSeparateConnectionsOneSuccessAndOneConflict(outcomes);
        var stored = administration.get(created.id());
        assertThat(stored.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void rejectsEveryReservedSlugOnCreateWithoutInsertingARow() {
        for (String reserved : ReservedBusinessSlugs.values()) {
            assertThatThrownBy(() -> administration.create(createCommand(reserved)))
                    .isInstanceOfSatisfying(
                            InvalidInput.class,
                            failure -> assertThat(failure.field())
                                    .isEqualTo(InputField.RESERVED_SLUG));
        }
        assertThatThrownBy(() -> administration.create(createCommand("  LOGIN ")))
                .isInstanceOf(InvalidInput.class);

        assertThat(businessCount()).isZero();
    }

    @Test
    void createsBusinessesWhoseSlugsOnlyResembleReservedRoots() {
        for (String slug : List.of("booking-studio", "my-book", "appointments-bg")) {
            assertThat(administration.create(createCommand(slug)).slug()).isEqualTo(slug);
        }
    }

    @Test
    void draftMayChangeToAnotherValidSlugButNotToAReservedOne() {
        var draft = administration.create(createCommand("draft-original"));

        for (String reserved : ReservedBusinessSlugs.values()) {
            assertThatThrownBy(() -> administration.update(
                            draft.id(), updateCommand(reserved, draft.version())))
                    .isInstanceOfSatisfying(
                            InvalidInput.class,
                            failure -> assertThat(failure.field())
                                    .isEqualTo(InputField.RESERVED_SLUG));
        }
        assertThat(administration.get(draft.id())).isEqualTo(draft);

        var renamed = administration.update(
                draft.id(), updateCommand("booking-studio", draft.version()));
        assertThat(renamed.slug()).isEqualTo("booking-studio");
        assertThat(renamed.status()).isEqualTo(BusinessStatus.DRAFT);
    }

    @Test
    void grandfatheredReservedDraftSlugMayBeKeptWhileOtherFieldsChange() {
        UUID id = insertBusiness("login", "DRAFT");

        var unchanged = administration.update(id, updateCommand("login", 0));
        var differentCase = administration.update(id, updateCommand("  LOGIN ", 1));

        assertThat(unchanged.slug()).isEqualTo("login");
        assertThat(unchanged.displayName()).isEqualTo("Updated Business");
        assertThat(unchanged.version()).isEqualTo(1);
        assertThat(differentCase.slug()).isEqualTo("login");
        assertThat(differentCase.version()).isEqualTo(2);
    }

    @Test
    void grandfatheredReservedDraftCannotMoveToAnotherReservedSlugButCanMoveToAValidOne() {
        UUID id = insertBusiness("login", "DRAFT");

        assertThatThrownBy(() -> administration.update(id, updateCommand("admin", 0)))
                .isInstanceOfSatisfying(
                        InvalidInput.class,
                        failure -> assertThat(failure.field())
                                .isEqualTo(InputField.RESERVED_SLUG));

        assertThat(administration.update(id, updateCommand("login-studio", 0)).slug())
                .isEqualTo("login-studio");
    }

    @Test
    void grandfatheredReservedDraftCannotBeActivatedUntilItsSlugIsChanged() {
        UUID id = insertBusiness("booking", "DRAFT");

        assertThatThrownBy(() -> administration.activateDraft(id, 0))
                .isInstanceOf(BusinessSlugReserved.class)
                .hasMessage("Business slug is reserved")
                .hasNoCause();
        var stillDraft = administration.get(id);
        assertThat(stillDraft.status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(stillDraft.version()).isZero();

        var renamed = administration.update(id, updateCommand("booking-studio", 0));
        var active = administration.activateDraft(id, renamed.version());

        assertThat(active.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(active.slug()).isEqualTo("booking-studio");
    }

    @Test
    void activeAndSuspendedBusinessesRejectAChangedSlugWithoutMutation() {
        var draft = administration.create(createCommand("stable-slug"));
        var active = administration.activateDraft(draft.id(), draft.version());

        assertThatThrownBy(() -> administration.update(
                        active.id(), updateCommand("changed-slug", active.version())))
                .isInstanceOf(BusinessSlugImmutable.class)
                .hasNoCause();
        assertThatThrownBy(() -> administration.update(
                        active.id(), updateCommand("login", active.version())))
                .isInstanceOf(BusinessSlugImmutable.class);
        assertThat(administration.get(active.id())).isEqualTo(active);

        var suspended = administration.suspendActive(active.id(), active.version());
        assertThatThrownBy(() -> administration.update(
                        suspended.id(), updateCommand("changed-slug", suspended.version())))
                .isInstanceOf(BusinessSlugImmutable.class);
        assertThat(administration.get(suspended.id())).isEqualTo(suspended);
    }

    @Test
    void activeAndSuspendedBusinessesAcceptAnUnchangedSlugWithOtherProfileChanges() {
        var draft = administration.create(createCommand("kept-slug"));
        var active = administration.activateDraft(draft.id(), draft.version());

        var activeUpdated = administration.update(
                active.id(), updateCommand("  KEPT-SLUG ", active.version()));
        var suspended = administration.suspendActive(active.id(), activeUpdated.version());
        var suspendedUpdated = administration.update(
                suspended.id(), updateCommand("kept-slug", suspended.version()));

        assertThat(activeUpdated.slug()).isEqualTo("kept-slug");
        assertThat(activeUpdated.displayName()).isEqualTo("Updated Business");
        assertThat(activeUpdated.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(suspendedUpdated.slug()).isEqualTo("kept-slug");
        assertThat(suspendedUpdated.status()).isEqualTo(BusinessStatus.SUSPENDED);
        assertThat(suspendedUpdated.version()).isEqualTo(suspended.version() + 1);
    }

    @Test
    void existingActiveAndSuspendedBusinessesKeepReservedSlugs() {
        UUID activeId = insertBusiness("api", "ACTIVE");
        UUID suspendedId = insertBusiness("admin", "SUSPENDED");

        assertThat(administration.update(activeId, updateCommand("api", 0)).slug())
                .isEqualTo("api");
        assertThat(administration.update(suspendedId, updateCommand("admin", 0)).slug())
                .isEqualTo("admin");
        var reactivated = administration.reactivateSuspended(suspendedId, 1);

        assertThat(reactivated.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(reactivated.slug()).isEqualTo("admin");
    }

    @Test
    void slugFailuresRemainDistinctFromUniquenessAndVersionConflicts() {
        var existing = administration.create(createCommand("taken-slug"));
        var draft = administration.create(createCommand("free-slug"));
        var activeSource = administration.create(createCommand("active-slug"));
        var active = administration.activateDraft(activeSource.id(), activeSource.version());

        assertThatThrownBy(() -> administration.update(
                        draft.id(), updateCommand(existing.slug(), draft.version())))
                .isInstanceOf(BusinessSlugConflict.class);
        assertThatThrownBy(() -> administration.update(
                        draft.id(), updateCommand("login", draft.version())))
                .isInstanceOf(InvalidInput.class);
        assertThatThrownBy(() -> administration.update(
                        active.id(), updateCommand("other-slug", active.version())))
                .isInstanceOf(BusinessSlugImmutable.class);
        assertThatThrownBy(() -> administration.update(
                        active.id(), updateCommand("other-slug", active.version() + 5)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> administration.update(
                        draft.id(), updateCommand("login", draft.version() + 5)))
                .isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void activationHoldingTheRowLockMakesARacingSlugChangeFailAndKeepsTheSlug() {
        var draft = administration.create(createCommand("race-original"));
        var race = new LockedRace();

        try {
            race.holdFirst(() -> administration.activateDraft(draft.id(), draft.version()));
            race.startSecond(() -> administration.update(
                    draft.id(), updateCommand("race-renamed", draft.version())));
            LockWait wait = race.awaitSecondBlocked();
            assertThat(wait.waitEventType()).isEqualTo("Lock");
            assertThat(race.secondCompleted()).isFalse();

            race.releaseFirst();

            assertThat(race.firstOutcome()).isEqualTo(ConcurrentResult.SUCCESS);
            assertThat(race.secondOutcome()).isEqualTo(ConcurrentResult.CONFLICT);
            assertThat(race.firstPid()).isNotEqualTo(race.secondPid());
        } finally {
            race.close();
        }

        var stored = administration.get(draft.id());
        assertThat(stored.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(stored.slug()).isEqualTo("race-original");
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void slugChangeHoldingTheRowLockMakesARacingActivationFail() {
        var draft = administration.create(createCommand("race-first"));
        var race = new LockedRace();

        try {
            race.holdFirst(() -> administration.update(
                    draft.id(), updateCommand("race-second", draft.version())));
            race.startSecond(() -> administration.activateDraft(draft.id(), draft.version()));
            assertThat(race.awaitSecondBlocked().waitEventType()).isEqualTo("Lock");

            race.releaseFirst();

            assertThat(race.firstOutcome()).isEqualTo(ConcurrentResult.SUCCESS);
            assertThat(race.secondOutcome()).isEqualTo(ConcurrentResult.CONFLICT);
        } finally {
            race.close();
        }

        var stored = administration.get(draft.id());
        assertThat(stored.status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(stored.slug()).isEqualTo("race-second");
        assertThat(stored.version()).isEqualTo(1);
    }

    @Test
    void racingActivationAndSlugChangeNeverLeavesAnActiveBusinessWithAChangedSlug() {
        var draft = administration.create(createCommand("free-race-original"));

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> administration.activateDraft(draft.id(), draft.version()),
                () -> administration.update(
                        draft.id(), updateCommand("free-race-renamed", draft.version())));

        assertSeparateConnectionsOneSuccessAndOneConflict(outcomes);
        var stored = administration.get(draft.id());
        assertThat(stored.version()).isEqualTo(1);
        if (stored.status() == BusinessStatus.ACTIVE) {
            assertThat(stored.slug()).isEqualTo("free-race-original");
        } else {
            assertThat(stored.status()).isEqualTo(BusinessStatus.DRAFT);
            assertThat(stored.slug()).isEqualTo("free-race-renamed");
        }
    }

    private List<ConcurrentOutcome> runConcurrently(
            Supplier<?> first, Supplier<?> second) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ConcurrentOutcome> firstResult =
                    submit(executor, transaction, ready, start, first);
            CompletableFuture<ConcurrentOutcome> secondResult =
                    submit(executor, transaction, ready, start, second);
            ready.await();
            start.countDown();
            return List.of(firstResult.join(), secondResult.join());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private CompletableFuture<ConcurrentOutcome> submit(
            ExecutorService executor,
            TransactionTemplate transaction,
            CountDownLatch ready,
            CountDownLatch start,
            Supplier<?> action) {
        return CompletableFuture.supplyAsync(
                () -> {
                    var backendPid = new AtomicInteger();
                    try {
                        transaction.executeWithoutResult(status -> {
                            backendPid.set(jdbc.sql("SELECT pg_backend_pid()")
                                    .query(Integer.class)
                                    .single());
                            ready.countDown();
                            await(start);
                            action.get();
                        });
                        return new ConcurrentOutcome(
                                backendPid.get(), ConcurrentResult.SUCCESS);
                    } catch (ConcurrentUpdate exception) {
                        return new ConcurrentOutcome(
                                backendPid.get(), ConcurrentResult.CONFLICT);
                    }
                },
                executor);
    }

    private void assertSeparateConnectionsOneSuccessAndOneConflict(
            List<ConcurrentOutcome> outcomes) {
        assertThat(outcomes).extracting(ConcurrentOutcome::backendPid).doesNotHaveDuplicates();
        assertThat(outcomes).extracting(ConcurrentOutcome::result)
                .containsExactlyInAnyOrder(ConcurrentResult.SUCCESS, ConcurrentResult.CONFLICT);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private CreateBusinessCommand createCommand(String slug) {
        return new CreateBusinessCommand(
                slug,
                "Business " + slug,
                BusinessType.OTHER,
                "Europe/Sofia",
                "Description " + slug,
                "Sofia",
                "1000",
                "Example",
                "1",
                "Address " + slug,
                "+359 2 000 0000",
                slug + "@example.invalid");
    }

    private UpdateBusinessCommand updateCommand(String slug, long expectedVersion) {
        return new UpdateBusinessCommand(
                slug,
                "Updated Business",
                BusinessType.MASSAGE_STUDIO,
                "Europe/London",
                "Updated description",
                "Plovdiv",
                "4000",
                "Main",
                "2",
                "Updated address",
                "+359 2 111 1111",
                "updated@example.invalid",
                expectedVersion);
    }

    private UUID insertBusiness(String slug, Instant createdAt) {
        UUID id = UUID.randomUUID();
        insertBusiness(id, slug, createdAt);
        return id;
    }

    private void insertBusiness(UUID id, String slug, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            version, created_at, updated_at)
                        VALUES (
                            :id, :slug, :displayName, 'OTHER', 'DRAFT', 'Europe/Sofia',
                            0, :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("displayName", "Business " + slug)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private long businessCount() {
        return jdbc.sql("SELECT count(*) FROM business").query(Long.class).single();
    }

    private UUID insertBusiness(String slug, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            version, created_at, updated_at)
                        VALUES (
                            :id, :slug, :displayName, 'OTHER', :status, 'Europe/Sofia',
                            0, :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("displayName", "Business " + slug)
                .param("status", status)
                .param("createdAt", NOW.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    /**
     * Coordinates one transaction that keeps the Business row locked and a second one that is
     * proven, through {@code pg_stat_activity}, to be waiting on that lock before the first is
     * released. No sleeps: every step waits on a latch or a bounded database observation.
     */
    private final class LockedRace {
        private final CountDownLatch firstLocked = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch secondReady = new CountDownLatch(1);
        private final AtomicInteger firstPid = new AtomicInteger();
        private final AtomicInteger secondPid = new AtomicInteger();
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private CompletableFuture<ConcurrentResult> first;
        private CompletableFuture<ConcurrentResult> second;

        void holdFirst(Supplier<?> action) {
            first = CompletableFuture.supplyAsync(
                    () -> run(action, firstPid, firstLocked, releaseFirst), executor);
            awaitLatch(firstLocked);
        }

        void startSecond(Supplier<?> action) {
            second = CompletableFuture.supplyAsync(
                    () -> run(action, secondPid, secondReady, null), executor);
            awaitLatch(secondReady);
        }

        LockWait awaitSecondBlocked() {
            long deadline = System.nanoTime() + COORDINATION_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                var wait = jdbc.sql("""
                                SELECT wait_event_type, wait_event
                                FROM pg_stat_activity
                                WHERE pid = :pid AND wait_event_type = 'Lock'
                                """)
                        .param("pid", secondPid.get())
                        .query((resultSet, rowNumber) -> new LockWait(
                                resultSet.getString("wait_event_type"),
                                resultSet.getString("wait_event")))
                        .optional();
                if (wait.isPresent()) {
                    return wait.orElseThrow();
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("second transaction did not enter a PostgreSQL lock wait");
        }

        boolean secondCompleted() {
            return second.isDone();
        }

        void releaseFirst() {
            releaseFirst.countDown();
        }

        ConcurrentResult firstOutcome() {
            return join(first);
        }

        ConcurrentResult secondOutcome() {
            return join(second);
        }

        int firstPid() {
            return firstPid.get();
        }

        int secondPid() {
            return secondPid.get();
        }

        void close() {
            releaseFirst.countDown();
            executor.shutdownNow();
            try {
                executor.awaitTermination(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private ConcurrentResult run(
                Supplier<?> action,
                AtomicInteger backendPid,
                CountDownLatch reached,
                CountDownLatch holdUntil) {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    backendPid.set(jdbc.sql("SELECT pg_backend_pid()")
                            .query(Integer.class)
                            .single());
                    if (holdUntil == null) {
                        reached.countDown();
                    }
                    action.get();
                    if (holdUntil != null) {
                        reached.countDown();
                        awaitLatch(holdUntil);
                    }
                });
                return ConcurrentResult.SUCCESS;
            } catch (ConcurrentUpdate exception) {
                return ConcurrentResult.CONFLICT;
            }
        }

        private ConcurrentResult join(CompletableFuture<ConcurrentResult> future) {
            try {
                return future.get(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
                throw new AssertionError(exception);
            }
        }

        private void awaitLatch(CountDownLatch latch) {
            try {
                if (!latch.await(COORDINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("coordination step timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
    }

    private record LockWait(String waitEventType, String waitEvent) {
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedBusinessAdministrationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    private enum ConcurrentResult {
        SUCCESS,
        CONFLICT
    }

    private record ConcurrentOutcome(int backendPid, ConcurrentResult result) {
    }
}
