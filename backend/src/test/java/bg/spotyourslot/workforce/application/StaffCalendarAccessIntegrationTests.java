package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffCalendarAccess;
import bg.spotyourslot.workforce.StaffCalendarAccess.CalendarStaffMember;
import java.time.DayOfWeek;
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

/** The published Workforce read of the Business calendar against real PostgreSQL. */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffCalendarAccessIntegrationTests extends PostgresIntegrationTest {
    @Autowired StaffCalendarAccess access;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    private AvailabilityFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AvailabilityFixtures(jdbc);
    }

    private List<CalendarStaffMember> find(UUID business, UUID filter, boolean periods) {
        return new TransactionTemplate(transactions).execute(status -> access.find(business, filter, periods));
    }

    private UUID staff(UUID business, String name, boolean active) {
        UUID id = fixtures.staffMember(business, active);
        jdbc.sql("UPDATE staff_member SET display_name = :name WHERE id = :id")
                .param("name", name).param("id", id).update();
        return id;
    }

    @Test
    void everyStaffMemberIsReturnedActiveOrNotInNameThenIdentifierOrder() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID second = staff(business, "Борис", true);
        UUID first = staff(business, "Анна", false);
        UUID third = staff(business, "Владимир", true);

        List<CalendarStaffMember> found = find(business, null, false);

        assertThat(found).extracting(CalendarStaffMember::id).containsExactly(first, second, third);
        assertThat(found).extracting(CalendarStaffMember::active).containsExactly(false, true, true);
        assertThat(found).extracting(CalendarStaffMember::displayName).containsExactly("Анна", "Борис", "Владимир");
        assertThat(found).allSatisfy(member -> assertThat(member.weeklyPeriods()).isEmpty());
    }

    @Test
    void weeklyPeriodsAreOrderedAndJoinedForActiveStaffMembersOnly() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID active = staff(business, "Активен", true);
        UUID inactive = staff(business, "Неактивен", false);
        fixtures.period(business, active, DayOfWeek.WEDNESDAY, "13:00", "17:00");
        fixtures.period(business, active, DayOfWeek.MONDAY, "09:00", "12:00");
        fixtures.period(business, active, DayOfWeek.WEDNESDAY, "08:00", "12:00");
        fixtures.period(business, inactive, DayOfWeek.MONDAY, "09:00", "12:00");

        List<CalendarStaffMember> found = find(business, null, true);

        CalendarStaffMember activeMember = found.stream().filter(member -> member.id().equals(active)).findFirst().orElseThrow();
        CalendarStaffMember inactiveMember = found.stream().filter(member -> member.id().equals(inactive)).findFirst().orElseThrow();
        assertThat(activeMember.weeklyPeriods()).extracting(period -> period.weekday().getValue() + "@" + period.startTime())
                .containsExactly("1@09:00", "3@08:00", "3@13:00");
        assertThat(inactiveMember.weeklyPeriods()).isEmpty();
        assertThat(find(business, null, false).stream().filter(m -> m.id().equals(active)).findFirst().orElseThrow()
                .weeklyPeriods()).isEmpty();
    }

    @Test
    void aFilterReturnsOnlyThatStaffMemberAndMissingForeignAndOtherBusinessAreIndistinguishablyEmpty() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID other = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID mine = staff(business, "Моят", false);
        UUID foreign = staff(other, "Чужд", true);
        staff(business, "Друг", true);

        assertThat(find(business, mine, true)).extracting(CalendarStaffMember::id).containsExactly(mine);
        assertThat(find(business, foreign, true)).isEmpty();
        assertThat(find(business, UUID.randomUUID(), true)).isEmpty();
        assertThat(find(other, null, false)).extracting(CalendarStaffMember::id).containsExactly(foreign);
    }

    @Test
    void itRequiresATransactionAndRedactsToString() {
        UUID business = fixtures.business("ACTIVE", "Europe/Sofia");
        UUID member = staff(business, "СЕНТИНЕЛ-ИМЕ", true);

        assertThatThrownBy(() -> access.find(business, null, false)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(find(business, member, false).get(0).toString()).doesNotContain("СЕНТИНЕЛ");
    }
}
