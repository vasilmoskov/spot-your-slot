package bg.spotyourslot.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.application.ServiceInputValidator;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.NameConflict;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ServiceStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Instant CREATED_AT = Instant.parse("2026-09-15T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-15T09:00:00Z");
    private static final int[] APPROVED_WHITESPACE_CODE_POINTS = {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
        0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
        0x3000
    };

    @Autowired
    ServiceStore store;

    @Autowired
    ServiceInputValidator validator;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void createsActiveVersionZeroServiceUsingApplicationSuppliedValues() {
        UUID businessId = createBusiness();
        var creation = newService(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                businessId,
                "Подстригване",
                "Описание\nс два реда",
                45,
                new BigDecimal("25.50"),
                CREATED_AT);

        ServiceRow created = store.create(creation);

        assertThat(created).isEqualTo(new ServiceRow(
                creation.id(),
                businessId,
                creation.name(),
                creation.description(),
                creation.durationMinutes(),
                creation.price(),
                true,
                0,
                CREATED_AT,
                CREATED_AT));
        assertThat(store.findByBusinessIdAndId(businessId, creation.id())).contains(created);
    }

    @Test
    void listsAndCountsActiveAndInactiveServicesOnlyInsideOneBusiness() {
        UUID firstBusiness = createBusiness();
        UUID secondBusiness = createBusiness();
        ServiceRow active = store.create(newService(firstBusiness, "Активна", CREATED_AT));
        ServiceRow inactive = store.create(newService(firstBusiness, "Неактивна", CREATED_AT));
        inactive = store.deactivate(
                        firstBusiness, inactive.id(), inactive.version(), UPDATED_AT)
                .orElseThrow();
        store.create(newService(secondBusiness, "Чужда", CREATED_AT));

        assertThat(store.list(firstBusiness, 0, 50))
                .containsExactlyInAnyOrder(active, inactive);
        assertThat(store.count(firstBusiness)).isEqualTo(2);
        assertThat(store.count(secondBusiness)).isEqualTo(1);
    }

    @Test
    void ordersAndPagesByNormalizedNameThenIdWithoutOverlap() {
        UUID businessId = createBusiness();
        store.create(newService(businessId, "gamma", CREATED_AT));
        store.create(newService(businessId, "Alpha", CREATED_AT));
        store.create(newService(businessId, "beta", CREATED_AT));
        store.create(newService(businessId, "delta", CREATED_AT));
        store.create(newService(businessId, "epsilon", CREATED_AT));

        List<ServiceRow> first = store.list(businessId, 0, 2);
        List<ServiceRow> second = store.list(businessId, 1, 2);
        List<ServiceRow> third = store.list(businessId, 2, 2);

        assertThat(first).extracting(ServiceRow::name).containsExactly("Alpha", "beta");
        assertThat(second).extracting(ServiceRow::name).containsExactly("delta", "epsilon");
        assertThat(third).extracting(ServiceRow::name).containsExactly("gamma");
        assertThat(store.list(businessId, 0, 10))
                .containsExactlyElementsOf(concatenate(first, second, third));
    }

    @Test
    void tenantScopedIdentityPreventsCrossBusinessReadsAndMutations() {
        UUID ownerBusiness = createBusiness();
        UUID otherBusiness = createBusiness();
        ServiceRow original = store.create(newService(ownerBusiness, "Защитена", CREATED_AT));
        var update = update(original, "Чужда промяна", original.version(), UPDATED_AT);

        assertThat(store.findByBusinessIdAndId(otherBusiness, original.id())).isEmpty();
        assertThat(store.update(otherBusiness, original.id(), update)).isEmpty();
        assertThat(store.deactivate(
                        otherBusiness, original.id(), original.version(), UPDATED_AT))
                .isEmpty();

        ServiceRow inactive = store.deactivate(
                        ownerBusiness, original.id(), original.version(), UPDATED_AT)
                .orElseThrow();
        assertThat(store.reactivate(
                        otherBusiness,
                        inactive.id(),
                        inactive.version(),
                        UPDATED_AT.plusSeconds(1)))
                .isEmpty();
        assertThat(store.findByBusinessIdAndId(ownerBusiness, original.id()))
                .contains(inactive);
    }

    @Test
    void sameNormalizedNameIsAllowedInDifferentBusinesses() {
        UUID firstBusiness = createBusiness();
        UUID secondBusiness = createBusiness();

        assertThat(store.create(newService(firstBusiness, "Straße", CREATED_AT)).name())
                .isEqualTo("Straße");
        assertThat(store.create(newService(secondBusiness, "STRASSE", CREATED_AT)).name())
                .isEqualTo("STRASSE");
    }

    @Test
    void inactiveServiceContinuesToReserveItsNormalizedName() {
        UUID businessId = createBusiness();
        ServiceRow service = store.create(newService(businessId, "Подстригване", CREATED_AT));
        store.deactivate(businessId, service.id(), 0, UPDATED_AT).orElseThrow();

        assertThatThrownBy(() -> store.create(
                        newService(businessId, " ПОДСТРИГВАНЕ ", UPDATED_AT)))
                .isInstanceOf(NameConflict.class)
                .hasMessage("Service name is already reserved")
                .hasNoCause();
    }

    @Test
    void updatesActiveAndInactiveServicesAndReleasesOldNameOnlyAfterSuccess() {
        UUID businessId = createBusiness();
        ServiceRow reserved = store.create(newService(businessId, "Резервирано", CREATED_AT));
        ServiceRow target = store.create(newService(businessId, "Старо име", CREATED_AT));

        assertThatThrownBy(() -> store.update(
                        businessId,
                        target.id(),
                        update(target, "РЕЗЕРВИРАНО", 0, UPDATED_AT)))
                .isInstanceOf(NameConflict.class);
        assertThat(store.findByBusinessIdAndId(businessId, target.id())).contains(target);
        assertThatThrownBy(() -> store.create(
                        newService(businessId, "старо име", UPDATED_AT)))
                .isInstanceOf(NameConflict.class);

        ServiceRow renamed = store.update(
                        businessId,
                        target.id(),
                        update(target, "Ново име", 0, UPDATED_AT))
                .orElseThrow();
        assertThat(renamed.version()).isEqualTo(1);
        assertThat(renamed.createdAt()).isEqualTo(CREATED_AT);
        assertThat(renamed.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(store.create(newService(businessId, "СТАРО ИМЕ", UPDATED_AT)).name())
                .isEqualTo("СТАРО ИМЕ");

        ServiceRow inactive = store.deactivate(
                        businessId, reserved.id(), reserved.version(), UPDATED_AT)
                .orElseThrow();
        ServiceRow updatedInactive = store.update(
                        businessId,
                        inactive.id(),
                        update(
                                inactive,
                                "Променена неактивна",
                                inactive.version(),
                                UPDATED_AT.plusSeconds(1)))
                .orElseThrow();
        assertThat(updatedInactive.active()).isFalse();
        assertThat(updatedInactive.version()).isEqualTo(2);
    }

    @Test
    void updatePersistsEveryEditableFieldAndPreservesIdentityOwnershipAndCreationTime() {
        UUID businessId = createBusiness();
        ServiceRow original = store.create(newService(businessId, "Първоначална", CREATED_AT));
        var update = new ServiceUpdateRow(
                "Обновена услуга",
                "Обновено описание\nс нов ред",
                480,
                new BigDecimal("9999999999.99"),
                original.version(),
                UPDATED_AT);

        ServiceRow updated = store.update(businessId, original.id(), update).orElseThrow();

        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.businessId()).isEqualTo(original.businessId());
        assertThat(updated.name()).isEqualTo(update.name());
        assertThat(updated.description()).isEqualTo(update.description());
        assertThat(updated.durationMinutes()).isEqualTo(update.durationMinutes());
        assertThat(updated.price()).isEqualByComparingTo(update.price());
        assertThat(updated.active()).isTrue();
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.createdAt()).isEqualTo(CREATED_AT);
        assertThat(updated.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void lifecycleMutationsChangeStateAndVersionExactlyOnce() {
        UUID businessId = createBusiness();
        ServiceRow original = store.create(newService(businessId, "Жизнен цикъл", CREATED_AT));

        ServiceRow inactive = store.deactivate(businessId, original.id(), 0, UPDATED_AT)
                .orElseThrow();
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(1);
        assertThat(inactive.createdAt()).isEqualTo(CREATED_AT);
        assertThat(inactive.updatedAt()).isEqualTo(UPDATED_AT);

        Instant reactivatedAt = UPDATED_AT.plusSeconds(1);
        ServiceRow active = store.reactivate(
                        businessId, original.id(), 1, reactivatedAt)
                .orElseThrow();
        assertThat(active.active()).isTrue();
        assertThat(active.version()).isEqualTo(2);
        assertThat(active.createdAt()).isEqualTo(CREATED_AT);
        assertThat(active.updatedAt()).isEqualTo(reactivatedAt);
    }

    @Test
    void staleAndStateMismatchPredicatesReturnEmptyWithoutChangingStoredState() {
        UUID businessId = createBusiness();
        ServiceRow original = store.create(newService(businessId, "Без промяна", CREATED_AT));

        assertThat(store.update(
                        businessId,
                        original.id(),
                        update(original, "Неуспешно", 1, UPDATED_AT)))
                .isEmpty();
        assertThat(store.reactivate(businessId, original.id(), 0, UPDATED_AT)).isEmpty();
        assertThat(store.findByBusinessIdAndId(businessId, original.id())).contains(original);

        ServiceRow inactive = store.deactivate(businessId, original.id(), 0, UPDATED_AT)
                .orElseThrow();
        assertThat(store.deactivate(
                        businessId, original.id(), inactive.version(), UPDATED_AT.plusSeconds(1)))
                .isEmpty();
        assertThat(store.reactivate(
                        businessId, original.id(), 0, UPDATED_AT.plusSeconds(1)))
                .isEmpty();
        assertThat(store.findByBusinessIdAndId(businessId, original.id())).contains(inactive);
    }

    @Test
    void classifiesOnlyTheApprovedNameConstraintAndSanitizesOtherFailures() {
        UUID businessId = createBusiness();
        ServiceRow first = store.create(newService(businessId, "Първа", CREATED_AT));

        assertThatThrownBy(() -> store.create(newService(businessId, "ПЪРВА", CREATED_AT)))
                .isInstanceOf(NameConflict.class)
                .hasMessage("Service name is already reserved")
                .hasNoCause();
        UnexpectedFailure primaryKeyFailure = org.junit.jupiter.api.Assertions.assertThrows(
                UnexpectedFailure.class,
                () -> store.create(newService(
                        first.id(),
                        businessId,
                        "Различно име",
                        null,
                        30,
                        BigDecimal.TEN,
                        CREATED_AT)));
        assertSafeUnexpectedFailure(primaryKeyFailure, "service_pkey");

        UnexpectedFailure foreignKeyFailure = org.junit.jupiter.api.Assertions.assertThrows(
                UnexpectedFailure.class,
                () -> store.create(newService(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Липсващ бизнес",
                        null,
                        30,
                        BigDecimal.TEN,
                        CREATED_AT)));
        assertSafeUnexpectedFailure(foreignKeyFailure, "service_business_fk");
    }

    @Test
    void nameConflictClassifierRequiresExactConstraintAndUniqueSqlState() {
        String approvedConstraint = "service_business_normalized_name_unique";

        assertThat(ServiceStore.isNameConflict(dataFailure(
                        "duplicate violates " + approvedConstraint, "23505")))
                .isTrue();
        assertThat(ServiceStore.isNameConflict(dataFailure(
                        "check violates " + approvedConstraint, "23514")))
                .isFalse();
        assertThat(ServiceStore.isNameConflict(dataFailure(
                        "duplicate violates service_pkey", "23505")))
                .isFalse();
        assertThat(ServiceStore.isNameConflict(dataFailure(
                        "duplicate violates " + approvedConstraint + "_suffix", "23505")))
                .isFalse();
        assertThat(ServiceStore.isNameConflict(dataFailure(
                        "duplicate violates prefix_" + approvedConstraint, "23505")))
                .isFalse();
    }

    @Test
    void javaCanonicalizationMatchesV5AcrossWhitespaceAndUnicodeBoundaries() {
        UUID businessId = createBusiness();

        for (int index = 0; index < APPROVED_WHITESPACE_CODE_POINTS.length; index++) {
            String whitespace = Character.toString(APPROVED_WHITESPACE_CODE_POINTS[index]);
            CreateServiceCommand validated = validator.validateCreate(new CreateServiceCommand(
                    whitespace + "Име" + whitespace + whitespace + index + whitespace,
                    whitespace + "Описание\n  ред " + index + whitespace,
                    30,
                    BigDecimal.TEN));
            ServiceRow stored = store.create(new NewServiceRow(
                    UUID.randomUUID(),
                    businessId,
                    validated.name(),
                    validated.description(),
                    validated.durationMinutes(),
                    validated.price(),
                    CREATED_AT));
            assertThat(stored.name()).isEqualTo("Име " + index);
            assertThat(stored.description()).isEqualTo("Описание\n  ред " + index);
        }

        CreateServiceCommand nfkc = validator.validateCreate(new CreateServiceCommand(
                " \uFB03 e\u0301 😀 ", " \uFB03\ne\u0301 😀 ", 30, BigDecimal.TEN));
        ServiceRow stored = store.create(new NewServiceRow(
                UUID.randomUUID(),
                businessId,
                nfkc.name(),
                nfkc.description(),
                nfkc.durationMinutes(),
                nfkc.price(),
                CREATED_AT));
        assertThat(stored.name()).isEqualTo("ffi é 😀");
        assertThat(stored.description()).isEqualTo("ffi\né 😀");
        assertThat(normalizedName(stored.id())).isEqualTo("ffi é 😀");
    }

    @Test
    void javaAndPostgresAgreeOnCanonicalUnicodeCodePointLengthBoundaries() {
        UUID businessId = createBusiness();
        String nameAtLimit = "😀".repeat(200);
        String descriptionAtLimit = "😀".repeat(2_000);

        CreateServiceCommand accepted = validator.validateCreate(new CreateServiceCommand(
                nameAtLimit, descriptionAtLimit, 30, BigDecimal.TEN));
        ServiceRow stored = store.create(new NewServiceRow(
                UUID.randomUUID(),
                businessId,
                accepted.name(),
                accepted.description(),
                accepted.durationMinutes(),
                accepted.price(),
                CREATED_AT));

        assertThat(stored.name().codePointCount(0, stored.name().length())).isEqualTo(200);
        assertThat(stored.description().codePointCount(0, stored.description().length()))
                .isEqualTo(2_000);
        assertThatThrownBy(() -> validator.validateCreate(new CreateServiceCommand(
                        "😀".repeat(201), null, 30, BigDecimal.TEN)))
                .isInstanceOf(bg.spotyourslot.catalog.ServiceApplicationException.InvalidInput.class);
        assertThatThrownBy(() -> validator.validateCreate(new CreateServiceCommand(
                        "Име", "😀".repeat(2_001), 30, BigDecimal.TEN)))
                .isInstanceOf(bg.spotyourslot.catalog.ServiceApplicationException.InvalidInput.class);
    }

    @Test
    void concurrentSameVersionUpdatesHaveOneWinnerOnSeparateConnections() {
        UUID businessId = createBusiness();
        ServiceRow original = store.create(newService(businessId, "Една версия", CREATED_AT));

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> store.update(
                                businessId,
                                original.id(),
                                update(original, "Първи", 0, UPDATED_AT))
                        .isPresent(),
                () -> store.update(
                                businessId,
                                original.id(),
                                update(original, "Втори", 0, UPDATED_AT.plusSeconds(1)))
                        .isPresent());

        assertSeparateConnections(outcomes);
        assertThat(outcomes).extracting(ConcurrentOutcome::successful)
                .containsExactlyInAnyOrder(true, false);
        assertThat(outcomes).extracting(ConcurrentOutcome::failure).containsOnlyNulls();
        ServiceRow stored = store.findByBusinessIdAndId(businessId, original.id()).orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.name()).isIn("Първи", "Втори");
    }

    @Test
    void concurrentDuplicateCreatesHaveOneWinnerOnSeparateConnections() {
        UUID businessId = createBusiness();

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> store.create(newService(businessId, "Straße", CREATED_AT)) != null,
                () -> store.create(newService(businessId, "STRASSE", CREATED_AT)) != null);

        assertOneSuccessAndOneNameConflict(outcomes);
        assertThat(store.count(businessId)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateRenamesHaveOneWinnerOnSeparateConnections() {
        UUID businessId = createBusiness();
        ServiceRow first = store.create(newService(businessId, "Първа", CREATED_AT));
        ServiceRow second = store.create(newService(businessId, "Втора", CREATED_AT));

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> store.update(
                                businessId,
                                first.id(),
                                update(first, "Общо име", 0, UPDATED_AT))
                        .isPresent(),
                () -> store.update(
                                businessId,
                                second.id(),
                                update(second, "ОБЩО ИМЕ", 0, UPDATED_AT.plusSeconds(1)))
                        .isPresent());

        assertOneSuccessAndOneNameConflict(outcomes);
        List<ServiceRow> stored = store.list(businessId, 0, 10);
        assertThat(stored).filteredOn(row -> row.version() == 1).hasSize(1);
        assertThat(stored).filteredOn(row -> row.version() == 0).hasSize(1);
        assertThat(stored).filteredOn(row -> row.updatedAt().equals(CREATED_AT)).hasSize(1);
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Service Store Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "service-store-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private NewServiceRow newService(UUID businessId, String name, Instant createdAt) {
        return newService(
                UUID.randomUUID(),
                businessId,
                name,
                "Описание за " + name,
                30,
                new BigDecimal("20.00"),
                createdAt);
    }

    private NewServiceRow newService(
            UUID id,
            UUID businessId,
            String name,
            String description,
            int durationMinutes,
            BigDecimal price,
            Instant createdAt) {
        CreateServiceCommand validated = validator.validateCreate(
                new CreateServiceCommand(name, description, durationMinutes, price));
        return new NewServiceRow(
                id,
                businessId,
                validated.name(),
                validated.description(),
                validated.durationMinutes(),
                validated.price(),
                createdAt);
    }

    private ServiceUpdateRow update(
            ServiceRow original, String name, long expectedVersion, Instant updatedAt) {
        return new ServiceUpdateRow(
                validator.validateCreate(new CreateServiceCommand(
                                name,
                                original.description(),
                                original.durationMinutes(),
                                original.price()))
                        .name(),
                original.description(),
                original.durationMinutes(),
                original.price(),
                expectedVersion,
                updatedAt);
    }

    private String normalizedName(UUID serviceId) {
        return jdbc.sql("SELECT normalized_name FROM service WHERE id = :id")
                .param("id", serviceId)
                .query(String.class)
                .single();
    }

    private static DataIntegrityViolationException dataFailure(
            String diagnostic, String sqlState) {
        return new DataIntegrityViolationException(
                "Spring persistence wrapper",
                new SQLException(diagnostic, sqlState));
    }

    private static void assertSafeUnexpectedFailure(
            UnexpectedFailure failure, String internalConstraint) {
        assertThat(failure)
                .hasMessage("Service persistence operation failed")
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure.getMessage())
                .doesNotContain(internalConstraint)
                .doesNotContain("duplicate key")
                .doesNotContain("org.postgresql")
                .doesNotContain("SQL");
        assertThat(failure.getCause()).isNotNull();
        assertThat(failure.getCause().getCause()).isInstanceOf(SQLException.class);
    }

    @SafeVarargs
    private static List<ServiceRow> concatenate(List<ServiceRow>... pages) {
        return java.util.Arrays.stream(pages).flatMap(List::stream).toList();
    }

    private List<ConcurrentOutcome> runConcurrently(
            Supplier<Boolean> first, Supplier<Boolean> second) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ConcurrentOutcome> firstResult =
                    submit(executor, transaction, ready, start, first);
            CompletableFuture<ConcurrentOutcome> secondResult =
                    submit(executor, transaction, ready, start, second);
            await(ready, "concurrent transactions did not become ready");
            start.countDown();
            return List.of(completed(firstResult), completed(secondResult));
        }
    }

    private CompletableFuture<ConcurrentOutcome> submit(
            ExecutorService executor,
            TransactionTemplate transaction,
            CountDownLatch ready,
            CountDownLatch start,
            Supplier<Boolean> action) {
        return CompletableFuture.supplyAsync(
                () -> {
                    AtomicInteger backendPid = new AtomicInteger();
                    try {
                        boolean successful = Boolean.TRUE.equals(transaction.execute(status -> {
                            backendPid.set(jdbc.sql("SELECT pg_backend_pid()")
                                    .query(Integer.class)
                                    .single());
                            ready.countDown();
                            await(start, "concurrent start signal was not released");
                            return action.get();
                        }));
                        return new ConcurrentOutcome(backendPid.get(), successful, null);
                    } catch (RuntimeException exception) {
                        return new ConcurrentOutcome(backendPid.get(), false, exception);
                    }
                },
                executor);
    }

    private void assertOneSuccessAndOneNameConflict(List<ConcurrentOutcome> outcomes) {
        assertSeparateConnections(outcomes);
        assertThat(outcomes).extracting(ConcurrentOutcome::successful)
                .containsExactlyInAnyOrder(true, false);
        assertThat(outcomes).extracting(ConcurrentOutcome::failure)
                .containsExactlyInAnyOrder(null, outcomes.stream()
                        .map(ConcurrentOutcome::failure)
                        .filter(NameConflict.class::isInstance)
                        .findFirst()
                        .orElseThrow());
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() instanceof NameConflict)
                .hasSize(1);
    }

    private void assertSeparateConnections(List<ConcurrentOutcome> outcomes) {
        assertThat(outcomes).extracting(ConcurrentOutcome::backendPid)
                .allMatch(pid -> pid > 0)
                .doesNotHaveDuplicates();
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
        } catch (TimeoutException exception) {
            throw new AssertionError("concurrent database operation did not finish", exception);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record ConcurrentOutcome(
            int backendPid, boolean successful, RuntimeException failure) {
    }
}
