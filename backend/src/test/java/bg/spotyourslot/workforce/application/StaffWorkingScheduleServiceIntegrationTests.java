package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InputField;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffWorkingScheduleNotFound;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.NewStaffWorkingScheduleRow;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingSchedulePersistenceException.UnexpectedFailure;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingScheduleStore;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@Import(StaffWorkingScheduleServiceIntegrationTests.FixedClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffWorkingScheduleServiceIntegrationTests extends PostgresIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-23T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-23T09:00:00Z");

    @Autowired StaffWorkingScheduleService schedules;
    @Autowired StaffWorkingScheduleStore store;
    @Autowired JdbcClient jdbc;

    @Test
    void readsTheEmptyVersionZeroScheduleBeforeAnyReplacement() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));

        StaffWorkingScheduleDetails details = schedules.find(businessId, staffMemberId);

        assertThat(details.staffMemberId()).isEqualTo(staffMemberId);
        assertThat(details.periods()).isEmpty();
        assertThat(details.version()).isZero();
        assertThat(details.createdAt()).isEqualTo(CREATED_AT);
        assertThat(details.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void returnsSafeNotFoundForMissingOrCrossBusinessStaffMember() {
        UUID businessId = createBusiness();
        UUID otherBusiness = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));

        assertThatThrownBy(() -> schedules.find(businessId, UUID.randomUUID()))
                .isInstanceOf(StaffWorkingScheduleNotFound.class);
        assertThatThrownBy(() -> schedules.find(otherBusiness, staffMemberId))
                .isInstanceOf(StaffWorkingScheduleNotFound.class);
    }

    @Test
    void replacesTheCompleteWeekAtomicallyAndAdvancesTheVersion() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        List<WorkingPeriod> desired = List.of(
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(18, 0)),
                new WorkingPeriod(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(12, 0)));

        StaffWorkingScheduleDetails replaced = schedules.replace(
                businessId, staffMemberId, new ReplaceWorkingPeriodsCommand(desired, 0L));

        assertThat(replaced.version()).isEqualTo(1);
        assertThat(replaced.createdAt()).isEqualTo(CREATED_AT);
        assertThat(replaced.updatedAt()).isEqualTo(NOW);
        assertThat(replaced.periods()).containsExactlyElementsOf(desired);
        assertThat(schedules.find(businessId, staffMemberId)).isEqualTo(replaced);
    }

    @Test
    void identicalReplacementStillIncrementsVersionExactlyOnce() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        List<WorkingPeriod> periods = List.of(
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        schedules.replace(
                businessId, staffMemberId, new ReplaceWorkingPeriodsCommand(periods, 0L));

        StaffWorkingScheduleDetails replayed = schedules.replace(
                businessId, staffMemberId, new ReplaceWorkingPeriodsCommand(periods, 1L));

        assertThat(replayed.version()).isEqualTo(2);
        assertThat(replayed.periods()).containsExactlyElementsOf(periods);
    }

    @Test
    void clearingOneWeekdayPreservesTheOthers() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        WorkingPeriod tuesday = new WorkingPeriod(
                DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
        schedules.replace(
                businessId,
                staffMemberId,
                new ReplaceWorkingPeriodsCommand(
                        List.of(
                                new WorkingPeriod(
                                        DayOfWeek.MONDAY,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(17, 0)),
                                tuesday),
                        0L));

        StaffWorkingScheduleDetails cleared = schedules.replace(
                businessId,
                staffMemberId,
                new ReplaceWorkingPeriodsCommand(List.of(tuesday), 1L));

        assertThat(cleared.periods()).containsExactly(tuesday);
    }

    @Test
    void clearingTheCompleteWeekLeavesAnEmptySchedule() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        schedules.replace(
                businessId,
                staffMemberId,
                new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(
                                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))),
                        0L));

        StaffWorkingScheduleDetails cleared = schedules.replace(
                businessId, staffMemberId, new ReplaceWorkingPeriodsCommand(List.of(), 1L));

        assertThat(cleared.periods()).isEmpty();
    }

    @Test
    void staleExpectedVersionIsRejectedAsConcurrentUpdate() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));

        assertThatThrownBy(() -> schedules.replace(
                        businessId,
                        staffMemberId,
                        new ReplaceWorkingPeriodsCommand(List.of(), 1L)))
                .isInstanceOf(ConcurrentUpdate.class)
                .hasMessage("Working schedule was changed by another operation");
    }

    @Test
    void invalidReplacementRollsBackWithoutChangingStateOrVersion() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        List<WorkingPeriod> overlapping = List.of(
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(18, 0)));

        assertThatThrownBy(() -> schedules.replace(
                        businessId,
                        staffMemberId,
                        new ReplaceWorkingPeriodsCommand(overlapping, 0L)))
                .isInstanceOfSatisfying(InvalidInput.class,
                        failure -> assertThat(failure.field())
                                .isEqualTo(InputField.OVERLAPPING_PERIOD));

        StaffWorkingScheduleDetails unchanged = schedules.find(businessId, staffMemberId);
        assertThat(unchanged.version()).isZero();
        assertThat(unchanged.periods()).isEmpty();
    }

    @Test
    void persistenceFailureDuringPeriodInsertRollsBackTheVersionAdvanceToo() {
        UUID businessId = createBusiness();
        UUID staffMemberId = createStaffMember(businessId);
        store.create(new NewStaffWorkingScheduleRow(businessId, staffMemberId, CREATED_AT));
        List<WorkingPeriod> initial = List.of(
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        StaffWorkingScheduleDetails seeded = schedules.replace(
                businessId, staffMemberId, new ReplaceWorkingPeriodsCommand(initial, 0L));

        installFailingPeriodInsertTrigger();
        try {
            List<WorkingPeriod> attempted = List.of(
                    new WorkingPeriod(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)));

            assertThatThrownBy(() -> schedules.replace(
                            businessId,
                            staffMemberId,
                            new ReplaceWorkingPeriodsCommand(attempted, seeded.version())))
                    .isInstanceOfSatisfying(UnexpectedFailure.class, failure -> {
                        assertThat(failure).hasMessage(
                                "Working schedule persistence operation failed");
                        assertThat(failure.getMessage())
                                .doesNotContain("deterministic test failure")
                                .doesNotContain("test_fail_staff_working_period_insert")
                                .doesNotContain("org.postgresql")
                                .doesNotContain("SQL");
                    });
        } finally {
            dropFailingPeriodInsertTrigger();
        }

        StaffWorkingScheduleDetails afterFailure = schedules.find(businessId, staffMemberId);
        assertThat(afterFailure.version()).isEqualTo(seeded.version());
        assertThat(afterFailure.updatedAt()).isEqualTo(seeded.updatedAt());
        assertThat(afterFailure.periods()).containsExactlyElementsOf(seeded.periods());
    }

    private void installFailingPeriodInsertTrigger() {
        jdbc.sql("""
                        CREATE OR REPLACE FUNCTION test_fail_staff_working_period_insert()
                        RETURNS trigger AS $$
                        BEGIN
                            RAISE EXCEPTION 'deterministic test failure';
                        END;
                        $$ LANGUAGE plpgsql
                        """)
                .update();
        jdbc.sql("""
                        CREATE TRIGGER test_fail_staff_working_period_insert_trigger
                        BEFORE INSERT ON staff_working_period
                        FOR EACH ROW EXECUTE FUNCTION test_fail_staff_working_period_insert()
                        """)
                .update();
    }

    private void dropFailingPeriodInsertTrigger() {
        jdbc.sql("""
                        DROP TRIGGER IF EXISTS test_fail_staff_working_period_insert_trigger
                        ON staff_working_period
                        """)
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS test_fail_staff_working_period_insert()")
                .update();
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Schedule Service Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "schedule-service-" + id)
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

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
