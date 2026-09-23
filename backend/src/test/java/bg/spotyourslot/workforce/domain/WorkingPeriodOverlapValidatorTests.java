package bg.spotyourslot.workforce.domain;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorkingPeriodOverlapValidatorTests {
    @Test
    void differentWeekdaysNeverOverlapEvenWithIdenticalTimes() {
        WorkingPeriod monday = period(DayOfWeek.MONDAY, "09:00", "18:00");
        WorkingPeriod tuesday = period(DayOfWeek.TUESDAY, "09:00", "18:00");

        assertThat(WorkingPeriodOverlapValidator.overlaps(monday, tuesday)).isFalse();
        assertThat(WorkingPeriodOverlapValidator.hasOverlap(List.of(monday, tuesday))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("overlappingPairs")
    void detectsOverlappingSameWeekdayPeriods(WorkingPeriod first, WorkingPeriod second) {
        assertThat(WorkingPeriodOverlapValidator.overlaps(first, second)).isTrue();
        assertThat(WorkingPeriodOverlapValidator.overlaps(second, first)).isTrue();
        assertThat(WorkingPeriodOverlapValidator.hasOverlap(List.of(first, second))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("nonOverlappingPairs")
    void acceptsAdjacentAndDisjointSameWeekdayPeriods(WorkingPeriod first, WorkingPeriod second) {
        assertThat(WorkingPeriodOverlapValidator.overlaps(first, second)).isFalse();
        assertThat(WorkingPeriodOverlapValidator.hasOverlap(List.of(first, second))).isFalse();
    }

    @Test
    void splitWorkingDayWithMultipleGapsHasNoOverlap() {
        List<WorkingPeriod> splitDay = List.of(
                period(DayOfWeek.WEDNESDAY, "09:00", "13:00"),
                period(DayOfWeek.WEDNESDAY, "14:00", "18:00"));

        assertThat(WorkingPeriodOverlapValidator.hasOverlap(splitDay)).isFalse();
    }

    private static Stream<Arguments> overlappingPairs() {
        return Stream.of(
                Arguments.of(
                        period(DayOfWeek.MONDAY, "09:00", "13:00"),
                        period(DayOfWeek.MONDAY, "12:59", "18:00")),
                Arguments.of(
                        period(DayOfWeek.MONDAY, "09:00", "18:00"),
                        period(DayOfWeek.MONDAY, "10:00", "11:00")),
                Arguments.of(
                        period(DayOfWeek.MONDAY, "09:00", "13:00"),
                        period(DayOfWeek.MONDAY, "09:00", "13:00")));
    }

    private static Stream<Arguments> nonOverlappingPairs() {
        return Stream.of(
                Arguments.of(
                        period(DayOfWeek.MONDAY, "09:00", "13:00"),
                        period(DayOfWeek.MONDAY, "13:00", "18:00")),
                Arguments.of(
                        period(DayOfWeek.MONDAY, "09:00", "10:00"),
                        period(DayOfWeek.MONDAY, "12:00", "13:00")));
    }

    private static WorkingPeriod period(DayOfWeek weekday, String start, String end) {
        return new WorkingPeriod(weekday, LocalTime.parse(start), LocalTime.parse(end));
    }
}
