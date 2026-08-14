package bg.spotyourslot.business.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.BusinessSlugConflict;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Autowired BusinessAdministration administration;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void createsDraftWithNormalizedValuesDefaultTimezoneAndFixedTimestamps() {
        var created = administration.create(new CreateBusinessCommand(
                "  Created-Business  ",
                "  Бизнес име  ",
                BusinessType.BEAUTY_STUDIO,
                null,
                "  Описание  ",
                "  Адрес  ",
                "  +359 2 000 0000  ",
                "  CONTACT@EXAMPLE.INVALID  "));

        assertThat(created.slug()).isEqualTo("created-business");
        assertThat(created.displayName()).isEqualTo("Бизнес име");
        assertThat(created.status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(created.timezone()).isEqualTo("Europe/Sofia");
        assertThat(created.description()).isEqualTo("Описание");
        assertThat(created.address()).isEqualTo("Адрес");
        assertThat(created.phone()).isEqualTo("+359 2 000 0000");
        assertThat(created.contactEmail()).isEqualTo("contact@example.invalid");
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        assertThat(administration.get(created.id())).isEqualTo(created);
    }

    @Test
    void listsDeterministicallyWithBoundedPaginationAndCount() {
        UUID firstId = insertBusiness("first", NOW.minusSeconds(2));
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        insertBusiness(lowerId, "same-lower", NOW.minusSeconds(1));
        insertBusiness(higherId, "same-higher", NOW.minusSeconds(1));

        var firstPage = administration.list(0, 2);
        var secondPage = administration.list(1, 2);

        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.businesses()).extracting(summary -> summary.id())
                .containsExactly(higherId, lowerId);
        assertThat(secondPage.businesses()).extracting(summary -> summary.id())
                .containsExactly(firstId);
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
        assertThat(updated.address()).isEqualTo("Updated address");
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

    private record ConcurrentOutcome(int backendPid, ConcurrentResult result) {}
}
