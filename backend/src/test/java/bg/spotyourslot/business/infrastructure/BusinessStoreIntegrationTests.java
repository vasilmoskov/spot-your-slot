package bg.spotyourslot.business.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessTimezone;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-14T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-14T09:00:00Z");

    @Autowired BusinessStore store;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void createsDraftBusinessAtVersionZeroAndRetrievesEveryField() {
        var creation = newBusiness("created-business", CREATED_AT);

        BusinessRow created = store.create(creation);

        assertThat(created)
                .isEqualTo(new BusinessRow(
                        creation.id(),
                        creation.slug(),
                        creation.displayName(),
                        creation.businessType(),
                        BusinessStatus.DRAFT,
                        creation.timezone(),
                        creation.description(),
                        creation.address(),
                        creation.phone(),
                        creation.contactEmail(),
                        0,
                        CREATED_AT,
                        CREATED_AT));
        assertThat(store.findById(creation.id())).contains(created);
    }

    @Test
    void returnsEmptyForUnknownBusiness() {
        assertThat(store.findById(UUID.fromString("00000000-0000-0000-0000-000000000099")))
                .isEmpty();
    }

    @Test
    void countsEmptyAndPopulatedBusinessData() {
        assertThat(store.count()).isZero();

        store.create(newBusiness("count-one", CREATED_AT));
        store.create(newBusiness("count-two", CREATED_AT.plusSeconds(1)));

        assertThat(store.count()).isEqualTo(2);
    }

    @Test
    void listsDeterministicallyByCreationTimeThenIdDescending() {
        UUID oldestId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        store.create(newBusiness(oldestId, "older", CREATED_AT));
        store.create(newBusiness(lowerId, "same-time-lower", CREATED_AT.plusSeconds(1)));
        store.create(newBusiness(higherId, "same-time-higher", CREATED_AT.plusSeconds(1)));

        assertThat(store.list(0, 10))
                .extracting(row -> row.slug().value())
                .containsExactly("same-time-higher", "same-time-lower", "older");
    }

    @Test
    void returnsDeterministicNonOverlappingPages() {
        for (int index = 0; index < 5; index++) {
            store.create(newBusiness("page-" + index, CREATED_AT.plusSeconds(index)));
        }

        var firstPage = store.list(0, 2);
        var secondPage = store.list(1, 2);
        var thirdPage = store.list(2, 2);

        assertThat(firstPage).extracting(row -> row.slug().value())
                .containsExactly("page-4", "page-3");
        assertThat(secondPage).extracting(row -> row.slug().value())
                .containsExactly("page-2", "page-1");
        assertThat(thirdPage).extracting(row -> row.slug().value())
                .containsExactly("page-0");
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void rejectsInvalidPaginationBeforeExecutingSql(int page, int size) {
        assertThatThrownBy(() -> store.list(page, size))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void letsPostgresqlRejectDuplicateSlugOnCreation() {
        store.create(newBusiness("duplicate-slug", CREATED_AT));

        assertThatThrownBy(() -> store.create(newBusiness(
                        "duplicate-slug", CREATED_AT.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void letsPostgresqlRejectDuplicateSlugOnProfileUpdate() {
        store.create(newBusiness("existing-slug", CREATED_AT));
        BusinessRow target = store.create(newBusiness("target-slug", CREATED_AT));

        assertThatThrownBy(() -> store.updateProfile(
                        target.id(), profileUpdate(target, "existing-slug", 0, UPDATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updatesOnlyApprovedProfileFieldsAndIncrementsVersionOnce() {
        BusinessRow original = store.create(newBusiness("original-profile", CREATED_AT));
        var update = new BusinessProfileUpdateRow(
                new BusinessSlug("updated-profile"),
                "Updated Business",
                BusinessType.MASSAGE_STUDIO,
                new BusinessTimezone("Europe/London"),
                "Updated description",
                "Updated address",
                "+359 2 111 1111",
                "updated@example.invalid",
                original.version(),
                UPDATED_AT);

        BusinessRow updated = store.updateProfile(original.id(), update).orElseThrow();

        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.slug()).isEqualTo(update.slug());
        assertThat(updated.displayName()).isEqualTo(update.displayName());
        assertThat(updated.businessType()).isEqualTo(update.businessType());
        assertThat(updated.status()).isEqualTo(BusinessStatus.DRAFT);
        assertThat(updated.timezone()).isEqualTo(update.timezone());
        assertThat(updated.description()).isEqualTo(update.description());
        assertThat(updated.address()).isEqualTo(update.address());
        assertThat(updated.phone()).isEqualTo(update.phone());
        assertThat(updated.contactEmail()).isEqualTo(update.contactEmail());
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void staleProfileVersionReturnsEmptyWithoutMutation() {
        BusinessRow original = store.create(newBusiness("stale-profile", CREATED_AT));

        assertThat(store.updateProfile(
                        original.id(), profileUpdate(original, "not-applied", 1, UPDATED_AT)))
                .isEmpty();
        assertThat(store.findById(original.id())).contains(original);
    }

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void atomicallyAppliesAllowedLifecycleTransitions(
            BusinessStatus current, BusinessStatus target) {
        BusinessRow original = businessInStatus(current, 4);

        BusinessRow updated = store.transition(
                        original.id(), current, target, original.version(), UPDATED_AT)
                .orElseThrow();

        assertThat(updated.status()).isEqualTo(target);
        assertThat(updated.version()).isEqualTo(5);
        assertThat(updated.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @ParameterizedTest
    @MethodSource("rejectedTransitions")
    void rejectsDisallowedLifecyclePairsBeforeMutation(
            BusinessStatus current, BusinessStatus target) {
        BusinessRow original = businessInStatus(current, 4);

        assertThatThrownBy(() -> store.transition(
                        original.id(), current, target, original.version(), UPDATED_AT))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(store.findById(original.id())).contains(original);
    }

    @Test
    void lifecycleStatusMismatchReturnsEmptyWithoutMutation() {
        BusinessRow original = businessInStatus(BusinessStatus.ACTIVE, 2);

        assertThat(store.transition(
                        original.id(),
                        BusinessStatus.DRAFT,
                        BusinessStatus.ACTIVE,
                        original.version(),
                        UPDATED_AT))
                .isEmpty();
        assertThat(store.findById(original.id())).contains(original);
    }

    @Test
    void concurrentProfileWritersUsingOneVersionProduceExactlyOneSuccess() {
        BusinessRow original = store.create(newBusiness("concurrent-profile", CREATED_AT));

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> store.updateProfile(
                        original.id(),
                        profileUpdate(original, "first-writer", 0, UPDATED_AT)),
                () -> store.updateProfile(
                        original.id(),
                        profileUpdate(original, "second-writer", 0, UPDATED_AT.plusSeconds(1))));

        assertSeparateConnectionsAndOneSuccess(outcomes);
        BusinessRow stored = store.findById(original.id()).orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.slug().value()).isIn("first-writer", "second-writer");
    }

    @Test
    void concurrentLifecycleWritersUsingOneVersionProduceExactlyOneSuccess() {
        BusinessRow original = store.create(newBusiness("concurrent-lifecycle", CREATED_AT));

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> store.transition(
                        original.id(), BusinessStatus.DRAFT, BusinessStatus.ACTIVE, 0, UPDATED_AT),
                () -> store.transition(
                        original.id(),
                        BusinessStatus.DRAFT,
                        BusinessStatus.ACTIVE,
                        0,
                        UPDATED_AT.plusSeconds(1)));

        assertSeparateConnectionsAndOneSuccess(outcomes);
        BusinessRow stored = store.findById(original.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(BusinessStatus.ACTIVE);
        assertThat(stored.version()).isEqualTo(1);
    }

    private BusinessRow businessInStatus(BusinessStatus status, long version) {
        BusinessRow created = store.create(newBusiness(
                "status-" + status.name().toLowerCase().replace('_', '-'), CREATED_AT));
        jdbc.sql("""
                        UPDATE business
                        SET status = :status, version = :version
                        WHERE id = :id
                        """)
                .param("status", status.name())
                .param("version", version)
                .param("id", created.id())
                .update();
        return store.findById(created.id()).orElseThrow();
    }

    private BusinessProfileUpdateRow profileUpdate(
            BusinessRow original, String slug, long expectedVersion, Instant updatedAt) {
        return new BusinessProfileUpdateRow(
                new BusinessSlug(slug),
                original.displayName(),
                original.businessType(),
                original.timezone(),
                original.description(),
                original.address(),
                original.phone(),
                original.contactEmail(),
                expectedVersion,
                updatedAt);
    }

    private NewBusinessRow newBusiness(String slug, Instant createdAt) {
        return newBusiness(UUID.randomUUID(), slug, createdAt);
    }

    private NewBusinessRow newBusiness(UUID id, String slug, Instant createdAt) {
        return new NewBusinessRow(
                id,
                new BusinessSlug(slug),
                "Business " + slug,
                BusinessType.OTHER,
                BusinessTimezone.defaultTimezone(),
                "Description " + slug,
                "Address " + slug,
                "+359 2 000 0000",
                slug + "@example.invalid",
                createdAt);
    }

    private List<ConcurrentOutcome> runConcurrently(
            Supplier<Optional<BusinessRow>> first,
            Supplier<Optional<BusinessRow>> second) {
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
            Supplier<Optional<BusinessRow>> action) {
        return CompletableFuture.supplyAsync(
                () -> transaction.execute(status -> {
                    int backendPid = jdbc.sql("SELECT pg_backend_pid()")
                            .query(Integer.class)
                            .single();
                    ready.countDown();
                    await(start);
                    return new ConcurrentOutcome(backendPid, action.get().isPresent());
                }),
                executor);
    }

    private void assertSeparateConnectionsAndOneSuccess(List<ConcurrentOutcome> outcomes) {
        assertThat(outcomes).extracting(ConcurrentOutcome::backendPid).doesNotHaveDuplicates();
        assertThat(outcomes).extracting(ConcurrentOutcome::successful)
                .containsExactlyInAnyOrder(true, false);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static Stream<Arguments> invalidPages() {
        return Stream.of(
                Arguments.of(-1, 10),
                Arguments.of(0, 0),
                Arguments.of(0, -1),
                Arguments.of(0, 101));
    }

    private static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(BusinessStatus.DRAFT, BusinessStatus.ACTIVE),
                Arguments.of(BusinessStatus.ACTIVE, BusinessStatus.SUSPENDED),
                Arguments.of(BusinessStatus.SUSPENDED, BusinessStatus.ACTIVE));
    }

    private static Stream<Arguments> rejectedTransitions() {
        return Stream.of(BusinessStatus.values())
                .flatMap(current -> Stream.of(BusinessStatus.values())
                        .filter(target -> !current.canTransitionTo(target))
                        .map(target -> Arguments.of(current, target)));
    }

    private record ConcurrentOutcome(int backendPid, boolean successful) {
    }
}
