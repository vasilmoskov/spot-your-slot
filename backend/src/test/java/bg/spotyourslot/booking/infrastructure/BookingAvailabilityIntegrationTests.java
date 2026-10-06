package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * The real availability orchestration over real committed Appointments: a refreshed calculation
 * no longer offers occupied time, adjacency stays bookable, cancelling releases the time, and
 * other StaffMembers and Businesses are unaffected. The clock is fixed.
 */
@Import(BookingAvailabilityIntegrationTests.ClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BookingAvailabilityIntegrationTests extends PostgresIntegrationTest {
    /** Tuesday 11:00 in Sofia (UTC+3): the earliest candidate start is 13:00 today. */
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);

    @Autowired AvailabilityQuery availability;
    @Autowired AppointmentStore store;
    @Autowired JdbcClient jdbc;
    @Autowired MutableTestClock clock;
    @Autowired PlatformTransactionManager transactionManager;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
        new AvailabilityFixtures(jdbc).everyDay(tenant.business(), tenant.staff(), "09:00", "12:00");
    }

    private List<LocalTime> thursdayStarts(UUID staffOrNull) {
        AvailabilitySnapshot snapshot = availability.calculate(
                tenant.business(), tenant.service(), staffOrNull);
        return snapshot.slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(THURSDAY))
                .filter(slot -> staffOrNull == null || slot.staffMemberIds().contains(staffOrNull))
                .map(slot -> slot.start().atZone(SOFIA).toLocalTime())
                .toList();
    }

    private Appointment book(String start, int minutes) {
        Instant instant = THURSDAY.atTime(LocalTime.parse(start)).atZone(SOFIA).toInstant();
        return new TransactionTemplate(transactionManager).execute(
                status -> store.insert(AppointmentFixtures.online(tenant, instant, minutes)));
    }

    @Test
    void aBookedTimeIsNoLongerOfferedAfterARefreshedCalculation() {
        assertThat(thursdayStarts(tenant.staff())).contains(
                LocalTime.of(9, 30), LocalTime.of(9, 45), LocalTime.of(10, 0),
                LocalTime.of(10, 15), LocalTime.of(10, 30));

        book("10:00", 30);

        List<LocalTime> after = thursdayStarts(tenant.staff());
        // A 30-minute Service starting at 09:45, 10:00, or 10:15 would overlap 10:00-10:30.
        assertThat(after).doesNotContain(
                LocalTime.of(9, 45), LocalTime.of(10, 0), LocalTime.of(10, 15));
        // Adjacency on both sides is still bookable (half-open ranges).
        assertThat(after).contains(LocalTime.of(9, 30), LocalTime.of(10, 30));
    }

    @Test
    void cancellingARowReleasesTheTimeAgain() {
        Appointment booked = book("10:00", 30);
        assertThat(thursdayStarts(tenant.staff())).doesNotContain(LocalTime.of(10, 0));

        jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE id = :id")
                .param("id", booked.id()).update();

        assertThat(thursdayStarts(tenant.staff())).contains(
                LocalTime.of(9, 45), LocalTime.of(10, 0), LocalTime.of(10, 15));
    }

    @Test
    void anotherStaffMemberStaysAvailableAndAnyPreferenceListsOnlyTheFreeOnes() {
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        new AvailabilityFixtures(jdbc).everyDay(tenant.business(), second, "09:00", "12:00");
        book("10:00", 30);

        AvailabilitySnapshot snapshot = availability.calculate(
                tenant.business(), tenant.service(), null);
        var tenOClock = snapshot.slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(THURSDAY))
                .filter(slot -> slot.start().atZone(SOFIA).toLocalTime().equals(LocalTime.of(10, 0)))
                .toList();

        assertThat(tenOClock).hasSize(1);
        assertThat(tenOClock.get(0).staffMemberIds()).containsExactly(second);
        assertThat(thursdayStarts(second)).contains(LocalTime.of(10, 0));
        assertThat(thursdayStarts(tenant.staff())).doesNotContain(LocalTime.of(10, 0));
    }

    @Test
    void anotherBusinessIsNeverAffected() {
        Tenant other = fixtures.tenant();
        new AvailabilityFixtures(jdbc).everyDay(other.business(), other.staff(), "09:00", "12:00");
        book("10:00", 30);

        AvailabilitySnapshot snapshot = availability.calculate(
                other.business(), other.service(), other.staff());

        assertThat(snapshot.slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(THURSDAY))
                .map(slot -> slot.start().atZone(SOFIA).toLocalTime()))
                .contains(LocalTime.of(10, 0));
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableTestClock bookingAvailabilityTestClock() {
            return new MutableTestClock(NOW);
        }
    }
}
