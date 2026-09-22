package bg.spotyourslot.workforce.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.application.StaffMemberInputValidator;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffMemberStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Duration COORDINATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Instant CREATED_AT = Instant.parse("2026-09-21T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-21T09:00:00Z");

    @Autowired
    StaffMemberStore store;

    @Autowired
    StaffMemberInputValidator validator;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void createsActiveVersionZeroStaffMemberUsingApplicationSuppliedValues() {
        UUID businessId = createBusiness();
        var creation = newStaff(
                UUID.fromString("00000000-0000-0000-0000-000000000101"),
                businessId,
                "Анна Иванова",
                "anna@example.invalid",
                "+359 (2) 123-45-67",
                CREATED_AT);

        StaffMemberRow created = store.create(creation);

        assertThat(created).isEqualTo(new StaffMemberRow(
                creation.id(),
                businessId,
                creation.displayName(),
                creation.contactEmail(),
                creation.contactPhone(),
                true,
                0,
                CREATED_AT,
                CREATED_AT));
        assertThat(store.findByBusinessIdAndId(businessId, creation.id()))
                .contains(created);
    }

    @Test
    void persistsExactCanonicalValuesProducedByWorkforceValidation() {
        UUID businessId = createBusiness();
        CreateStaffMemberCommand validated = validator.validateCreate(
                new CreateStaffMemberCommand(
                        "\u2003Ａｎｎａ\u00A0\u00A0Иванова\n",
                        "\u3000ＴＥＡＭ@ＥＸＡＭＰＬＥ.INVALID\u2009",
                        "\u2003＋３５９ (\uFF12) １２３-４５-６７\n"));

        StaffMemberRow stored = store.create(new NewStaffMemberRow(
                UUID.randomUUID(),
                businessId,
                validated.displayName(),
                validated.contactEmail(),
                validated.contactPhone(),
                CREATED_AT));

        assertThat(stored.displayName()).isEqualTo("Anna Иванова");
        assertThat(stored.contactEmail()).isEqualTo("team@example.invalid");
        assertThat(stored.contactPhone()).isEqualTo("+359 (2) 123-45-67");
    }

    @Test
    void allowsDuplicateDisplayNamesNormalizedNamesEmailsAndPhones() {
        UUID businessId = createBusiness();
        String email = "team@example.invalid";
        String phone = "+359 888 123 456";

        StaffMemberRow first = store.create(newStaff(
                businessId, "Straße", email, phone, CREATED_AT));
        StaffMemberRow second = store.create(newStaff(
                businessId, "STRASSE", email, phone, CREATED_AT));

        assertThat(store.count(businessId)).isEqualTo(2);
        assertThat(store.list(businessId, 0, 50))
                .extracting(StaffMemberRow::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(jdbc.sql("""
                        SELECT normalized_display_name
                        FROM staff_member
                        WHERE id IN (:first, :second)
                        ORDER BY id
                        """)
                .param("first", first.id())
                .param("second", second.id())
                .query(String.class)
                .list())
                .containsOnly("strasse");
    }

    @Test
    void ordersAndPagesByNormalizedDisplayNameThenIdInsideOneBusiness() {
        UUID businessId = createBusiness();
        UUID otherBusiness = createBusiness();
        UUID firstAlphaId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID secondAlphaId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID betaId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        UUID gammaId = UUID.fromString("00000000-0000-0000-0000-000000000104");

        store.create(newStaff(secondAlphaId, businessId, "ALPHA", null, null, CREATED_AT));
        store.create(newStaff(gammaId, businessId, "gamma", null, null, CREATED_AT));
        store.create(newStaff(firstAlphaId, businessId, "Alpha", null, null, CREATED_AT));
        store.create(newStaff(betaId, businessId, "beta", null, null, CREATED_AT));
        store.create(newStaff(otherBusiness, "Aardvark", null, null, CREATED_AT));

        List<StaffMemberRow> firstPage = store.list(businessId, 0, 2);
        List<StaffMemberRow> secondPage = store.list(businessId, 1, 2);

        assertThat(firstPage).extracting(StaffMemberRow::id)
                .containsExactly(firstAlphaId, secondAlphaId);
        assertThat(secondPage).extracting(StaffMemberRow::id)
                .containsExactly(betaId, gammaId);
        assertThat(store.count(businessId)).isEqualTo(4);
        assertThat(store.count(otherBusiness)).isEqualTo(1);
    }

    @Test
    void tenantScopedIdentityMakesMissingAndCrossBusinessOperationsIndistinguishable() {
        UUID ownerBusiness = createBusiness();
        UUID otherBusiness = createBusiness();
        StaffMemberRow original = store.create(newStaff(
                ownerBusiness, "Защитен член", null, null, CREATED_AT));
        UUID missingId = UUID.randomUUID();
        var update = update(original, "Чужда промяна", original.version(), UPDATED_AT);

        assertThat(store.findByBusinessIdAndId(otherBusiness, original.id())).isEmpty();
        assertThat(store.findByBusinessIdAndId(ownerBusiness, missingId)).isEmpty();
        assertThat(store.updateProfile(otherBusiness, original.id(), update)).isEmpty();
        assertThat(store.updateProfile(ownerBusiness, missingId, update)).isEmpty();
        assertThat(store.deactivate(
                        otherBusiness, original.id(), original.version(), UPDATED_AT))
                .isEmpty();
        assertThat(store.deactivate(
                        ownerBusiness, missingId, original.version(), UPDATED_AT))
                .isEmpty();
        assertThat(store.reactivate(
                        otherBusiness, original.id(), original.version(), UPDATED_AT))
                .isEmpty();
        assertThat(store.findByBusinessIdAndId(ownerBusiness, original.id()))
                .contains(original);
    }

    @Test
    void profileUpdatesActiveAndInactiveStaffWhilePreservingImmutableFields() {
        UUID businessId = createBusiness();
        StaffMemberRow original = store.create(newStaff(
                businessId,
                "Първоначален",
                "first@example.invalid",
                "+359111222",
                CREATED_AT));

        StaffMemberRow updated = store.updateProfile(
                        businessId,
                        original.id(),
                        new StaffMemberProfileUpdateRow(
                                "Обновен",
                                "updated@example.invalid",
                                "+359 (2) 123-45-67",
                                0,
                                UPDATED_AT))
                .orElseThrow();

        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.businessId()).isEqualTo(original.businessId());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
        assertThat(updated.displayName()).isEqualTo("Обновен");
        assertThat(updated.contactEmail()).isEqualTo("updated@example.invalid");
        assertThat(updated.contactPhone()).isEqualTo("+359 (2) 123-45-67");
        assertThat(updated.active()).isTrue();
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.updatedAt()).isEqualTo(UPDATED_AT);

        StaffMemberRow inactive = store.deactivate(
                        businessId, original.id(), 1, UPDATED_AT.plusSeconds(1))
                .orElseThrow();
        StaffMemberRow updatedInactive = store.updateProfile(
                        businessId,
                        original.id(),
                        new StaffMemberProfileUpdateRow(
                                "Редактиран неактивен",
                                null,
                                null,
                                2,
                                UPDATED_AT.plusSeconds(2)))
                .orElseThrow();

        assertThat(inactive.active()).isFalse();
        assertThat(updatedInactive.active()).isFalse();
        assertThat(updatedInactive.version()).isEqualTo(3);
        assertThat(updatedInactive.displayName()).isEqualTo("Редактиран неактивен");
        assertThat(updatedInactive.businessId()).isEqualTo(businessId);
        assertThat(updatedInactive.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void successfulSameValueProfileUpdateStillIncrementsVersionAndTimestampOnce() {
        UUID businessId = createBusiness();
        StaffMemberRow original = store.create(newStaff(
                businessId,
                "Същият",
                "same@example.invalid",
                "+359123456",
                CREATED_AT));

        StaffMemberRow updated = store.updateProfile(
                        businessId,
                        original.id(),
                        new StaffMemberProfileUpdateRow(
                                original.displayName(),
                                original.contactEmail(),
                                original.contactPhone(),
                                original.version(),
                                UPDATED_AT))
                .orElseThrow();

        assertThat(updated.displayName()).isEqualTo(original.displayName());
        assertThat(updated.contactEmail()).isEqualTo(original.contactEmail());
        assertThat(updated.contactPhone()).isEqualTo(original.contactPhone());
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void lifecycleMutationsChangeStateVersionAndTimestampExactlyOnce() {
        UUID businessId = createBusiness();
        StaffMemberRow original = store.create(newStaff(
                businessId, "Жизнен цикъл", null, null, CREATED_AT));

        StaffMemberRow inactive = store.deactivate(
                        businessId, original.id(), 0, UPDATED_AT)
                .orElseThrow();
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(1);
        assertThat(inactive.createdAt()).isEqualTo(CREATED_AT);
        assertThat(inactive.updatedAt()).isEqualTo(UPDATED_AT);

        Instant reactivatedAt = UPDATED_AT.plusSeconds(1);
        StaffMemberRow active = store.reactivate(
                        businessId, original.id(), 1, reactivatedAt)
                .orElseThrow();
        assertThat(active.active()).isTrue();
        assertThat(active.version()).isEqualTo(2);
        assertThat(active.createdAt()).isEqualTo(CREATED_AT);
        assertThat(active.updatedAt()).isEqualTo(reactivatedAt);
    }

    @Test
    void staleAndRepeatedLifecyclePredicatesReturnEmptyWithoutChangingState() {
        UUID businessId = createBusiness();
        StaffMemberRow original = store.create(newStaff(
                businessId, "Без промяна", null, null, CREATED_AT));

        assertThat(store.updateProfile(
                        businessId,
                        original.id(),
                        update(original, "Неуспешно", 1, UPDATED_AT)))
                .isEmpty();
        assertThat(store.reactivate(businessId, original.id(), 0, UPDATED_AT)).isEmpty();
        assertThat(store.findByBusinessIdAndId(businessId, original.id()))
                .contains(original);

        StaffMemberRow inactive = store.deactivate(
                        businessId, original.id(), 0, UPDATED_AT)
                .orElseThrow();
        assertThat(store.deactivate(
                        businessId, original.id(), 1, UPDATED_AT.plusSeconds(1)))
                .isEmpty();
        assertThat(store.reactivate(
                        businessId, original.id(), 0, UPDATED_AT.plusSeconds(1)))
                .isEmpty();
        assertThat(store.findByBusinessIdAndId(businessId, original.id()))
                .contains(inactive);
    }

    @Test
    void unexpectedPersistenceFailuresAreSanitizedAndPreserveTheirCauses() {
        UUID businessId = createBusiness();
        StaffMemberRow first = store.create(newStaff(
                businessId, "Първи", null, null, CREATED_AT));

        UnexpectedFailure primaryKeyFailure = org.junit.jupiter.api.Assertions.assertThrows(
                UnexpectedFailure.class,
                () -> store.create(newStaff(
                        first.id(),
                        businessId,
                        "Втори",
                        null,
                        null,
                        CREATED_AT)));
        assertSafeUnexpectedFailure(primaryKeyFailure, "staff_member_pkey");

        UnexpectedFailure foreignKeyFailure = org.junit.jupiter.api.Assertions.assertThrows(
                UnexpectedFailure.class,
                () -> store.create(newStaff(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "Липсващ бизнес",
                        null,
                        null,
                        CREATED_AT)));
        assertSafeUnexpectedFailure(foreignKeyFailure, "staff_member_business_fk");
    }

    @Test
    void concurrentProfileAndLifecycleMutationsUsingOneVersionHaveOneWinner() {
        UUID businessId = createBusiness();
        StaffMemberRow original = store.create(newStaff(
                businessId, "Една версия", null, null, CREATED_AT));
        Instant profileTime = UPDATED_AT;
        Instant lifecycleTime = UPDATED_AT.plusSeconds(1);

        List<ConcurrentOutcome> outcomes = runConcurrently(
                () -> store.updateProfile(
                                businessId,
                                original.id(),
                                update(original, "Профилът спечели", 0, profileTime))
                        .isPresent(),
                () -> store.deactivate(
                                businessId, original.id(), 0, lifecycleTime)
                        .isPresent());

        assertSeparateConnections(outcomes);
        assertThat(outcomes).extracting(ConcurrentOutcome::successful)
                .containsExactlyInAnyOrder(true, false);
        assertThat(outcomes).extracting(ConcurrentOutcome::failure).containsOnlyNulls();

        StaffMemberRow stored = store.findByBusinessIdAndId(businessId, original.id())
                .orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        if (stored.active()) {
            assertThat(stored.displayName()).isEqualTo("Профилът спечели");
            assertThat(stored.updatedAt()).isEqualTo(profileTime);
        } else {
            assertThat(stored.displayName()).isEqualTo(original.displayName());
            assertThat(stored.updatedAt()).isEqualTo(lifecycleTime);
        }
    }

    @Test
    void assignmentGuardAndReconciliationUseTenantVersionWithoutActivityPredicate() {
        UUID businessId = createBusiness();
        StaffMemberRow active = store.create(newStaff(
                businessId, "Assignments", null, null, CREATED_AT));
        StaffMemberRow inactive = store.deactivate(
                        businessId, active.id(), 0, UPDATED_AT)
                .orElseThrow();
        UUID first = createService(businessId, "First", true);
        UUID second = createService(businessId, "Second", false);

        store.reconcileServiceAssignments(
                businessId, inactive.id(), List.of(), List.of(first, second));
        assertThat(store.listAssignedServiceIds(businessId, inactive.id()))
                .containsExactlyInAnyOrder(first, second);

        Instant replacementTime = UPDATED_AT.plusSeconds(1);
        StaffMemberRow guarded = store.advanceAssignmentVersion(
                        businessId, inactive.id(), 1, replacementTime)
                .orElseThrow();
        store.reconcileServiceAssignments(
                businessId, inactive.id(), List.of(first), List.of());

        assertThat(guarded.active()).isFalse();
        assertThat(guarded.version()).isEqualTo(2);
        assertThat(guarded.updatedAt()).isEqualTo(replacementTime);
        assertThat(guarded.id()).isEqualTo(active.id());
        assertThat(guarded.businessId()).isEqualTo(businessId);
        assertThat(guarded.createdAt()).isEqualTo(active.createdAt());
        assertThat(store.listAssignedServiceIds(businessId, inactive.id()))
                .containsExactly(second);
        assertThat(store.advanceAssignmentVersion(
                        businessId, inactive.id(), 1, replacementTime.plusSeconds(1)))
                .isEmpty();
    }

    @Test
    void assignmentPersistenceRejectsCrossBusinessEndpointsAndSanitizesFailure() {
        UUID firstBusiness = createBusiness();
        UUID secondBusiness = createBusiness();
        StaffMemberRow staffMember = store.create(newStaff(
                firstBusiness, "Tenant protected", null, null, CREATED_AT));
        UUID foreignService = createService(secondBusiness, "Foreign", true);

        UnexpectedFailure failure = org.junit.jupiter.api.Assertions.assertThrows(
                UnexpectedFailure.class,
                () -> store.reconcileServiceAssignments(
                        firstBusiness,
                        staffMember.id(),
                        List.of(),
                        List.of(foreignService)));

        assertSafeUnexpectedFailure(failure, "staff_member_service_service_fk");
        assertThat(store.listAssignedServiceIds(firstBusiness, staffMember.id())).isEmpty();
        assertThat(store.listAssignedServiceIds(secondBusiness, staffMember.id())).isEmpty();
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Staff Store Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "staff-store-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private UUID createService(UUID businessId, String name, boolean active) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
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

    private NewStaffMemberRow newStaff(
            UUID businessId,
            String displayName,
            String contactEmail,
            String contactPhone,
            Instant createdAt) {
        return newStaff(
                UUID.randomUUID(),
                businessId,
                displayName,
                contactEmail,
                contactPhone,
                createdAt);
    }

    private NewStaffMemberRow newStaff(
            UUID id,
            UUID businessId,
            String displayName,
            String contactEmail,
            String contactPhone,
            Instant createdAt) {
        CreateStaffMemberCommand validated = validator.validateCreate(
                new CreateStaffMemberCommand(displayName, contactEmail, contactPhone));
        return new NewStaffMemberRow(
                id,
                businessId,
                validated.displayName(),
                validated.contactEmail(),
                validated.contactPhone(),
                createdAt);
    }

    private StaffMemberProfileUpdateRow update(
            StaffMemberRow original,
            String displayName,
            long expectedVersion,
            Instant updatedAt) {
        CreateStaffMemberCommand validated = validator.validateCreate(
                new CreateStaffMemberCommand(
                        displayName,
                        original.contactEmail(),
                        original.contactPhone()));
        return new StaffMemberProfileUpdateRow(
                validated.displayName(),
                validated.contactEmail(),
                validated.contactPhone(),
                expectedVersion,
                updatedAt);
    }

    private static void assertSafeUnexpectedFailure(
            UnexpectedFailure failure, String internalConstraint) {
        assertThat(failure)
                .hasMessage("StaffMember persistence operation failed")
                .hasCauseInstanceOf(DataAccessException.class);
        assertThat(failure.getMessage())
                .doesNotContain(internalConstraint)
                .doesNotContain("duplicate key")
                .doesNotContain("org.postgresql")
                .doesNotContain("SQL");
        assertThat(failure.getCause()).isNotNull();
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
