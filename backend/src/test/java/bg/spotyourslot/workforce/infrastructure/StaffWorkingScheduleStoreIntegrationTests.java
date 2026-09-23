package bg.spotyourslot.workforce.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingSchedulePersistenceException.UnexpectedFailure;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffWorkingScheduleStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-23T08:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-23T09:00:00Z");

    @Autowired
    StaffWorkingScheduleStore store;

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsEmptyVersionZeroScheduleUsingSuppliedCreationInstant() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);

        StaffWorkingScheduleRow created = store.create(
                new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));

        assertThat(created).isEqualTo(new StaffWorkingScheduleRow(
                businessId, staffMemberId, 0, CREATED_AT, CREATED_AT));
        assertThat(store.findByBusinessIdAndStaffMemberId(businessId, staffMemberId))
                .contains(created);
        assertThat(store.findPeriods(businessId, staffMemberId)).isEmpty();
    }

    @Test
    void tenantScopedIdentityMakesMissingAndCrossBusinessLookupsIndistinguishable() {
        UUID ownerBusiness = createBusiness();
        UUID otherBusiness = createBusiness();
        UUID staffMemberId = createStaffMember(ownerBusiness);
        store.create(new NewStaffWorkingScheduleRow(ownerBusiness, staffMemberId, CREATED_AT));

        assertThat(store.findByBusinessIdAndStaffMemberId(otherBusiness, staffMemberId))
                .isEmpty();
        assertThat(store.findByBusinessIdAndStaffMemberId(ownerBusiness, UUID.randomUUID()))
                .isEmpty();
        assertThat(store.advanceScheduleVersion(
                        otherBusiness, staffMemberId, 0, UPDATED_AT))
                .isEmpty();
    }

    @Test
    void replacementAdvancesVersionAndAtomicallySwapsPeriods() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        List<WorkingPeriod> firstWeek = List.of(
                period(DayOfWeek.MONDAY, "09:00", "13:00"),
                period(DayOfWeek.MONDAY, "14:00", "18:00"),
                period(DayOfWeek.TUESDAY, "09:00", "18:00"));

        StaffWorkingScheduleRow guarded = store.advanceScheduleVersion(
                        businessId, staffMemberId, 0, UPDATED_AT)
                .orElseThrow();
        store.replacePeriods(businessId, staffMemberId, firstWeek);

        assertThat(guarded.version()).isEqualTo(1);
        assertThat(guarded.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(guarded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(store.findPeriods(businessId, staffMemberId))
                .containsExactlyElementsOf(firstWeek);

        Instant secondUpdate = UPDATED_AT.plusSeconds(1);
        List<WorkingPeriod> secondWeek = List.of(
                period(DayOfWeek.FRIDAY, "10:00", "12:00"));
        store.advanceScheduleVersion(businessId, staffMemberId, 1, secondUpdate)
                .orElseThrow();
        store.replacePeriods(businessId, staffMemberId, secondWeek);

        assertThat(store.findPeriods(businessId, staffMemberId))
                .containsExactlyElementsOf(secondWeek);
    }

    @Test
    void clearingTheCompleteWeekReplacesPeriodsWithAnEmptySet() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        store.advanceScheduleVersion(businessId, staffMemberId, 0, UPDATED_AT);
        store.replacePeriods(
                businessId, staffMemberId, List.of(period(DayOfWeek.MONDAY, "09:00", "10:00")));

        store.advanceScheduleVersion(businessId, staffMemberId, 1, UPDATED_AT.plusSeconds(1));
        store.replacePeriods(businessId, staffMemberId, List.of());

        assertThat(store.findPeriods(businessId, staffMemberId)).isEmpty();
    }

    @Test
    void periodsAreReturnedInDeterministicWeekdayThenTimeOrder() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        WorkingPeriod mondayLate = period(DayOfWeek.MONDAY, "14:00", "18:00");
        WorkingPeriod mondayEarly = period(DayOfWeek.MONDAY, "09:00", "13:00");
        WorkingPeriod sunday = period(DayOfWeek.SUNDAY, "09:00", "12:00");
        store.advanceScheduleVersion(businessId, staffMemberId, 0, UPDATED_AT);

        store.replacePeriods(
                businessId, staffMemberId, List.of(sunday, mondayLate, mondayEarly));

        assertThat(store.findPeriods(businessId, staffMemberId))
                .containsExactly(mondayEarly, mondayLate, sunday);
    }

    @Test
    void staleExpectedVersionIsRejectedWithoutChangingState() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        store.advanceScheduleVersion(businessId, staffMemberId, 0, UPDATED_AT);

        assertThat(store.advanceScheduleVersion(
                        businessId, staffMemberId, 0, UPDATED_AT.plusSeconds(1)))
                .isEmpty();
        assertThat(store.findByBusinessIdAndStaffMemberId(businessId, staffMemberId)
                        .orElseThrow()
                        .version())
                .isEqualTo(1);
    }

    @Test
    void directCallToReplacePeriodsIsIntrinsicallyAtomicWithoutACallerTransaction() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        List<WorkingPeriod> overlapping = List.of(
                period(DayOfWeek.MONDAY, "09:00", "13:00"),
                period(DayOfWeek.MONDAY, "12:00", "18:00"));

        UnexpectedFailure failure = assertThrows(
                UnexpectedFailure.class,
                () -> store.replacePeriods(businessId, staffMemberId, overlapping));

        assertSafeUnexpectedFailure(failure, "staff_working_period_no_overlap");
        assertThat(store.findPeriods(businessId, staffMemberId)).isEmpty();
    }

    @Test
    void unexpectedPersistenceFailuresAreSanitizedAndPreserveTheirCauses() {
        UnexpectedFailure failure = assertThrows(
                UnexpectedFailure.class,
                () -> store.create(new NewStaffWorkingScheduleRow(
                        UUID.randomUUID(), UUID.randomUUID(), CREATED_AT)));

        assertSafeUnexpectedFailure(failure, "staff_working_schedule_staff_member_fk");
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Schedule Store Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "schedule-store-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private UUID createStaffMember(UUID businessId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, contact_email, contact_phone,
                            active, version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, 'Schedule Staff', NULL, NULL,
                            true, 0, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("now", now)
                .update();
        return id;
    }

    private WorkingPeriod period(DayOfWeek weekday, String start, String end) {
        return new WorkingPeriod(weekday, LocalTime.parse(start), LocalTime.parse(end));
    }

    private static void assertSafeUnexpectedFailure(
            UnexpectedFailure failure, String internalConstraint) {
        assertThat(failure)
                .hasMessage("Working schedule persistence operation failed")
                .hasCauseInstanceOf(DataAccessException.class);
        assertThat(failure.getMessage())
                .doesNotContain(internalConstraint)
                .doesNotContain("duplicate key")
                .doesNotContain("org.postgresql")
                .doesNotContain("SQL");
        assertThat(failure.getCause()).isNotNull();
    }
}
