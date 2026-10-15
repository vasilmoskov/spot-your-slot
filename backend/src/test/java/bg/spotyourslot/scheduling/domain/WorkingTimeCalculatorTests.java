package bg.spotyourslot.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator.Closure;
import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator.ClosureKind;
import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator.DayBounds;
import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator.Interval;
import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator.StaffInput;
import bg.spotyourslot.scheduling.domain.WorkingTimeCalculator.StaffWorkingTime;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The pure working-time facts of the calendar (ADR-0028 section 12): net instant intervals, the
 * closure marker rule, daylight-saving resolution, and the inactive StaffMember rule. This is not
 * an availability test: nothing here knows notice, horizon, grid, Service duration, or
 * Appointments.
 */
class WorkingTimeCalculatorTests {
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final UUID BUSINESS = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant STORED = Instant.parse("2026-01-01T00:00:00Z");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private static LocalPeriod period(String start, String end) {
        return new LocalPeriod(LocalTime.parse(start), LocalTime.parse(end));
    }

    private static Instant at(ZoneId zone, LocalDate date, String time) {
        return LocalDateTime.of(date, LocalTime.parse(time)).atZone(zone).toInstant();
    }

    private static StaffInput everyDay(UUID id, boolean active, LocalPeriod... periods) {
        Map<DayOfWeek, List<LocalPeriod>> byDay = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            byDay.put(day, List.of(periods));
        }
        return new StaffInput(id, "Служител", active, byDay);
    }

    private static ScheduleException stored(ScheduleExceptionContent content) {
        return new ScheduleException(UUID.randomUUID(), BUSINESS, content, 0, STORED, STORED);
    }

    private static StaffWorkingTime single(
            StaffInput staff, LocalDate first, LocalDate last, ScheduleException... exceptions) {
        return WorkingTimeCalculator.calculate(SOFIA, first, last, List.of(staff), List.of(exceptions)).get(0);
    }

    @Test
    void aRecurringDayIsTheItsPeriodsAsInstantIntervalsInOrder() {
        StaffWorkingTime time = single(
                everyDay(STAFF, true, period("13:00", "17:00"), period("09:00", "12:00")), MONDAY, MONDAY);

        assertThat(time.workingIntervals()).containsExactly(
                new Interval(at(SOFIA, MONDAY, "09:00"), at(SOFIA, MONDAY, "12:00")),
                new Interval(at(SOFIA, MONDAY, "13:00"), at(SOFIA, MONDAY, "17:00")));
        assertThat(time.closures()).isEmpty();
        assertThat(time.active()).isTrue();
    }

    @Test
    void anOverrideReplacesTheRecurringPeriodsAndAnEmptyOverrideMeansNoWorkingTime() {
        StaffInput staff = everyDay(STAFF, true, period("09:00", "18:00"));

        assertThat(single(staff, MONDAY, MONDAY, stored(ScheduleExceptionContent.workingDayOverride(
                STAFF, MONDAY, List.of(period("10:00", "11:00"))))).workingIntervals())
                .containsExactly(new Interval(at(SOFIA, MONDAY, "10:00"), at(SOFIA, MONDAY, "11:00")));
        StaffWorkingTime off = single(staff, MONDAY, MONDAY, stored(
                ScheduleExceptionContent.workingDayOverride(STAFF, MONDAY, List.of())));
        assertThat(off.workingIntervals()).isEmpty();
        // An empty override is not a closure: no marker, because no full-day exception covers it.
        assertThat(off.closures()).isEmpty();
    }

    @Test
    void additionalPeriodsAugmentTheBase() {
        StaffInput staff = everyDay(STAFF, true, period("09:00", "12:00"));

        StaffWorkingTime time = single(staff, MONDAY, MONDAY, stored(
                ScheduleExceptionContent.additionalWorkingPeriods(
                        STAFF, MONDAY, List.of(period("18:00", "20:00")))));

        assertThat(time.workingIntervals()).containsExactly(
                new Interval(at(SOFIA, MONDAY, "09:00"), at(SOFIA, MONDAY, "12:00")),
                new Interval(at(SOFIA, MONDAY, "18:00"), at(SOFIA, MONDAY, "20:00")));
    }

    @Test
    void partialExceptionsAreSubtractedAndLeaveNoMarker() {
        StaffInput staff = everyDay(STAFF, true, period("09:00", "18:00"));

        StaffWorkingTime time = single(staff, MONDAY, MONDAY,
                stored(ScheduleExceptionContent.businessClosurePartial(MONDAY, List.of(period("12:00", "13:00")))),
                stored(ScheduleExceptionContent.staffTimeOffPartial(STAFF, MONDAY, List.of(period("15:00", "16:00")))));

        assertThat(time.workingIntervals()).containsExactly(
                new Interval(at(SOFIA, MONDAY, "09:00"), at(SOFIA, MONDAY, "12:00")),
                new Interval(at(SOFIA, MONDAY, "13:00"), at(SOFIA, MONDAY, "15:00")),
                new Interval(at(SOFIA, MONDAY, "16:00"), at(SOFIA, MONDAY, "18:00")));
        assertThat(time.closures()).isEmpty();
    }

    @Test
    void aPartialBlockAtTheEdgesOfAPeriodAndOneCoveringAllOfItBehave() {
        StaffInput staff = everyDay(STAFF, true, period("09:00", "12:00"));

        assertThat(single(staff, MONDAY, MONDAY, stored(ScheduleExceptionContent.staffTimeOffPartial(
                STAFF, MONDAY, List.of(period("08:00", "09:30"))))).workingIntervals())
                .containsExactly(new Interval(at(SOFIA, MONDAY, "09:30"), at(SOFIA, MONDAY, "12:00")));
        assertThat(single(staff, MONDAY, MONDAY, stored(ScheduleExceptionContent.staffTimeOffPartial(
                STAFF, MONDAY, List.of(period("08:00", "13:00"))))).workingIntervals()).isEmpty();
    }

    @Test
    void theClosureMarkerAppearsExactlyForFullDayExceptionsAndBusinessClosureWins() {
        LocalDate tuesday = MONDAY.plusDays(1);
        LocalDate wednesday = MONDAY.plusDays(2);
        StaffInput staff = everyDay(STAFF, true, period("09:00", "18:00"));

        StaffWorkingTime time = single(staff, MONDAY, wednesday,
                stored(ScheduleExceptionContent.businessClosureDays(MONDAY, MONDAY)),
                stored(ScheduleExceptionContent.staffTimeOffDays(STAFF, MONDAY, tuesday)));

        assertThat(time.closures()).containsExactly(
                new Closure(MONDAY, ClosureKind.BUSINESS_CLOSURE),
                new Closure(tuesday, ClosureKind.STAFF_TIME_OFF));
        assertThat(time.workingIntervals()).containsExactly(
                new Interval(at(SOFIA, wednesday, "09:00"), at(SOFIA, wednesday, "18:00")));
    }

    @Test
    void aFullDayExceptionOverridesAnAdditionalPeriodAndAnOverrideOfTheSameDate() {
        StaffInput staff = everyDay(STAFF, true, period("09:00", "18:00"));

        StaffWorkingTime time = single(staff, MONDAY, MONDAY,
                stored(ScheduleExceptionContent.staffTimeOffDays(STAFF, MONDAY, MONDAY)),
                stored(ScheduleExceptionContent.additionalWorkingPeriods(
                        STAFF, MONDAY, List.of(period("19:00", "20:00")))));

        assertThat(time.workingIntervals()).isEmpty();
        assertThat(time.closures()).containsExactly(new Closure(MONDAY, ClosureKind.STAFF_TIME_OFF));
    }

    @Test
    void anotherStaffMembersTimeOffNeverAffectsThisOneButABusinessClosureDoesAffectEveryone() {
        List<StaffWorkingTime> times = WorkingTimeCalculator.calculate(
                SOFIA, MONDAY, MONDAY,
                List.of(everyDay(STAFF, true, period("09:00", "10:00")), everyDay(OTHER, true, period("09:00", "10:00"))),
                List.of(stored(ScheduleExceptionContent.staffTimeOffDays(OTHER, MONDAY, MONDAY))));

        assertThat(times.get(0).closures()).isEmpty();
        assertThat(times.get(0).workingIntervals()).hasSize(1);
        assertThat(times.get(1).closures()).containsExactly(new Closure(MONDAY, ClosureKind.STAFF_TIME_OFF));
        assertThat(times.get(1).workingIntervals()).isEmpty();
    }

    @Test
    void anInactiveStaffMemberHasNeitherIntervalsNorMarkers() {
        StaffWorkingTime time = single(
                everyDay(STAFF, false, period("09:00", "18:00")), MONDAY, MONDAY.plusDays(1),
                stored(ScheduleExceptionContent.businessClosureDays(MONDAY, MONDAY)));

        assertThat(time.active()).isFalse();
        assertThat(time.workingIntervals()).isEmpty();
        assertThat(time.closures()).isEmpty();
    }

    @Test
    void theFallBackDayHasTwentyFiveHoursAndARepeatedHourCountsTwice() {
        LocalDate fallBack = LocalDate.of(2026, 10, 25);
        DayBounds day = WorkingTimeCalculator.days(SOFIA, fallBack, fallBack).get(0);
        StaffWorkingTime time = single(
                everyDay(STAFF, true, period("02:00", "06:00")), fallBack, fallBack);

        assertThat(Duration.between(day.start(), day.end())).isEqualTo(Duration.ofHours(25));
        // 02:00 to 06:00 on the wall clock is five elapsed hours: 23:00Z to 04:00Z.
        assertThat(time.workingIntervals()).containsExactly(
                new Interval(Instant.parse("2026-10-24T23:00:00Z"), Instant.parse("2026-10-25T04:00:00Z")));
    }

    @Test
    void theSpringForwardDayHasTwentyThreeHoursAndAGapContributesNoTime() {
        LocalDate springForward = LocalDate.of(2027, 3, 28);
        DayBounds day = WorkingTimeCalculator.days(SOFIA, springForward, springForward).get(0);
        StaffWorkingTime time = single(
                everyDay(STAFF, true, period("02:00", "06:00")), springForward, springForward);

        assertThat(Duration.between(day.start(), day.end())).isEqualTo(Duration.ofHours(23));
        // 02:00 to 06:00 on the wall clock is three elapsed hours: 00:00Z to 03:00Z.
        assertThat(time.workingIntervals()).containsExactly(
                new Interval(Instant.parse("2027-03-28T00:00:00Z"), Instant.parse("2027-03-28T03:00:00Z")));
    }

    @Test
    void aPartialBlockInsideTheRepeatedHourBlocksBothOccurrences() {
        LocalDate fallBack = LocalDate.of(2026, 10, 25);

        StaffWorkingTime time = single(
                everyDay(STAFF, true, period("02:00", "06:00")), fallBack, fallBack,
                stored(ScheduleExceptionContent.staffTimeOffPartial(
                        STAFF, fallBack, List.of(period("03:00", "04:00")))));

        // The wall-clock hour 03:00-04:00 happens twice (00:00Z-01:00Z and 01:00Z-02:00Z).
        assertThat(time.workingIntervals()).containsExactly(
                new Interval(Instant.parse("2026-10-24T23:00:00Z"), Instant.parse("2026-10-25T00:00:00Z")),
                new Interval(Instant.parse("2026-10-25T02:00:00Z"), Instant.parse("2026-10-25T04:00:00Z")));
    }

    @Test
    void resultsFollowTheInputOrderAndTheWindowIsInclusive() {
        List<StaffWorkingTime> times = WorkingTimeCalculator.calculate(
                SOFIA, MONDAY, MONDAY.plusDays(2),
                List.of(everyDay(OTHER, true, period("09:00", "10:00")), everyDay(STAFF, true, period("09:00", "10:00"))),
                List.of());

        assertThat(times).extracting(StaffWorkingTime::id).containsExactly(OTHER, STAFF);
        assertThat(times.get(0).workingIntervals()).hasSize(3);
        assertThat(WorkingTimeCalculator.days(SOFIA, MONDAY, MONDAY.plusDays(2))).hasSize(3);
    }

    @Test
    void toStringIsRedacted() {
        assertThat(everyDay(STAFF, true).toString()).isEqualTo("StaffInput[redacted]");
        assertThat(single(everyDay(STAFF, true), MONDAY, MONDAY).toString()).isEqualTo("StaffWorkingTime[redacted]");
    }
}
