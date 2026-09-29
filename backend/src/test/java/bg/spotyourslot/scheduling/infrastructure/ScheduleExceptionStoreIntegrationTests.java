package bg.spotyourslot.scheduling.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException.InvalidReference;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException.OverlapConflict;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleExceptionStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T08:00:00.123456Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-29T09:30:00.654321Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired
    ScheduleExceptionStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    // ---- insert, find, kinds -------------------------------------------------

    @Test
    void insertsAndReadsAllFourKindsAtVersionZeroWithTheSuppliedInstant() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        List<ScheduleExceptionContent> contents = List.of(
                ScheduleExceptionContent.businessClosureDays(DAY, DAY.plusDays(2)),
                ScheduleExceptionContent.staffTimeOffPartial(
                        staffId, DAY, List.of(period("09:00", "10:00"), period("12:00", "13:00"))),
                ScheduleExceptionContent.workingDayOverride(staffId, DAY, List.of()),
                ScheduleExceptionContent.additionalWorkingPeriods(
                        staffId, DAY, List.of(period("18:00", "20:00"))));

        for (ScheduleExceptionContent content : contents) {
            NewScheduleException created = newException(businessId, content);

            ScheduleException stored = store.insert(created);

            assertThat(stored).isEqualTo(new ScheduleException(
                    created.id(), businessId, content, 0, CREATED_AT, CREATED_AT));
            assertThat(store.findByBusinessIdAndId(businessId, created.id())).contains(stored);
        }
    }

    @Test
    void emptyOverrideIsStoredAsAnAggregateWithoutPeriods() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        NewScheduleException created = newException(
                businessId, ScheduleExceptionContent.workingDayOverride(staffId, DAY, List.of()));

        store.insert(created);

        assertThat(store.findByBusinessIdAndId(businessId, created.id()))
                .hasValueSatisfying(found -> assertThat(found.content().periods()).isEmpty());
        assertThat(periodRows(created.id())).isZero();
    }

    @Test
    void multiPeriodOverrideKeepsAdjacentPeriodsSeparateAndSorted() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        NewScheduleException created = newException(
                businessId,
                ScheduleExceptionContent.workingDayOverride(
                        staffId,
                        DAY,
                        List.of(
                                period("14:00", "18:00"),
                                period("09:00", "12:00"),
                                period("12:00", "14:00"))));

        store.insert(created);

        assertThat(store.findByBusinessIdAndId(businessId, created.id()).orElseThrow()
                        .content().periods())
                .containsExactly(
                        period("09:00", "12:00"),
                        period("12:00", "14:00"),
                        period("14:00", "18:00"));
        assertThat(periodRows(created.id())).isEqualTo(3);
    }

    @Test
    void crossBusinessAndMissingLookupsAreIndistinguishable() {
        UUID owner = createBusiness();
        UUID other = createBusiness();
        NewScheduleException created = newException(
                owner, ScheduleExceptionContent.businessClosureDays(DAY, DAY));
        store.insert(created);

        assertThat(store.findByBusinessIdAndId(other, created.id())).isEmpty();
        assertThat(store.findByBusinessIdAndId(owner, UUID.randomUUID())).isEmpty();
        assertThat(store.replace(
                        other,
                        created.id(),
                        0,
                        ScheduleExceptionContent.businessClosureDays(DAY, DAY.plusDays(1)),
                        UPDATED_AT))
                .isEmpty();
        assertThat(store.replace(
                        owner,
                        UUID.randomUUID(),
                        0,
                        ScheduleExceptionContent.businessClosureDays(DAY, DAY),
                        UPDATED_AT))
                .isEmpty();
        assertThat(store.delete(other, created.id(), 0)).isFalse();
        assertThat(store.delete(owner, UUID.randomUUID(), 0)).isFalse();
        assertThat(store.findByBusinessIdAndId(owner, created.id())).isPresent();
        assertThat(store.findOverlapping(other, DAY, DAY)).isEmpty();
    }

    // ---- persistence failures ------------------------------------------------

    @Test
    void crossBusinessStaffMemberIsRejectedAsAnInvalidReferenceWithASafeMessage() {
        UUID owner = createBusiness();
        UUID foreignStaff = createStaff(createBusiness());

        assertThatThrownBy(() -> store.insert(newException(
                        owner, ScheduleExceptionContent.staffTimeOffDays(foreignStaff, DAY, DAY))))
                .isInstanceOf(InvalidReference.class)
                .satisfies(failure -> assertSafe(failure, foreignStaff, "staff_member_fk"));
        assertThat(exceptionRows()).isZero();
    }

    @Test
    void sameKindOverlapIsRejectedAsAnOverlapConflictWithASafeMessage() {
        UUID businessId = createBusiness();
        store.insert(newException(
                businessId, ScheduleExceptionContent.businessClosureDays(DAY, DAY.plusDays(2))));

        assertThatThrownBy(() -> store.insert(newException(
                        businessId,
                        ScheduleExceptionContent.businessClosurePartial(
                                DAY.plusDays(1), List.of(period("09:00", "10:00"))))))
                .isInstanceOf(OverlapConflict.class)
                .satisfies(failure -> assertSafe(failure, businessId, "closure_no_overlap"));
        assertThat(exceptionRows()).isEqualTo(1);
    }

    @Test
    void otherPersistenceFailuresAreReportedAsUnexpectedFailuresWithASafeMessage() {
        UUID businessId = createBusiness();
        NewScheduleException created = newException(
                businessId, ScheduleExceptionContent.businessClosureDays(DAY, DAY));
        store.insert(created);

        assertThatThrownBy(() -> store.insert(created))
                .isInstanceOf(ScheduleExceptionPersistenceException.UnexpectedFailure.class)
                .satisfies(failure -> assertSafe(failure, created.id(), "pkey"));
    }

    // ---- cross-aggregate matrix through the store ----------------------------

    @Test
    void crossKindAndCrossScopeOverlapsAreStoredAndSameKindDistinctScopesCoexist() {
        UUID businessId = createBusiness();
        UUID otherBusiness = createBusiness();
        UUID staffId = createStaff(businessId);
        UUID otherStaff = createStaff(businessId);

        store.insert(newException(
                businessId, ScheduleExceptionContent.businessClosureDays(DAY, DAY)));
        store.insert(newException(
                businessId, ScheduleExceptionContent.staffTimeOffDays(staffId, DAY, DAY)));
        store.insert(newException(
                businessId, ScheduleExceptionContent.workingDayOverride(staffId, DAY, List.of())));
        store.insert(newException(
                businessId,
                ScheduleExceptionContent.additionalWorkingPeriods(
                        staffId, DAY, List.of(period("09:00", "10:00")))));
        store.insert(newException(
                businessId, ScheduleExceptionContent.staffTimeOffDays(otherStaff, DAY, DAY)));
        store.insert(newException(
                otherBusiness, ScheduleExceptionContent.businessClosureDays(DAY, DAY)));

        assertThat(exceptionRows()).isEqualTo(6);
    }

    // ---- range-overlap list semantics ----------------------------------------

    @Test
    void overlapListUsesInclusiveDateEdges() {
        UUID businessId = createBusiness();
        NewScheduleException closure = newException(
                businessId,
                ScheduleExceptionContent.businessClosureDays(DAY, DAY.plusDays(2)));
        store.insert(closure);

        assertThat(ids(store.findOverlapping(businessId, DAY.minusDays(3), DAY.minusDays(1))))
                .isEmpty();
        assertThat(ids(store.findOverlapping(businessId, DAY.minusDays(3), DAY)))
                .containsExactly(closure.id());
        assertThat(ids(store.findOverlapping(businessId, DAY.plusDays(1), DAY.plusDays(1))))
                .containsExactly(closure.id());
        assertThat(ids(store.findOverlapping(businessId, DAY.plusDays(2), DAY.plusDays(9))))
                .containsExactly(closure.id());
        assertThat(ids(store.findOverlapping(businessId, DAY.plusDays(3), DAY.plusDays(9))))
                .isEmpty();
        assertThat(ids(store.findOverlapping(businessId, DAY.minusDays(9), DAY.plusDays(9))))
                .containsExactly(closure.id());
        assertThat(ids(store.findOverlapping(businessId, DAY, DAY)))
                .containsExactly(closure.id());
    }

    @Test
    void overlapListRejectsReversedWindows() {
        UUID businessId = createBusiness();

        assertThatThrownBy(() -> store.findOverlapping(businessId, DAY.plusDays(1), DAY))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.findOverlappingForStaff(
                        businessId, DAY.plusDays(1), DAY, List.of()))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staffFilteredListIncludesBusinessClosuresAndOnlyTheRequestedStaffMembers() {
        UUID businessId = createBusiness();
        UUID staffA = createStaff(businessId);
        UUID staffB = createStaff(businessId);
        UUID staffC = createStaff(businessId);
        NewScheduleException closure = insert(
                businessId, ScheduleExceptionContent.businessClosureDays(DAY, DAY));
        NewScheduleException timeOffA = insert(
                businessId, ScheduleExceptionContent.staffTimeOffDays(staffA, DAY, DAY));
        insert(businessId, ScheduleExceptionContent.staffTimeOffDays(staffB, DAY, DAY));
        NewScheduleException overrideC = insert(
                businessId, ScheduleExceptionContent.workingDayOverride(staffC, DAY, List.of()));
        insert(businessId, ScheduleExceptionContent.staffTimeOffDays(
                staffA, DAY.plusDays(30), DAY.plusDays(31)));

        assertThat(ids(store.findOverlappingForStaff(
                        businessId, DAY, DAY, List.of(staffA, staffC))))
                .containsExactlyInAnyOrder(closure.id(), timeOffA.id(), overrideC.id());
        assertThat(ids(store.findOverlappingForStaff(businessId, DAY, DAY, List.of())))
                .containsExactly(closure.id());
        assertThat(ids(store.findOverlapping(businessId, DAY, DAY))).hasSize(4);
    }

    @Test
    void listedAggregatesCarryTheirPeriodsFromOneJoinedRead() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        insert(businessId, ScheduleExceptionContent.staffTimeOffPartial(
                staffId, DAY, List.of(period("13:00", "14:00"), period("09:00", "10:00"))));
        insert(businessId, ScheduleExceptionContent.workingDayOverride(
                staffId, DAY.plusDays(1), List.of()));
        insert(businessId, ScheduleExceptionContent.additionalWorkingPeriods(
                staffId, DAY.plusDays(2), List.of(period("17:00", "18:00"))));

        List<ScheduleException> listed = store.findOverlapping(businessId, DAY, DAY.plusDays(2));

        assertThat(listed).extracting(exception -> exception.content().periods().size())
                .containsExactly(2, 0, 1);
        assertThat(listed.get(0).content().periods())
                .containsExactly(period("09:00", "10:00"), period("13:00", "14:00"));
    }

    @Test
    void listOrderIsDeterministicByDatesKindStaffAndIdRegardlessOfInsertionOrder() {
        UUID businessId = createBusiness();
        UUID staffA = new UUID(0, 1);
        UUID staffB = new UUID(0, 2);
        createStaff(businessId, staffA);
        createStaff(businessId, staffB);
        NewScheduleException late = insert(
                businessId, ScheduleExceptionContent.businessClosureDays(
                        DAY.plusDays(5), DAY.plusDays(5)));
        NewScheduleException timeOffB = insert(
                businessId, ScheduleExceptionContent.staffTimeOffDays(staffB, DAY, DAY));
        NewScheduleException wide = insert(
                businessId, ScheduleExceptionContent.businessClosureDays(
                        DAY.plusDays(2), DAY.plusDays(3)));
        NewScheduleException timeOffA = insert(
                businessId, ScheduleExceptionContent.staffTimeOffDays(staffA, DAY, DAY));
        NewScheduleException overrideA = insert(
                businessId, ScheduleExceptionContent.workingDayOverride(staffA, DAY, List.of()));
        NewScheduleException additionalA = insert(
                businessId, ScheduleExceptionContent.additionalWorkingPeriods(
                        staffA, DAY, List.of(period("09:00", "10:00"))));
        NewScheduleException narrow = insert(
                businessId, ScheduleExceptionContent.businessClosureDays(
                        DAY.plusDays(1), DAY.plusDays(1)));

        List<UUID> expected = List.of(
                additionalA.id(),
                timeOffA.id(),
                timeOffB.id(),
                overrideA.id(),
                narrow.id(),
                wide.id(),
                late.id());
        assertThat(ids(store.findOverlapping(businessId, DAY, DAY.plusDays(10))))
                .isEqualTo(expected);
        assertThat(ids(store.findOverlapping(businessId, DAY, DAY.plusDays(10))))
                .isEqualTo(expected);
        assertThat(store.findOverlapping(businessId, DAY, DAY.plusDays(10))
                        .stream().map(exception -> exception.content().kind()).toList())
                .containsSubsequence(
                        ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                        ScheduleExceptionKind.STAFF_TIME_OFF,
                        ScheduleExceptionKind.WORKING_DAY_OVERRIDE);
    }

    // ---- replace -------------------------------------------------------------

    @Test
    void replaceAdvancesVersionOnceKeepsCreationTimeAndSwapsDatesShapeAndPeriods() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        NewScheduleException created = insert(
                businessId,
                ScheduleExceptionContent.staffTimeOffPartial(
                        staffId, DAY, List.of(period("09:00", "10:00"))));
        ScheduleExceptionContent fullDays =
                ScheduleExceptionContent.staffTimeOffDays(staffId, DAY, DAY.plusDays(3));

        ScheduleException replaced = store.replace(
                        businessId, created.id(), 0, fullDays, UPDATED_AT)
                .orElseThrow();

        assertThat(replaced).isEqualTo(new ScheduleException(
                created.id(), businessId, fullDays, 1, CREATED_AT, UPDATED_AT));
        assertThat(store.findByBusinessIdAndId(businessId, created.id())).contains(replaced);
        assertThat(periodRows(created.id())).isZero();

        ScheduleExceptionContent partial = ScheduleExceptionContent.staffTimeOffPartial(
                staffId,
                DAY.plusDays(7),
                List.of(period("11:00", "12:00"), period("12:00", "13:00")));
        ScheduleException again = store.replace(
                        businessId, created.id(), 1, partial, UPDATED_AT.plusSeconds(60))
                .orElseThrow();

        assertThat(again.version()).isEqualTo(2);
        assertThat(again.createdAt()).isEqualTo(CREATED_AT);
        assertThat(again.updatedAt()).isEqualTo(UPDATED_AT.plusSeconds(60));
        assertThat(again.content().periods()).hasSize(2);
        assertThat(periodRows(created.id())).isEqualTo(2);
    }

    @Test
    void identicalAcceptedReplacementStillAdvancesVersionAndUpdateTime() {
        UUID businessId = createBusiness();
        ScheduleExceptionContent content = ScheduleExceptionContent.businessClosurePartial(
                DAY, List.of(period("09:00", "10:00")));
        NewScheduleException created = insert(businessId, content);

        ScheduleException replaced = store.replace(
                        businessId, created.id(), 0, content, UPDATED_AT)
                .orElseThrow();

        assertThat(replaced.version()).isEqualTo(1);
        assertThat(replaced.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(replaced.content()).isEqualTo(content);
    }

    @Test
    void staleExpectedVersionWrongKindAndWrongStaffMemberMatchNoRow() {
        UUID businessId = createBusiness();
        UUID staffId = createStaff(businessId);
        UUID otherStaff = createStaff(businessId);
        ScheduleExceptionContent original =
                ScheduleExceptionContent.staffTimeOffDays(staffId, DAY, DAY);
        NewScheduleException created = insert(businessId, original);

        assertThat(store.replace(
                        businessId,
                        created.id(),
                        7,
                        ScheduleExceptionContent.staffTimeOffDays(staffId, DAY, DAY.plusDays(1)),
                        UPDATED_AT))
                .isEmpty();
        assertThat(store.replace(
                        businessId,
                        created.id(),
                        0,
                        ScheduleExceptionContent.workingDayOverride(staffId, DAY, List.of()),
                        UPDATED_AT))
                .isEmpty();
        assertThat(store.replace(
                        businessId,
                        created.id(),
                        0,
                        ScheduleExceptionContent.staffTimeOffDays(otherStaff, DAY, DAY),
                        UPDATED_AT))
                .isEmpty();

        ScheduleException unchanged = store.findByBusinessIdAndId(businessId, created.id())
                .orElseThrow();
        assertThat(unchanged.version()).isZero();
        assertThat(unchanged.content()).isEqualTo(original);
        assertThat(unchanged.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void secondReplaceWithTheConsumedVersionMatchesNoRow() {
        UUID businessId = createBusiness();
        NewScheduleException created = insert(
                businessId, ScheduleExceptionContent.businessClosureDays(DAY, DAY));

        assertThat(store.replace(
                        businessId, created.id(), 0,
                        ScheduleExceptionContent.businessClosureDays(DAY, DAY.plusDays(1)),
                        UPDATED_AT))
                .isPresent();
        assertThat(store.replace(
                        businessId, created.id(), 0,
                        ScheduleExceptionContent.businessClosureDays(DAY, DAY.plusDays(2)),
                        UPDATED_AT))
                .isEmpty();
    }

    @Test
    void failedReplacementFullyRollsBackVersionDatesAndPeriods() {
        UUID businessId = createBusiness();
        NewScheduleException blocker = insert(
                businessId, ScheduleExceptionContent.businessClosureDays(
                        DAY.plusDays(10), DAY.plusDays(12)));
        ScheduleExceptionContent original = ScheduleExceptionContent.businessClosurePartial(
                DAY, List.of(period("09:00", "10:00"), period("11:00", "12:00")));
        NewScheduleException created = insert(businessId, original);

        assertThatThrownBy(() -> store.replace(
                        businessId,
                        created.id(),
                        0,
                        ScheduleExceptionContent.businessClosureDays(
                                DAY.plusDays(11), DAY.plusDays(11)),
                        UPDATED_AT))
                .isInstanceOf(OverlapConflict.class);

        ScheduleException unchanged = store.findByBusinessIdAndId(businessId, created.id())
                .orElseThrow();
        assertThat(unchanged).isEqualTo(new ScheduleException(
                created.id(), businessId, original, 0, CREATED_AT, CREATED_AT));
        assertThat(store.findByBusinessIdAndId(businessId, blocker.id())).isPresent();
    }

    @Test
    void abortedOuterTransactionPreservesThePreviousAggregate() {
        UUID businessId = createBusiness();
        ScheduleExceptionContent original = ScheduleExceptionContent.businessClosurePartial(
                DAY, List.of(period("09:00", "10:00")));
        NewScheduleException created = insert(businessId, original);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            store.replace(
                                    businessId,
                                    created.id(),
                                    0,
                                    ScheduleExceptionContent.businessClosureDays(
                                            DAY, DAY.plusDays(4)),
                                    UPDATED_AT);
                            throw new IllegalStateException("abort");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.findByBusinessIdAndId(businessId, created.id()))
                .contains(new ScheduleException(
                        created.id(), businessId, original, 0, CREATED_AT, CREATED_AT));
        assertThat(periodRows(created.id())).isEqualTo(1);
    }

    @Test
    void abortedOuterTransactionDoesNotLeaveAnInsertedAggregate() {
        UUID businessId = createBusiness();
        NewScheduleException created = newException(
                businessId,
                ScheduleExceptionContent.businessClosurePartial(
                        DAY, List.of(period("09:00", "10:00"))));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            store.insert(created);
                            throw new IllegalStateException("abort");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.findByBusinessIdAndId(businessId, created.id())).isEmpty();
        assertThat(periodRows(created.id())).isZero();
    }

    // ---- delete --------------------------------------------------------------

    @Test
    void hardDeleteRemovesTheAggregateAndItsPeriodsOnlyWithTheExpectedVersion() {
        UUID businessId = createBusiness();
        NewScheduleException created = insert(
                businessId,
                ScheduleExceptionContent.businessClosurePartial(
                        DAY, List.of(period("09:00", "10:00"), period("11:00", "12:00"))));
        NewScheduleException neighbour = insert(
                businessId,
                ScheduleExceptionContent.businessClosurePartial(
                        DAY.plusDays(1), List.of(period("09:00", "10:00"))));

        assertThat(store.delete(businessId, created.id(), 1)).isFalse();
        assertThat(store.findByBusinessIdAndId(businessId, created.id())).isPresent();

        assertThat(store.delete(businessId, created.id(), 0)).isTrue();

        assertThat(store.findByBusinessIdAndId(businessId, created.id())).isEmpty();
        assertThat(periodRows(created.id())).isZero();
        assertThat(store.findByBusinessIdAndId(businessId, neighbour.id())).isPresent();
        assertThat(periodRows(neighbour.id())).isEqualTo(1);
        assertThat(store.delete(businessId, created.id(), 0)).isFalse();
    }

    @Test
    void staleDeleteAfterReplacementIsRejectedAndDeletionAfterReplacementUsesTheNewVersion() {
        UUID businessId = createBusiness();
        NewScheduleException created = insert(
                businessId, ScheduleExceptionContent.businessClosureDays(DAY, DAY));
        Optional<ScheduleException> replaced = store.replace(
                businessId, created.id(), 0,
                ScheduleExceptionContent.businessClosureDays(DAY, DAY.plusDays(1)), UPDATED_AT);
        assertThat(replaced).isPresent();

        assertThat(store.delete(businessId, created.id(), 0)).isFalse();
        assertThat(store.delete(businessId, created.id(), 1)).isTrue();
    }

    // ---- helpers -------------------------------------------------------------

    private NewScheduleException insert(UUID businessId, ScheduleExceptionContent content) {
        NewScheduleException created = newException(businessId, content);
        store.insert(created);
        return created;
    }

    private static NewScheduleException newException(
            UUID businessId, ScheduleExceptionContent content) {
        return new NewScheduleException(UUID.randomUUID(), businessId, content, CREATED_AT);
    }

    private static List<UUID> ids(List<ScheduleException> exceptions) {
        List<UUID> ids = new ArrayList<>();
        exceptions.forEach(exception -> ids.add(exception.id()));
        return ids;
    }

    private static LocalPeriod period(String start, String end) {
        return new LocalPeriod(LocalTime.parse(start), LocalTime.parse(end));
    }

    private static void assertSafe(Throwable failure, UUID submitted, String internalDetail) {
        assertThat(failure.getMessage())
                .doesNotContain(submitted.toString())
                .doesNotContain(internalDetail)
                .doesNotContain("schedule_exception")
                .doesNotContain("INSERT")
                .doesNotContain("SELECT");
        assertThat(failure).hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private long exceptionRows() {
        return jdbc.sql("SELECT count(*) FROM schedule_exception").query(Long.class).single();
    }

    private long periodRows(UUID exceptionId) {
        return jdbc.sql("SELECT count(*) FROM schedule_exception_period WHERE exception_id = :id")
                .param("id", exceptionId)
                .query(Long.class)
                .single();
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Schedule Exception Store Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "exception-store-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private UUID createStaff(UUID businessId) {
        return createStaff(businessId, UUID.randomUUID());
    }

    private UUID createStaff(UUID businessId, UUID id) {
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, active, version, created_at, updated_at)
                        VALUES (:id, :businessId, 'Exception Store Staff', true, 0, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("now", now)
                .update();
        return id;
    }
}
