package bg.spotyourslot.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.WorkingTimeOverview;
import bg.spotyourslot.scheduling.WorkingTimeRecords.StaffWorkingTime;
import bg.spotyourslot.scheduling.WorkingTimeRecords.WorkingTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The published working-time read against real PostgreSQL: the inclusion rule and the window limits. */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WorkingTimeOverviewIntegrationTests extends PostgresIntegrationTest {
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired WorkingTimeOverview overview;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    private AvailabilityFixtures fixtures;
    private UUID business;

    @BeforeEach
    void setUp() {
        fixtures = new AvailabilityFixtures(jdbc);
        business = fixtures.business("ACTIVE", "Europe/Sofia");
    }

    private WorkingTime read(LocalDate first, LocalDate last, UUID filter, Set<UUID> withAppointments) {
        return new TransactionTemplate(transactions).execute(
                status -> overview.overview(business, SOFIA, first, last, filter, withAppointments));
    }

    @Test
    void anInactiveStaffMemberIsIncludedOnlyWhenListedAsHavingAnAppointmentOrWhenFilteredExplicitly() {
        UUID active = fixtures.staffMember(business, true);
        UUID inactiveListed = fixtures.staffMember(business, false);
        UUID inactiveOther = fixtures.staffMember(business, false);
        fixtures.everyDay(business, active, "09:00", "10:00");

        WorkingTime unfiltered = read(DAY, DAY, null, Set.of(inactiveListed));
        WorkingTime explicit = read(DAY, DAY, inactiveOther, Set.of());
        WorkingTime missing = read(DAY, DAY, UUID.randomUUID(), Set.of());

        assertThat(unfiltered.staffMembers()).extracting(StaffWorkingTime::id)
                .containsExactlyInAnyOrder(active, inactiveListed);
        assertThat(explicit.staffMembers()).extracting(StaffWorkingTime::id).containsExactly(inactiveOther);
        assertThat(explicit.staffMembers().get(0).workingIntervals()).isEmpty();
        assertThat(missing.staffMembers()).isEmpty();
        assertThat(unfiltered.days()).hasSize(1);
    }

    @Test
    void theWindowHoldsAtMostThirtyOneDatesAndATransactionIsRequired() {
        assertThat(read(DAY, DAY.plusDays(30), null, Set.of()).days()).hasSize(31);
        assertThatThrownBy(() -> read(DAY, DAY.plusDays(31), null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> read(DAY.plusDays(1), DAY, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overview.overview(business, SOFIA, DAY, DAY, null, Set.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(List.of(read(DAY, DAY, null, Set.of()).toString())).isNotEmpty();
    }
}
