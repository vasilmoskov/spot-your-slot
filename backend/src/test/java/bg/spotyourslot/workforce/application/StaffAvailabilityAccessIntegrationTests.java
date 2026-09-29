package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffAvailabilityAccess;
import bg.spotyourslot.workforce.StaffAvailabilityAccess.EligibleStaffMember;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffAvailabilityAccessIntegrationTests extends PostgresIntegrationTest {
    @Autowired StaffAvailabilityAccess access;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    private AvailabilityFixtures fixtures;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        fixtures = new AvailabilityFixtures(jdbc);
        transaction = new TransactionTemplate(transactions);
    }

    private List<EligibleStaffMember> find(UUID businessId, UUID serviceId) {
        return transaction.execute(status -> access.findEligibleForService(businessId, serviceId));
    }

    @Test
    void returnsOnlyActiveAssignedStaffMembersOfTheBusiness() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        UUID otherService = fixtures.service(business, 30, true);
        UUID eligible = fixtures.staffMember(business, true);
        UUID inactive = fixtures.staffMember(business, false);
        UUID unassigned = fixtures.staffMember(business, true);
        UUID assignedElsewhere = fixtures.staffMember(business, true);
        fixtures.assign(business, eligible, service);
        fixtures.assign(business, inactive, service);
        fixtures.assign(business, assignedElsewhere, otherService);

        assertThat(find(business, service)).extracting(EligibleStaffMember::id)
                .containsExactly(eligible);
        assertThat(find(business, otherService)).extracting(EligibleStaffMember::id)
                .containsExactly(assignedElsewhere);
        assertThat(unassigned).isNotEqualTo(eligible);
    }

    @Test
    void aRemovedAssignmentOrADeactivationRemovesTheStaffMemberAndReactivationRestoresIt() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        UUID staff = fixtures.staffMember(business, true);
        fixtures.assign(business, staff, service);
        assertThat(find(business, service)).hasSize(1);

        fixtures.unassign(business, staff, service);
        assertThat(find(business, service)).isEmpty();

        fixtures.assign(business, staff, service);
        fixtures.setStaffMemberActive(staff, false);
        assertThat(find(business, service)).isEmpty();

        fixtures.setStaffMemberActive(staff, true);
        assertThat(find(business, service)).hasSize(1);
    }

    @Test
    void isTenantScoped() {
        UUID businessA = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID businessB = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID serviceA = fixtures.service(businessA, 30, true);
        UUID serviceB = fixtures.service(businessB, 30, true);
        UUID staffA = fixtures.staffMember(businessA, true);
        UUID staffB = fixtures.staffMember(businessB, true);
        fixtures.assign(businessA, staffA, serviceA);
        fixtures.assign(businessB, staffB, serviceB);

        assertThat(find(businessA, serviceA)).extracting(EligibleStaffMember::id)
                .containsExactly(staffA);
        assertThat(find(businessA, serviceB)).isEmpty();
        assertThat(find(businessB, serviceA)).isEmpty();
        assertThat(find(businessB, serviceB)).extracting(EligibleStaffMember::id)
                .containsExactly(staffB);
    }

    @Test
    void aStaffMemberWithoutPeriodsIsPresentWithAnEmptyList() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        UUID staff = fixtures.staffMember(business, true);
        fixtures.assign(business, staff, service);

        List<EligibleStaffMember> found = find(business, service);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().weeklyPeriods()).isEmpty();
    }

    @Test
    void periodsAreReturnedOrderedByWeekdayStartAndEndIncludingSplitDays() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        UUID staff = fixtures.staffMember(business, true);
        fixtures.assign(business, staff, service);
        fixtures.period(business, staff, DayOfWeek.WEDNESDAY, "14:00", "18:00");
        fixtures.period(business, staff, DayOfWeek.MONDAY, "09:00", "13:00");
        fixtures.period(business, staff, DayOfWeek.WEDNESDAY, "09:00", "12:00");
        fixtures.period(business, staff, DayOfWeek.SUNDAY, "10:00", "11:00");

        assertThat(find(business, service).getFirst().weeklyPeriods()).containsExactly(
                period(DayOfWeek.MONDAY, 9, 0, 13, 0),
                period(DayOfWeek.WEDNESDAY, 9, 0, 12, 0),
                period(DayOfWeek.WEDNESDAY, 14, 0, 18, 0),
                period(DayOfWeek.SUNDAY, 10, 0, 11, 0));
    }

    @Test
    void staffMembersAreOrderedByIdAndEachKeepsOnlyItsOwnPeriods() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        UUID[] staff = new UUID[10];
        for (int index = 0; index < staff.length; index++) {
            staff[index] = fixtures.staffMember(business, true);
            fixtures.assign(business, staff[index], service);
            fixtures.period(
                    business, staff[index], DayOfWeek.MONDAY, "09:00", "1" + index + ":00");
        }

        List<EligibleStaffMember> found = find(business, service);

        assertThat(found).extracting(EligibleStaffMember::id).containsExactlyInAnyOrder(staff);
        // PostgreSQL orders uuid values bytewise, which matches the canonical text form.
        assertThat(found).extracting(member -> member.id().toString())
                .isSortedAccordingTo(Comparator.naturalOrder());
        for (EligibleStaffMember member : found) {
            int index = Arrays.asList(staff).indexOf(member.id());
            assertThat(member.weeklyPeriods()).containsExactly(
                    period(DayOfWeek.MONDAY, 9, 0, 10 + index, 0));
        }
    }

    @Test
    void theResultIsDeeplyImmutable() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);
        UUID staff = fixtures.staffMember(business, true);
        fixtures.assign(business, staff, service);
        fixtures.period(business, staff, DayOfWeek.MONDAY, "09:00", "10:00");

        List<EligibleStaffMember> found = find(business, service);

        assertThatThrownBy(() -> found.add(found.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> found.getFirst().weeklyPeriods().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theLookupRequiresTheCallersTransaction() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID service = fixtures.service(business, 30, true);

        assertThatThrownBy(() -> access.findEligibleForService(business, service))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private static WorkingPeriod period(
            DayOfWeek day, int startHour, int startMinute, int endHour, int endMinute) {
        return new WorkingPeriod(
                day, LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }
}
