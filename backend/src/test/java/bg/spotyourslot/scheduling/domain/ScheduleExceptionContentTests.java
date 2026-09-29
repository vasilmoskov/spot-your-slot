package bg.spotyourslot.scheduling.domain;

import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_A;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.period;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleExceptionContentTests {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate NEXT_DAY = DAY.plusDays(1);

    @Test
    void fullDayClosureAndTimeOffAcceptSingleDatesAndInclusiveRanges() {
        ScheduleExceptionContent single = ScheduleExceptionContent.businessClosureDays(DAY, DAY);
        ScheduleExceptionContent range =
                ScheduleExceptionContent.staffTimeOffDays(STAFF_A, DAY, NEXT_DAY);

        assertThat(single.allDay()).isTrue();
        assertThat(single.periods()).isEmpty();
        assertThat(single.staffMemberId()).isNull();
        assertThat(range.lastDate()).isEqualTo(NEXT_DAY);
        assertThat(range.staffMemberId()).isEqualTo(STAFF_A);
    }

    @Test
    void fullDayExceptionsRejectPeriodsAndReversedRanges() {
        assertThatThrownBy(() -> new ScheduleExceptionContent(
                        ScheduleExceptionKind.BUSINESS_CLOSURE,
                        null,
                        DAY,
                        DAY,
                        true,
                        List.of(period("09:00", "10:00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleExceptionContent.businessClosureDays(NEXT_DAY, DAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialClosuresAndTimeOffRequireOnePeriodOnOneDate() {
        assertThatThrownBy(() -> ScheduleExceptionContent.businessClosurePartial(DAY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleExceptionContent.staffTimeOffPartial(
                        STAFF_A, DAY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleExceptionContent(
                        ScheduleExceptionKind.STAFF_TIME_OFF,
                        STAFF_A,
                        DAY,
                        NEXT_DAY,
                        false,
                        List.of(period("09:00", "10:00"))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ScheduleExceptionContent.staffTimeOffPartial(
                                STAFF_A, DAY, List.of(period("09:00", "10:00")))
                        .periods())
                .hasSize(1);
    }

    @Test
    void workingKindsAreSingleDateAndNeverFullDay() {
        assertThatThrownBy(() -> new ScheduleExceptionContent(
                        ScheduleExceptionKind.WORKING_DAY_OVERRIDE,
                        STAFF_A,
                        DAY,
                        DAY,
                        true,
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleExceptionContent(
                        ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                        STAFF_A,
                        DAY,
                        NEXT_DAY,
                        false,
                        List.of(period("09:00", "10:00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyAndNonEmptyOverridesAreValid() {
        assertThat(ScheduleExceptionContent.workingDayOverride(STAFF_A, DAY, List.of()).periods())
                .isEmpty();
        assertThat(ScheduleExceptionContent.workingDayOverride(
                                STAFF_A,
                                DAY,
                                List.of(period("14:00", "18:00"), period("09:00", "12:00")))
                        .periods())
                .containsExactly(period("09:00", "12:00"), period("14:00", "18:00"));
    }

    @Test
    void additionalWorkingPeriodsRequireAtLeastOnePeriod() {
        assertThatThrownBy(() -> ScheduleExceptionContent.additionalWorkingPeriods(
                        STAFF_A, DAY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopeMustMatchKind() {
        assertThatThrownBy(() -> new ScheduleExceptionContent(
                        ScheduleExceptionKind.BUSINESS_CLOSURE, STAFF_A, DAY, DAY, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleExceptionContent(
                        ScheduleExceptionKind.STAFF_TIME_OFF, null, DAY, DAY, true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void periodsAreSortedDeterministicallyRegardlessOfInputOrder() {
        List<LocalPeriod> periods = List.of(
                period("16:00", "17:00"), period("09:00", "10:00"), period("12:00", "13:00"));

        ScheduleExceptionContent content =
                ScheduleExceptionContent.additionalWorkingPeriods(STAFF_A, DAY, periods);

        assertThat(content.periods())
                .containsExactly(
                        period("09:00", "10:00"), period("12:00", "13:00"), period("16:00", "17:00"));
    }

    @Test
    void duplicateAndOverlappingPeriodsAreRejectedIncludingContainment() {
        assertThatThrownBy(() -> ScheduleExceptionContent.staffTimeOffPartial(
                        STAFF_A, DAY, List.of(period("09:00", "10:00"), period("09:00", "10:00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleExceptionContent.staffTimeOffPartial(
                        STAFF_A, DAY, List.of(period("09:00", "11:00"), period("10:00", "12:00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleExceptionContent.staffTimeOffPartial(
                        STAFF_A, DAY, List.of(period("09:00", "17:00"), period("10:00", "11:00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjacentPeriodsAreAcceptedAndRetainedSeparately() {
        ScheduleExceptionContent content = ScheduleExceptionContent.workingDayOverride(
                STAFF_A, DAY, List.of(period("09:00", "12:00"), period("12:00", "15:00")));

        assertThat(content.periods())
                .containsExactly(period("09:00", "12:00"), period("12:00", "15:00"));
    }

    @Test
    void periodsAreDefensivelyCopied() {
        List<LocalPeriod> mutable = new java.util.ArrayList<>(List.of(period("09:00", "10:00")));

        ScheduleExceptionContent content =
                ScheduleExceptionContent.additionalWorkingPeriods(STAFF_A, DAY, mutable);
        mutable.clear();

        assertThat(content.periods()).hasSize(1);
        assertThatThrownBy(() -> content.periods().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
