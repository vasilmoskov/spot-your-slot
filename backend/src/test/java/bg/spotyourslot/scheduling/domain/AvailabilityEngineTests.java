package bg.spotyourslot.scheduling.domain;

import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.SOFIA;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_A;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_B;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.instant;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.localTimesOn;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.minutes;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.period;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.request;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.staff;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.times;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityEngineTests {
    /** Monday, well before any test period, in the summer offset (+03:00). */
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);
    private static final Instant EARLY_MONDAY = instant(SOFIA, "2026-06-01T05:00:00");

    private static List<AvailableSlot> calculate(Duration duration, StaffAvailabilityInput... staff) {
        return AvailabilityEngine.calculate(request(SOFIA, duration, EARLY_MONDAY, staff));
    }

    @Test
    void ordinaryWeekdayProducesGridAlignedSlotsWhoseServiceFitsTheWorkingPeriod() {
        StaffAvailabilityInput monday = staff(STAFF_A)
                .recurring(DayOfWeek.MONDAY, period("09:00", "12:00"))
                .build();

        List<AvailableSlot> slots = calculate(minutes(60), monday);

        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times(
                "09:00", "09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45", "11:00"));
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.localStart().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(Duration.between(slot.start(), slot.end())).isEqualTo(minutes(60));
        });
    }

    @Test
    void noWorkingPeriodsProduceNoSlots() {
        assertThat(calculate(minutes(30), staff(STAFF_A).build())).isEmpty();
        assertThat(AvailabilityEngine.calculate(request(SOFIA, minutes(30), EARLY_MONDAY))).isEmpty();
    }

    @Test
    void nonWorkingWeekdaysProduceNoSlots() {
        StaffAvailabilityInput tuesdayOnly = staff(STAFF_A)
                .recurring(DayOfWeek.TUESDAY, period("09:00", "10:00"))
                .build();

        List<AvailableSlot> slots = calculate(minutes(30), tuesdayOnly);

        assertThat(localTimesOn(slots, MONDAY)).isEmpty();
        assertThat(localTimesOn(slots, MONDAY.plusDays(1))).isNotEmpty();
    }

    @Test
    void serviceExactlyFillingThePeriodFitsButOneMinuteMoreDoesNot() {
        StaffAvailabilityInput exact = staff(STAFF_A)
                .override(MONDAY, period("09:00", "10:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), exact), MONDAY)).isEqualTo(times("09:00"));
        assertThat(localTimesOn(calculate(minutes(61), exact), MONDAY)).isEmpty();
    }

    @Test
    void periodOneMinuteShorterThanTheServiceProducesNoSlot() {
        StaffAvailabilityInput shortPeriod = staff(STAFF_A)
                .override(MONDAY, period("09:00", "09:59"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), shortPeriod), MONDAY)).isEmpty();
    }

    @Test
    void splitPeriodsAreNotBridged() {
        StaffAvailabilityInput split = staff(STAFF_A)
                .override(MONDAY, period("09:00", "10:00"), period("10:30", "11:30"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), split), MONDAY)).isEqualTo(times("09:00", "10:30"));
        assertThat(localTimesOn(calculate(minutes(45), split), MONDAY))
                .isEqualTo(times("09:00", "09:15", "10:30", "10:45"));
    }

    @Test
    void adjacentPeriodsAreNotBridged() {
        StaffAvailabilityInput adjacent = staff(STAFF_A)
                .override(MONDAY, period("09:00", "10:00"), period("10:00", "11:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(45), adjacent), MONDAY))
                .isEqualTo(times("09:00", "09:15", "10:00", "10:15"));
        assertThat(localTimesOn(calculate(minutes(60), adjacent), MONDAY)).isEqualTo(times("09:00", "10:00"));
    }

    @Test
    void overlappingWorkingInputsKeepTheirOwnBoundaries() {
        StaffAvailabilityInput overlapping = staff(STAFF_A)
                .override(MONDAY, period("09:00", "11:00"))
                .additional(MONDAY, period("10:00", "12:00"))
                .build();

        // 09:30-11:30 crosses the end of the base period and the start of the
        // additional period without fitting wholly inside either of them.
        assertThat(localTimesOn(calculate(minutes(120), overlapping), MONDAY))
                .isEqualTo(times("09:00", "10:00"));
    }

    @Test
    void periodStartingOffTheGridBeginsAtTheNextWallClockBoundary() {
        StaffAvailabilityInput offGrid = staff(STAFF_A)
                .override(MONDAY, period("09:10", "10:30"))
                .build();

        assertThat(localTimesOn(calculate(minutes(30), offGrid), MONDAY))
                .isEqualTo(times("09:15", "09:30", "09:45", "10:00"));
    }

    @Test
    void periodStartingOnTheGridIncludesItsStartAndAnOffGridEndLimitsTheLastSlot() {
        StaffAvailabilityInput onGrid = staff(STAFF_A)
                .override(MONDAY, period("09:15", "10:20"))
                .build();

        assertThat(localTimesOn(calculate(minutes(30), onGrid), MONDAY))
                .isEqualTo(times("09:15", "09:30", "09:45"));
    }

    @Test
    void gridIsAnchoredToTheWallClockNotToEachPeriod() {
        StaffAvailabilityInput two = staff(STAFF_A)
                .override(MONDAY, period("09:07", "10:00"), period("13:52", "15:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(30), two), MONDAY))
                .isEqualTo(times("09:15", "09:30", "14:00", "14:15", "14:30"));
    }

    @Test
    void slotsAreOrderedChronologicallyRegardlessOfInputOrder() {
        StaffAvailabilityInput unordered = staff(STAFF_A)
                .override(MONDAY, period("14:00", "15:00"), period("09:00", "10:00"))
                .additional(MONDAY.plusDays(1), period("12:00", "13:00"), period("08:00", "09:00"))
                .build();

        List<AvailableSlot> slots = calculate(minutes(60), unordered);

        assertThat(slots).extracting(AvailableSlot::start).isSorted();
        assertThat(slots).extracting(AvailableSlot::start).doesNotHaveDuplicates();
        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times("09:00", "14:00"));
    }

    @Test
    void repeatedCalculationsAndInputOrderChangesProduceIdenticalResults() {
        StaffAvailabilityInput first = staff(STAFF_A).everyDay(period("09:00", "12:00")).build();
        StaffAvailabilityInput second = staff(STAFF_B).everyDay(period("10:00", "13:00")).build();

        List<AvailableSlot> once = AvailabilityEngine.calculate(
                request(SOFIA, minutes(45), EARLY_MONDAY, first, second));
        List<AvailableSlot> twice = AvailabilityEngine.calculate(
                request(SOFIA, minutes(45), EARLY_MONDAY, first, second));
        List<AvailableSlot> reversed = AvailabilityEngine.calculate(
                request(SOFIA, minutes(45), EARLY_MONDAY, second, first));

        assertThat(twice).isEqualTo(once);
        assertThat(reversed).isEqualTo(once);
        assertThat(once.toString()).isEqualTo(twice.toString());
    }

    @Test
    void anyStaffMemberCombinesDuplicateStartsAndRetainsEveryEligibleStaffMember() {
        StaffAvailabilityInput a = staff(STAFF_A).override(MONDAY, period("09:00", "11:00")).build();
        StaffAvailabilityInput b = staff(STAFF_B).override(MONDAY, period("10:00", "12:00")).build();

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), EARLY_MONDAY, b, a));

        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times(
                "09:00", "09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45", "11:00"));
        assertThat(slots).extracting(AvailableSlot::start).doesNotHaveDuplicates();
        for (AvailableSlot slot : slots) {
            String local = slot.localStart().toLocalTime().toString();
            switch (local) {
                case "09:00", "09:15", "09:30", "09:45" -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_A);
                case "10:00" -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_A, STAFF_B);
                default -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_B);
            }
        }
    }

    @Test
    void specificStaffMemberRequestOnlyContainsThatStaffMember() {
        StaffAvailabilityInput a = staff(STAFF_A).override(MONDAY, period("09:00", "10:00")).build();

        List<AvailableSlot> slots = calculate(minutes(60), a);

        assertThat(slots).singleElement().satisfies(slot -> assertThat(slot.staffMemberIds())
                .containsExactly(STAFF_A));
    }

    @Test
    void overrideReplacesRecurringPeriodsForItsDateOnly() {
        LocalDate nextMonday = MONDAY.plusDays(7);
        StaffAvailabilityInput overridden = staff(STAFF_A)
                .recurring(DayOfWeek.MONDAY, period("09:00", "10:00"))
                .override(nextMonday, period("13:00", "14:00"))
                .build();

        List<AvailableSlot> slots = calculate(minutes(60), overridden);

        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times("09:00"));
        assertThat(localTimesOn(slots, nextMonday)).isEqualTo(times("13:00"));
    }

    @Test
    void emptyOverrideRemovesTheRecurringPeriodsForItsDate() {
        StaffAvailabilityInput dayOff = staff(STAFF_A)
                .recurring(DayOfWeek.MONDAY, period("09:00", "10:00"))
                .override(MONDAY)
                .build();

        assertThat(localTimesOn(calculate(minutes(60), dayOff), MONDAY)).isEmpty();
    }

    @Test
    void overrideCanCreateAWorkingDayOnANonWorkingWeekday() {
        LocalDate saturday = LocalDate.of(2026, 6, 6);
        StaffAvailabilityInput working = staff(STAFF_A)
                .recurring(DayOfWeek.MONDAY, period("09:00", "10:00"))
                .override(saturday, period("10:00", "11:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), working), saturday)).isEqualTo(times("10:00"));
    }

    @Test
    void additionalPeriodsAugmentTheRecurringBase() {
        StaffAvailabilityInput augmented = staff(STAFF_A)
                .recurring(DayOfWeek.MONDAY, period("09:00", "10:00"))
                .additional(MONDAY, period("12:00", "13:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), augmented), MONDAY)).isEqualTo(times("09:00", "12:00"));
    }

    @Test
    void additionalPeriodsAugmentAnOverrideAndNotTheReplacedRecurringPeriods() {
        StaffAvailabilityInput combined = staff(STAFF_A)
                .recurring(DayOfWeek.MONDAY, period("08:00", "09:00"))
                .override(MONDAY, period("13:00", "14:00"))
                .additional(MONDAY, period("16:00", "17:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), combined), MONDAY)).isEqualTo(times("13:00", "16:00"));
    }

    @Test
    void businessPartialClosureRemovesOnlyOverlappingSlotsAndAllowsAdjacency() {
        StaffAvailabilityInput working = staff(STAFF_A).override(MONDAY, period("09:00", "12:00")).build();
        List<LocalBlock> closures = List.of(new LocalBlock.PartialDay(MONDAY, period("09:30", "10:30")));

        List<AvailableSlot> slots = AvailabilityEngine.calculate(
                request(SOFIA, minutes(60), EARLY_MONDAY, closures, working));

        // 09:00-10:00 overlaps the closure; 10:30-11:30 starts exactly when it ends.
        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times("10:30", "10:45", "11:00"));
    }

    @Test
    void businessFullDayClosureRemovesEveryStaffMemberOnThatDateOnly() {
        StaffAvailabilityInput a = staff(STAFF_A).everyDay(period("09:00", "10:00")).build();
        StaffAvailabilityInput b = staff(STAFF_B).everyDay(period("09:00", "10:00")).build();
        List<LocalBlock> closures = List.of(LocalBlock.FullDays.singleDay(MONDAY.plusDays(1)));

        List<AvailableSlot> slots = AvailabilityEngine.calculate(
                request(SOFIA, minutes(60), EARLY_MONDAY, closures, a, b));

        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times("09:00"));
        assertThat(localTimesOn(slots, MONDAY.plusDays(1))).isEmpty();
        assertThat(localTimesOn(slots, MONDAY.plusDays(2))).isEqualTo(times("09:00"));
    }

    @Test
    void businessFullDayClosureRangeIsInclusive() {
        StaffAvailabilityInput a = staff(STAFF_A).everyDay(period("09:00", "10:00")).build();
        List<LocalBlock> closures = List.of(new LocalBlock.FullDays(MONDAY.plusDays(1), MONDAY.plusDays(3)));

        List<AvailableSlot> slots = AvailabilityEngine.calculate(
                request(SOFIA, minutes(60), EARLY_MONDAY, closures, a));

        assertThat(localTimesOn(slots, MONDAY)).isNotEmpty();
        assertThat(localTimesOn(slots, MONDAY.plusDays(1))).isEmpty();
        assertThat(localTimesOn(slots, MONDAY.plusDays(2))).isEmpty();
        assertThat(localTimesOn(slots, MONDAY.plusDays(3))).isEmpty();
        assertThat(localTimesOn(slots, MONDAY.plusDays(4))).isNotEmpty();
    }

    @Test
    void staffPartialTimeOffAffectsOnlyThatStaffMember() {
        StaffAvailabilityInput a = staff(STAFF_A)
                .override(MONDAY, period("09:00", "11:00"))
                .timeOff(new LocalBlock.PartialDay(MONDAY, period("09:00", "10:00")))
                .build();
        StaffAvailabilityInput b = staff(STAFF_B).override(MONDAY, period("09:00", "11:00")).build();

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), EARLY_MONDAY, a, b));

        for (AvailableSlot slot : slots) {
            String local = slot.localStart().toLocalTime().toString();
            if (local.equals("09:00") || local.equals("09:15") || local.equals("09:30")
                    || local.equals("09:45")) {
                assertThat(slot.staffMemberIds()).containsExactly(STAFF_B);
            } else {
                assertThat(slot.staffMemberIds()).containsExactly(STAFF_A, STAFF_B);
            }
        }
        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times(
                "09:00", "09:15", "09:30", "09:45", "10:00"));
    }

    @Test
    void staffFullDayTimeOffRemovesOnlyThatStaffMembersDateRange() {
        StaffAvailabilityInput a = staff(STAFF_A)
                .everyDay(period("09:00", "10:00"))
                .timeOff(new LocalBlock.FullDays(MONDAY, MONDAY.plusDays(1)))
                .build();
        StaffAvailabilityInput b = staff(STAFF_B).everyDay(period("09:00", "10:00")).build();

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), EARLY_MONDAY, a, b));

        AvailableSlot monday = slots.stream()
                .filter(slot -> slot.localStart().toLocalDate().equals(MONDAY)).findFirst().orElseThrow();
        AvailableSlot wednesday = slots.stream()
                .filter(slot -> slot.localStart().toLocalDate().equals(MONDAY.plusDays(2))).findFirst().orElseThrow();
        assertThat(monday.staffMemberIds()).containsExactly(STAFF_B);
        assertThat(wednesday.staffMemberIds()).containsExactly(STAFF_A, STAFF_B);
    }

    @Test
    void blockingIntervalsWinOverOverridesAndAdditionalPeriods() {
        StaffAvailabilityInput working = staff(STAFF_A)
                .override(MONDAY, period("09:00", "12:00"))
                .additional(MONDAY, period("13:00", "16:00"))
                .timeOff(new LocalBlock.PartialDay(MONDAY, period("10:00", "11:00")))
                .build();
        List<LocalBlock> closures = List.of(new LocalBlock.PartialDay(MONDAY, period("14:00", "15:00")));

        List<AvailableSlot> slots = AvailabilityEngine.calculate(
                request(SOFIA, minutes(60), EARLY_MONDAY, closures, working));

        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times(
                "09:00", "11:00", "13:00", "15:00"));
    }

    @Test
    void fullDayBlocksWinOverAnOverrideAndAdditionalPeriodsOnTheSameDate() {
        StaffAvailabilityInput working = staff(STAFF_A)
                .override(MONDAY, period("09:00", "12:00"))
                .additional(MONDAY, period("13:00", "16:00"))
                .timeOff(LocalBlock.FullDays.singleDay(MONDAY))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), working), MONDAY)).isEmpty();
    }

    @Test
    void slotsNeverOverlapBusyIntervalsButMayBeAdjacentToThem() {
        StaffAvailabilityInput working = staff(STAFF_A)
                .override(MONDAY, period("09:00", "13:00"))
                .busy(instant(SOFIA, "2026-06-01T10:00:00"), instant(SOFIA, "2026-06-01T11:00:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), working), MONDAY)).isEqualTo(times(
                "09:00", "11:00", "11:15", "11:30", "11:45", "12:00"));
    }

    @Test
    void anExceptionMayOverlapABusyIntervalAndTheBusyIntervalStillBlocks() {
        StaffAvailabilityInput working = staff(STAFF_A)
                .override(MONDAY, period("09:00", "13:00"))
                .timeOff(new LocalBlock.PartialDay(MONDAY, period("09:00", "10:30")))
                .busy(instant(SOFIA, "2026-06-01T10:00:00"), instant(SOFIA, "2026-06-01T11:00:00"))
                .build();

        assertThat(localTimesOn(calculate(minutes(60), working), MONDAY)).isEqualTo(times(
                "11:00", "11:15", "11:30", "11:45", "12:00"));
    }

    @Test
    void busyIntervalsOfOneStaffMemberDoNotAffectAnother() {
        StaffAvailabilityInput a = staff(STAFF_A)
                .override(MONDAY, period("09:00", "10:00"))
                .busy(instant(SOFIA, "2026-06-01T09:00:00"), instant(SOFIA, "2026-06-01T10:00:00"))
                .build();
        StaffAvailabilityInput b = staff(STAFF_B).override(MONDAY, period("09:00", "10:00")).build();

        List<AvailableSlot> slots = calculate(minutes(60), a, b);

        assertThat(slots).singleElement().satisfies(slot -> assertThat(slot.staffMemberIds())
                .containsExactly(STAFF_B));
    }

    @Test
    void minimumNoticeIsInclusiveAtItsExactBoundary() {
        StaffAvailabilityInput working = staff(STAFF_A).override(MONDAY, period("09:00", "11:00")).build();
        Instant now = instant(SOFIA, "2026-06-01T07:00:00");

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), now, working));

        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times("09:00", "09:15", "09:30", "09:45", "10:00"));
    }

    @Test
    void aSlotOneInstantBeforeTheMinimumNoticeBoundaryIsExcluded() {
        StaffAvailabilityInput working = staff(STAFF_A).override(MONDAY, period("09:00", "11:00")).build();
        Instant justAfterBoundary = instant(SOFIA, "2026-06-01T07:00:00").plusNanos(1);

        List<AvailableSlot> slots = AvailabilityEngine.calculate(
                request(SOFIA, minutes(60), justAfterBoundary, working));

        // 09:00 is one nanosecond earlier than now + 2 hours.
        assertThat(localTimesOn(slots, MONDAY)).isEqualTo(times("09:15", "09:30", "09:45", "10:00"));
    }

    @Test
    void horizonIncludesTodayAndTodayPlus29AndExcludesTodayPlus30() {
        StaffAvailabilityInput daily = staff(STAFF_A).everyDay(period("09:00", "10:00")).build();

        List<AvailableSlot> slots = calculate(minutes(60), daily);

        assertThat(slots).hasSize(AvailabilityPolicy.HORIZON_DAYS);
        assertThat(slots.getFirst().localStart()).isEqualTo(LocalDateTime.of(2026, 6, 1, 9, 0));
        assertThat(slots.getLast().localStart()).isEqualTo(LocalDateTime.of(2026, 6, 30, 9, 0));
        assertThat(localTimesOn(slots, LocalDate.of(2026, 7, 1))).isEmpty();
    }

    @Test
    void horizonIsCountedInBusinessLocalDatesNotUtcDates() {
        StaffAvailabilityInput daily = staff(STAFF_A).everyDay(period("09:00", "10:00")).build();
        // 22:30 UTC on 31 May is 01:30 on 1 June in Sofia (+03:00).
        Instant now = Instant.parse("2026-05-31T22:30:00Z");

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), now, daily));

        assertThat(slots.getFirst().localStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(slots.getLast().localStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void horizonCrossesMonthAndYearBoundaries() {
        StaffAvailabilityInput daily = staff(STAFF_A).everyDay(period("09:00", "10:00")).build();
        Instant now = instant(SOFIA, "2026-12-25T00:00:00");

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), now, daily));

        assertThat(slots).hasSize(30);
        assertThat(slots.get(6).localStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(slots.get(7).localStart().toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(slots.getLast().localStart().toLocalDate()).isEqualTo(LocalDate.of(2027, 1, 23));
    }

    @Test
    void horizonCrossesAMonthBoundary() {
        StaffAvailabilityInput daily = staff(STAFF_A).everyDay(period("09:00", "10:00")).build();
        Instant now = instant(SOFIA, "2026-06-20T00:00:00");

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), now, daily));

        assertThat(slots.get(10).localStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(slots.get(11).localStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(slots.getLast().localStart().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 19));
    }

    @Test
    void leapDayIsAnOrdinaryBookableDate() {
        StaffAvailabilityInput daily = staff(STAFF_A).everyDay(period("09:00", "10:00")).build();
        Instant now = instant(SOFIA, "2028-02-27T00:00:00");

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(60), now, daily));

        assertThat(slots.subList(0, 4)).extracting(slot -> slot.localStart().toLocalDate()).containsExactly(
                LocalDate.of(2028, 2, 27),
                LocalDate.of(2028, 2, 28),
                LocalDate.of(2028, 2, 29),
                LocalDate.of(2028, 3, 1));
    }

    @Test
    void leapDayOverrideAndClosureApplyToThatLocalDate() {
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        StaffAvailabilityInput working = staff(STAFF_A).override(leapDay, period("09:00", "10:00")).build();
        Instant now = instant(SOFIA, "2028-02-27T00:00:00");

        assertThat(AvailabilityEngine.calculate(request(SOFIA, minutes(60), now, working)))
                .extracting(AvailableSlot::localStart)
                .containsExactly(leapDay.atTime(9, 0));
        assertThat(AvailabilityEngine.calculate(request(
                SOFIA, minutes(60), now, List.of(LocalBlock.FullDays.singleDay(leapDay)), working))).isEmpty();
    }

    @Test
    void businessTimezoneDeterminesTheInstantsIndependentlyOfUtc() {
        StaffAvailabilityInput working = staff(STAFF_A).override(MONDAY, period("09:00", "10:00")).build();
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant now = instant(newYork, "2026-06-01T05:00:00");

        List<AvailableSlot> sofia = AvailabilityEngine.calculate(
                request(SOFIA, minutes(60), EARLY_MONDAY, working));
        List<AvailableSlot> york = AvailabilityEngine.calculate(request(newYork, minutes(60), now, working));

        assertThat(sofia).singleElement().satisfies(slot -> {
            assertThat(slot.start()).isEqualTo(Instant.parse("2026-06-01T06:00:00Z"));
            assertThat(slot.startOffset().getTotalSeconds()).isEqualTo(3 * 3600);
        });
        assertThat(york).singleElement().satisfies(slot -> {
            assertThat(slot.start()).isEqualTo(Instant.parse("2026-06-01T13:00:00Z"));
            assertThat(slot.startOffset().getTotalSeconds()).isEqualTo(-4 * 3600);
        });
    }
}
