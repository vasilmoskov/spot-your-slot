package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InputField;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaffWorkingScheduleInputValidatorTests {
    private static final StaffWorkingScheduleInputValidator VALIDATOR =
            new StaffWorkingScheduleInputValidator();

    @Test
    void rejectsNullStaffMemberId() {
        assertThatThrownBy(() -> VALIDATOR.validateStaffMemberId(null))
                .isInstanceOfSatisfying(InvalidInput.class,
                        failure -> assertThat(failure.field())
                                .isEqualTo(InputField.STAFF_MEMBER_ID));
    }

    @Test
    void acceptsStaffMemberId() {
        UUID staffMemberId = UUID.randomUUID();

        assertThat(VALIDATOR.validateStaffMemberId(staffMemberId)).isEqualTo(staffMemberId);
    }

    @Test
    void rejectsNullCommand() {
        assertInvalid(InputField.COMMAND, () -> VALIDATOR.validateReplacement(null));
    }

    @Test
    void rejectsNegativeExpectedVersion() {
        assertInvalid(InputField.EXPECTED_VERSION, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(List.of(), -1L)));
    }

    @Test
    void rejectsMissingExpectedVersion() {
        assertInvalid(InputField.EXPECTED_VERSION, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(List.of(), null)));
    }

    @Test
    void acceptsEmptyPeriodListAsClearingTheCompleteWeek() {
        ReplaceWorkingPeriodsCommand validated = VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(List.of(), 0L));

        assertThat(validated.periods()).isEmpty();
        assertThat(validated.expectedVersion()).isZero();
    }

    @Test
    void rejectsNullPeriodList() {
        assertInvalid(InputField.PERIODS,
                () -> VALIDATOR.validateReplacement(
                        new ReplaceWorkingPeriodsCommand(null, 0L)));
    }

    @Test
    void rejectsMoreThanOneHundredPeriods() {
        List<WorkingPeriod> tooMany = new ArrayList<>();
        for (int index = 0; index < StaffWorkingScheduleInputValidator.MAX_PERIODS + 1; index++) {
            tooMany.add(new WorkingPeriod(
                    DayOfWeek.MONDAY, LocalTime.of(0, index % 59), LocalTime.of(23, 59)));
        }

        assertInvalid(InputField.PERIODS, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(tooMany, 0L)));
    }

    @Test
    void rejectsNullPeriodElement() {
        List<WorkingPeriod> periods = new ArrayList<>();
        periods.add(null);

        assertInvalid(InputField.WEEKDAY, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(periods, 0L)));
    }

    @Test
    void rejectsNullWeekday() {
        List<WorkingPeriod> periods = List.of(
                new WorkingPeriod(null, LocalTime.of(9, 0), LocalTime.of(10, 0)));

        assertInvalid(InputField.WEEKDAY, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(periods, 0L)));
    }

    @Test
    void rejectsNullStartOrEndTime() {
        assertInvalid(InputField.START_TIME, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(
                                DayOfWeek.MONDAY, null, LocalTime.of(10, 0))),
                        0L)));
        assertInvalid(InputField.END_TIME, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(
                                DayOfWeek.MONDAY, LocalTime.of(9, 0), null)),
                        0L)));
    }

    @Test
    void rejectsSubMinutePrecision() {
        assertInvalid(InputField.START_TIME, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(
                                DayOfWeek.MONDAY,
                                LocalTime.of(9, 0, 30),
                                LocalTime.of(10, 0))),
                        0L)));
    }

    @Test
    void rejectsEqualAndReversedStartEndAsInvalidRange() {
        assertInvalid(InputField.PERIOD_RANGE, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(
                                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(9, 0))),
                        0L)));
        assertInvalid(InputField.PERIOD_RANGE, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(
                                DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(9, 0))),
                        0L)));
    }

    @Test
    void rejectsDuplicatePeriods() {
        WorkingPeriod period = new WorkingPeriod(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0));

        assertInvalid(InputField.DUPLICATE_PERIOD, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(List.of(period, period), 0L)));
    }

    @Test
    void rejectsOverlappingPeriodsOnTheSameWeekday() {
        List<WorkingPeriod> periods = List.of(
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(18, 0)));

        assertInvalid(InputField.OVERLAPPING_PERIOD, () -> VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(periods, 0L)));
    }

    @Test
    void acceptsAdjacentAndSplitWorkingDayPeriods() {
        List<WorkingPeriod> periods = List.of(
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(14, 0)),
                new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(18, 0)),
                new WorkingPeriod(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)));

        ReplaceWorkingPeriodsCommand validated = VALIDATOR.validateReplacement(
                new ReplaceWorkingPeriodsCommand(periods, 0L));

        assertThat(validated.periods()).containsExactlyElementsOf(periods);
    }

    private static void assertInvalid(InputField field, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(InvalidInput.class,
                        failure -> assertThat(failure.field()).isEqualTo(field));
    }
}
