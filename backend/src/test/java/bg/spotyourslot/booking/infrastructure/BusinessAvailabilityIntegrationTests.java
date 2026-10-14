package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.BusinessAvailability;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * The Business-side availability policy (ADR-0029 section 1) through the real orchestration over
 * real committed Appointments: zero notice, an explicit date window, the supplied (stored) duration,
 * a schedule that does not depend on any Service assignment, the exceptions and daylight-saving
 * rules shared with the public path, and the ignore-self rule, which omits exactly the Appointment
 * being moved. The caller supplies the instant; the clock is never read.
 */
@Import(BusinessAvailabilityIntegrationTests.ClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessAvailabilityIntegrationTests extends PostgresIntegrationTest {
    /** Tuesday 11:00 in Sofia (UTC+3). */
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);

    @Autowired AvailabilityQuery availability;
    @Autowired AppointmentStore store;
    @Autowired ScheduleExceptionStore exceptions;
    @Autowired JdbcClient jdbc;
    @Autowired MutableTestClock clock;
    @Autowired PlatformTransactionManager transactionManager;

    private AppointmentFixtures fixtures;
    private AvailabilityFixtures availabilityFixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fixtures = new AppointmentFixtures(jdbc);
        availabilityFixtures = new AvailabilityFixtures(jdbc);
        tenant = fixtures.tenant();
        availabilityFixtures.everyDay(tenant.business(), tenant.staff(), "09:00", "12:00");
    }

    private BusinessAvailability calculate(
            LocalDate first, LocalDate last, int minutes, Instant now, UUID ignored) {
        return availability.calculateForBusiness(
                tenant.business(), Duration.ofMinutes(minutes), tenant.staff(), first, last, now, ignored);
    }

    private List<LocalTime> starts(BusinessAvailability result, LocalDate date) {
        return result.slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(date))
                .map(slot -> slot.start().atZone(SOFIA).toLocalTime())
                .toList();
    }

    private Appointment book(LocalDate date, String start, int minutes) {
        Instant instant = date.atTime(LocalTime.parse(start)).atZone(SOFIA).toInstant();
        return new TransactionTemplate(transactionManager).execute(
                status -> store.insert(AppointmentFixtures.manual(tenant, instant, minutes)));
    }

    // ---- zero notice, window, duration ---------------------------------------

    @Test
    void thereIsNoMinimumNoticeAndAStartExactlyAtNowIsOffered() {
        BusinessAvailability result = calculate(TODAY, TODAY, 30, NOW, null);

        // Now is 11:00 Sofia: the public policy would start at 13:00, the Business policy at 11:00.
        assertThat(starts(result, TODAY)).containsExactly(
                LocalTime.of(11, 0), LocalTime.of(11, 15), LocalTime.of(11, 30));
    }

    @Test
    void aNowBetweenGridPointsStartsAtTheNextGridPointAndNothingEarlierIsOffered() {
        BusinessAvailability result = calculate(TODAY, TODAY, 30, NOW.plusSeconds(7 * 60), null);

        assertThat(starts(result, TODAY)).containsExactly(LocalTime.of(11, 15), LocalTime.of(11, 30));
        assertThat(starts(calculate(TODAY, TODAY, 30, NOW.plusSeconds(1), null), TODAY))
                .doesNotContain(LocalTime.of(11, 0));
    }

    @Test
    void thePublicPolicyOnTheSameDataStartsTwoHoursLater() {
        var snapshot = availability.calculate(tenant.business(), tenant.service(), tenant.staff());

        assertThat(snapshot.slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(TODAY))
                .map(slot -> slot.start().atZone(SOFIA).toLocalTime())).isEmpty();
    }

    @Test
    void aStartBeyondThePublicHorizonIsOfferedAndThePublicPolicyDoesNotOfferIt() {
        LocalDate far = TODAY.plusDays(90);

        BusinessAvailability result = calculate(far, far, 30, NOW, null);

        assertThat(starts(result, far)).isNotEmpty().contains(LocalTime.of(9, 0), LocalTime.of(11, 30));
        var publicSnapshot = availability.calculate(tenant.business(), tenant.service(), tenant.staff());
        assertThat(publicSnapshot.slots().stream()
                .anyMatch(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(far))).isFalse();
    }

    @Test
    void aPastDateIsNeverOffered() {
        BusinessAvailability yesterday = calculate(TODAY.minusDays(1), TODAY.minusDays(1), 30, NOW, null);
        BusinessAvailability earlierToday = calculate(TODAY, TODAY, 30, NOW, null);

        assertThat(yesterday.slots()).isEmpty();
        assertThat(starts(earlierToday, TODAY)).doesNotContain(LocalTime.of(9, 0), LocalTime.of(10, 45));
    }

    @Test
    void theSuppliedStoredDurationDecidesTheSlotEndAndWhichStartsFit() {
        BusinessAvailability ninety = calculate(THURSDAY, THURSDAY, 90, NOW, null);

        assertThat(starts(ninety, THURSDAY)).containsExactly(
                LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30), LocalTime.of(9, 45),
                LocalTime.of(10, 0), LocalTime.of(10, 15), LocalTime.of(10, 30));
        for (AvailabilitySlot slot : ninety.slots()) {
            assertThat(Duration.between(slot.start(), slot.end())).isEqualTo(Duration.ofMinutes(90));
        }
        assertThat(ninety.occupiedDuration()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void theResultCarriesTheWindowTheSuppliedInstantAndTheBusinessZone() {
        BusinessAvailability result = calculate(THURSDAY, THURSDAY.plusDays(2), 30, NOW, null);

        assertThat(result.firstDate()).isEqualTo(THURSDAY);
        assertThat(result.lastDate()).isEqualTo(THURSDAY.plusDays(2));
        assertThat(result.calculatedAt()).isEqualTo(NOW);
        assertThat(result.timezone()).isEqualTo(SOFIA);
        assertThat(result.slots().stream().map(slot -> slot.start().atZone(SOFIA).toLocalDate())
                .distinct().toList())
                .containsExactly(THURSDAY, THURSDAY.plusDays(1), THURSDAY.plusDays(2));
    }

    @Test
    void theWindowHoldsAtMostThirtyOneDatesAndStaysInsideTheSupportedDates() {
        calculate(THURSDAY, THURSDAY.plusDays(30), 30, NOW, null);

        assertThatThrownBy(() -> calculate(THURSDAY, THURSDAY.plusDays(31), 30, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(THURSDAY.plusDays(1), THURSDAY, 30, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(LocalDate.of(1999, 12, 31), LocalDate.of(1999, 12, 31), 30, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(LocalDate.of(2101, 1, 1), LocalDate.of(2101, 1, 1), 30, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(THURSDAY, THURSDAY, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculate(THURSDAY, THURSDAY, 481, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theClockIsNeverReadByTheBusinessEntry() {
        clock.resetReads();

        calculate(THURSDAY, THURSDAY, 30, NOW, null);

        assertThat(clock.reads()).isZero();
    }

    // ---- the StaffMember schedule is independent of Service assignments ------

    @Test
    void theScheduleDoesNotDependOnAnyServiceAssignmentOrOnTheServiceBeingActive() {
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), tenant.service());
        availabilityFixtures.setServiceActive(tenant.service(), false);

        BusinessAvailability result = calculate(THURSDAY, THURSDAY, 30, NOW, null);

        assertThat(starts(result, THURSDAY)).contains(LocalTime.of(9, 0), LocalTime.of(11, 30));
    }

    @Test
    void anInactiveMissingOrForeignStaffMemberIsNotEligible() {
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);
        assertThatThrownBy(() -> calculate(THURSDAY, THURSDAY, 30, NOW, null))
                .isInstanceOf(StaffMemberNotEligible.class);
        availabilityFixtures.setStaffMemberActive(tenant.staff(), true);

        Tenant other = fixtures.tenant();
        assertThatThrownBy(() -> availability.calculateForBusiness(
                tenant.business(), Duration.ofMinutes(30), other.staff(), THURSDAY, THURSDAY, NOW, null))
                .isInstanceOf(StaffMemberNotEligible.class);
        assertThatThrownBy(() -> availability.calculateForBusiness(
                tenant.business(), Duration.ofMinutes(30), UUID.randomUUID(), THURSDAY, THURSDAY, NOW, null))
                .isInstanceOf(StaffMemberNotEligible.class);
    }

    @Test
    void aDraftSuspendedOrMissingBusinessIsNotBookableForBusinessCalculations() {
        availabilityFixtures.setBusinessStatus(tenant.business(), "DRAFT");
        assertThatThrownBy(() -> calculate(THURSDAY, THURSDAY, 30, NOW, null))
                .isInstanceOf(BusinessNotBookable.class);
        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");
        assertThatThrownBy(() -> calculate(THURSDAY, THURSDAY, 30, NOW, null))
                .isInstanceOf(BusinessNotBookable.class);
        assertThatThrownBy(() -> availability.calculateForBusiness(
                UUID.randomUUID(), Duration.ofMinutes(30), tenant.staff(), THURSDAY, THURSDAY, NOW, null))
                .isInstanceOf(BusinessNotBookable.class);
    }

    // ---- ignore-self ----------------------------------------------------------

    @Test
    void withoutIgnoringAnAppointmentBlocksItsOwnTimeAndWithIgnoringItsOwnIntervalIsFree() {
        Appointment own = book(THURSDAY, "10:00", 30);

        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, null), THURSDAY))
                .doesNotContain(LocalTime.of(9, 45), LocalTime.of(10, 0), LocalTime.of(10, 15));
        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, own.id()), THURSDAY))
                .contains(LocalTime.of(9, 45), LocalTime.of(10, 0), LocalTime.of(10, 15));
    }

    @Test
    void onlyTheNamedAppointmentIsIgnoredWhileAdjacentAndOverlappingNeighboursStillBlock() {
        Appointment own = book(THURSDAY, "10:00", 30);
        Appointment adjacentAfter = book(THURSDAY, "10:30", 30);
        Appointment adjacentBefore = book(THURSDAY, "09:30", 30);

        List<LocalTime> ignoringOwn = starts(
                calculate(THURSDAY, THURSDAY, 30, NOW, own.id()), THURSDAY);
        // 10:00-10:30 fits exactly between the two neighbours; any other start in 09:30-11:00 overlaps one.
        assertThat(ignoringOwn).contains(LocalTime.of(10, 0));
        assertThat(ignoringOwn).doesNotContain(
                LocalTime.of(9, 15), LocalTime.of(9, 30), LocalTime.of(9, 45), LocalTime.of(10, 15),
                LocalTime.of(10, 30), LocalTime.of(10, 45));

        // Ignoring a neighbour frees the neighbour, not the Appointment that was not named.
        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, adjacentAfter.id()), THURSDAY))
                .contains(LocalTime.of(10, 30)).doesNotContain(LocalTime.of(10, 0));
        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, adjacentBefore.id()), THURSDAY))
                .contains(LocalTime.of(9, 30)).doesNotContain(LocalTime.of(10, 0));
    }

    @Test
    void anOverlappingNeighbourIsNotIgnoredByNamingAnotherAppointment() {
        Appointment own = book(THURSDAY, "10:00", 60);
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), second, "09:00", "12:00");
        Appointment otherStaff = new TransactionTemplate(transactionManager).execute(status -> store.insert(
                AppointmentFixtures.manual(
                        new Tenant(tenant.business(), tenant.service(), second, tenant.customer()),
                        THURSDAY.atTime(10, 0).atZone(SOFIA).toInstant(), 60)));

        // Naming the other StaffMember's Appointment changes nothing for this StaffMember.
        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, otherStaff.id()), THURSDAY))
                .doesNotContain(LocalTime.of(10, 0), LocalTime.of(10, 30));
        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, UUID.randomUUID()), THURSDAY))
                .doesNotContain(LocalTime.of(10, 0), LocalTime.of(10, 30));
        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, own.id()), THURSDAY))
                .contains(LocalTime.of(10, 0), LocalTime.of(10, 30));
    }

    @Test
    void aCancelledAppointmentIsFreeAnyway() {
        Appointment cancelled = book(THURSDAY, "10:00", 30);
        fixtures.cancel(cancelled.id());

        assertThat(starts(calculate(THURSDAY, THURSDAY, 30, NOW, null), THURSDAY))
                .contains(LocalTime.of(10, 0));
    }

    // ---- exceptions and daylight-saving time ----------------------------------

    @Test
    void aStaffTimeOffDayIsRespectedBecauseTheExceptionRulesAreShared() {
        exceptions.insert(new NewScheduleException(
                UUID.randomUUID(),
                tenant.business(),
                ScheduleExceptionContent.staffTimeOffDays(tenant.staff(), THURSDAY, THURSDAY),
                NOW));

        assertThat(calculate(THURSDAY, THURSDAY, 30, NOW, null).slots()).isEmpty();
        assertThat(starts(calculate(THURSDAY, THURSDAY.plusDays(1), 30, NOW, null), THURSDAY.plusDays(1)))
                .isNotEmpty();
    }

    @Test
    void bothOccurrencesOfARepeatedHourAreDistinctInstantsJudgedByTheirOwnInstant() {
        Tenant repeated = fixtures.tenant();
        availabilityFixtures.period(
                repeated.business(), repeated.staff(), DayOfWeek.SUNDAY, "02:00", "06:00");
        LocalDate fallBack = LocalDate.of(2026, 10, 25);
        // Sofia: 04:00 EEST (+03:00) becomes 03:00 EET (+02:00) at 01:00Z, so 03:00-04:00 occurs twice.
        Instant firstThreeThirty = Instant.parse("2026-10-25T00:30:00Z");
        Instant secondThreeThirty = Instant.parse("2026-10-25T01:30:00Z");

        BusinessAvailability early = availability.calculateForBusiness(
                repeated.business(), Duration.ofMinutes(30), repeated.staff(), fallBack, fallBack,
                Instant.parse("2026-10-25T00:45:00Z"), null);
        List<Instant> earlyStarts = early.slots().stream().map(AvailabilitySlot::start).toList();
        // The first 03:30 (+03:00) is already past; the second 03:30 (+02:00) is not.
        assertThat(earlyStarts).doesNotContain(firstThreeThirty).contains(secondThreeThirty);
        assertThat(earlyStarts).contains(Instant.parse("2026-10-25T00:45:00Z"));
        assertThat(early.slots().stream()
                .filter(slot -> slot.start().equals(secondThreeThirty)).findFirst().orElseThrow()
                .startOffset()).isEqualTo(ZoneOffset.ofHours(2));
        assertThat(early.slots().stream()
                .filter(slot -> slot.start().equals(Instant.parse("2026-10-25T00:45:00Z")))
                .findFirst().orElseThrow().startOffset()).isEqualTo(ZoneOffset.ofHours(3));

        BusinessAvailability exactlyNow = availability.calculateForBusiness(
                repeated.business(), Duration.ofMinutes(30), repeated.staff(), fallBack, fallBack,
                secondThreeThirty, null);
        assertThat(exactlyNow.slots().get(0).start()).isEqualTo(secondThreeThirty);
    }

    @Test
    void aTimeInsideTheSpringGapNeverExistsAndNeighbouringInstantsAreOffered() {
        Tenant gap = fixtures.tenant();
        availabilityFixtures.period(gap.business(), gap.staff(), DayOfWeek.SUNDAY, "02:00", "06:00");
        LocalDate springForward = LocalDate.of(2027, 3, 28);
        // Sofia: 03:00 EET (+02:00) becomes 04:00 EEST (+03:00) at 01:00Z; 03:00-04:00 does not exist.

        BusinessAvailability result = availability.calculateForBusiness(
                gap.business(), Duration.ofMinutes(30), gap.staff(), springForward, springForward, NOW, null);

        List<LocalTime> localStarts = result.slots().stream()
                .map(slot -> slot.start().atZone(SOFIA).toLocalTime()).toList();
        assertThat(localStarts).doesNotContain(LocalTime.of(3, 0), LocalTime.of(3, 15), LocalTime.of(3, 30), LocalTime.of(3, 45));
        assertThat(localStarts).contains(LocalTime.of(2, 30), LocalTime.of(4, 0));
        assertThat(result.slots().stream().map(AvailabilitySlot::start))
                .contains(Instant.parse("2027-03-28T00:30:00Z"), Instant.parse("2027-03-28T01:00:00Z"));
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableTestClock businessAvailabilityTestClock() {
            return new MutableTestClock(NOW);
        }
    }
}
