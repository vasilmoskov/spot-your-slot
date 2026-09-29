package bg.spotyourslot.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InputField;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.application.ScheduleExceptionInputValidator.ValidatedReplacement;
import bg.spotyourslot.scheduling.application.ScheduleExceptionInputValidator.Window;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScheduleExceptionInputValidatorTests {
    private static final UUID STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);

    private final ScheduleExceptionInputValidator validator = new ScheduleExceptionInputValidator();

    @Test
    void limitsAreTheApprovedFixedValues() {
        assertThat(ScheduleExceptionInputValidator.MIN_DATE).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(ScheduleExceptionInputValidator.MAX_DATE).isEqualTo(LocalDate.of(2100, 12, 31));
        assertThat(ScheduleExceptionInputValidator.MAX_FULL_DAY_SPAN_DATES).isEqualTo(366);
        assertThat(ScheduleExceptionInputValidator.MAX_PERIODS).isEqualTo(24);
        assertThat(ScheduleExceptionInputValidator.MAX_WINDOW_DATES).isEqualTo(93);
    }

    @Test
    void acceptsEveryKindInItsCanonicalShapeAndSortsPeriods() {
        ScheduleExceptionContent closure = validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE.plusDays(2), true, List.of()));
        assertThat(closure.allDay()).isTrue();
        assertThat(closure.lastDate()).isEqualTo(DATE.plusDays(2));

        ScheduleExceptionContent partialClosure = validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE, false, List.of(period(13, 0, 14, 0))));
        assertThat(partialClosure.periods()).hasSize(1);

        ScheduleExceptionContent timeOff = validator.validateCreate(create(
                ScheduleExceptionKind.STAFF_TIME_OFF, STAFF_MEMBER_ID, DATE, DATE.plusDays(1), true, List.of()));
        assertThat(timeOff.staffMemberId()).isEqualTo(STAFF_MEMBER_ID);

        ScheduleExceptionContent additional = validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                List.of(period(14, 0, 16, 0), period(9, 0, 12, 0))));
        assertThat(additional.periods()).containsExactly(
                new LocalPeriod(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                new LocalPeriod(LocalTime.of(14, 0), LocalTime.of(16, 0)));
    }

    @Test
    void emptyWorkingDayOverrideIsValidAndMeansNoRecurringPeriods() {
        ScheduleExceptionContent override = validator.validateCreate(create(
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE, STAFF_MEMBER_ID, DATE, DATE, false, List.of()));

        assertThat(override.periods()).isEmpty();
        assertThat(override.allDay()).isFalse();
    }

    @Test
    void adjacentPeriodsStaySeparate() {
        ScheduleExceptionContent content = validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                List.of(period(9, 0, 12, 0), period(12, 0, 13, 0))));

        assertThat(content.periods()).hasSize(2);
    }

    @Test
    void rejectsDuplicateAndOverlappingPeriodsOnPeriodsField() {
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                List.of(period(9, 0, 12, 0), period(9, 0, 12, 0)))));
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                List.of(period(9, 0, 12, 0), period(11, 59, 13, 0)))));
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                List.of(period(9, 0, 17, 0), period(10, 0, 11, 0)))));
    }

    @Test
    void rejectsMalformedPeriods() {
        assertPeriodsRejected(List.of(period(12, 0, 12, 0)));
        assertPeriodsRejected(List.of(period(13, 0, 12, 0)));
        assertPeriodsRejected(List.of(new ExceptionPeriod(LocalTime.of(9, 0, 30), LocalTime.of(10, 0))));
        assertPeriodsRejected(List.of(new ExceptionPeriod(LocalTime.of(9, 0), LocalTime.of(10, 0, 0, 1))));
        assertPeriodsRejected(List.of(new ExceptionPeriod(null, LocalTime.of(10, 0))));
        assertPeriodsRejected(List.of(new ExceptionPeriod(LocalTime.of(9, 0), null)));
        List<ExceptionPeriod> withNull = new ArrayList<>();
        withNull.add(null);
        assertPeriodsRejected(withNull);
    }

    @Test
    void periodCountBoundaryAcceptsTwentyFourAndRejectsTwentyFive() {
        assertThat(validator.validateCreate(create(
                        ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                        STAFF_MEMBER_ID,
                        DATE,
                        DATE,
                        false,
                        hourlyPeriods(24)))
                .periods()).hasSize(24);

        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                twentyFivePeriods())));
    }

    @Test
    void dateBoundsAcceptTheExactLimitsAndRejectTheFirstOutsideValue() {
        ScheduleExceptionContent earliest = validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                null,
                LocalDate.of(2000, 1, 1),
                LocalDate.of(2000, 1, 1),
                true,
                List.of()));
        ScheduleExceptionContent latest = validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                null,
                LocalDate.of(2100, 12, 31),
                LocalDate.of(2100, 12, 31),
                true,
                List.of()));
        assertThat(earliest.firstDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(latest.lastDate()).isEqualTo(LocalDate.of(2100, 12, 31));

        assertField(InputField.FIRST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                null,
                LocalDate.of(1999, 12, 31),
                LocalDate.of(2000, 1, 1),
                true,
                List.of())));
        assertField(InputField.LAST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                null,
                LocalDate.of(2100, 12, 31),
                LocalDate.of(2101, 1, 1),
                true,
                List.of())));
        assertField(InputField.FIRST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                null,
                LocalDate.of(2101, 1, 1),
                LocalDate.of(2101, 1, 1),
                true,
                List.of())));
    }

    @Test
    void fullDaySpanAcceptsThreeHundredSixtySixDatesAndRejectsThreeHundredSixtySeven() {
        LocalDate first = LocalDate.of(2026, 1, 1);
        ScheduleExceptionContent accepted = validator.validateCreate(create(
                ScheduleExceptionKind.STAFF_TIME_OFF,
                STAFF_MEMBER_ID,
                first,
                first.plusDays(365),
                true,
                List.of()));
        assertThat(accepted.lastDate()).isEqualTo(first.plusDays(365));

        assertField(InputField.LAST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.STAFF_TIME_OFF,
                STAFF_MEMBER_ID,
                first,
                first.plusDays(366),
                true,
                List.of())));
    }

    @Test
    void rejectsMissingAndInconsistentDateFields() {
        assertField(InputField.FIRST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, null, DATE, true, List.of())));
        assertField(InputField.LAST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, null, true, List.of())));
        assertField(InputField.LAST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE.minusDays(1), true, List.of())));
        assertField(InputField.ALL_DAY, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE, null, List.of())));
    }

    @Test
    void periodBasedExceptionsApplyToExactlyOneDate() {
        assertField(InputField.LAST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                null,
                DATE,
                DATE.plusDays(1),
                false,
                List.of(period(9, 0, 10, 0)))));
        assertField(InputField.LAST_DATE, () -> validator.validateCreate(create(
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE,
                STAFF_MEMBER_ID,
                DATE,
                DATE.plusDays(1),
                false,
                List.of())));
    }

    @Test
    void fullDayShapeHasNoPeriodsAndWorkingKindsCannotBeFullDay() {
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                null,
                DATE,
                DATE,
                true,
                List.of(period(9, 0, 10, 0)))));
        assertField(InputField.ALL_DAY, () -> validator.validateCreate(create(
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                true,
                List.of())));
        assertField(InputField.ALL_DAY, () -> validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                true,
                List.of())));
    }

    @Test
    void minimumPeriodRulesPerKind() {
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE, false, List.of())));
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.STAFF_TIME_OFF, STAFF_MEMBER_ID, DATE, DATE, false, List.of())));
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                List.of())));
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE, true, null)));
    }

    @Test
    void kindAndScopeMustAgreeOnCreate() {
        assertField(InputField.KIND, () -> validator.validateCreate(create(
                null, null, DATE, DATE, true, List.of())));
        assertField(InputField.STAFF_MEMBER_ID, () -> validator.validateCreate(create(
                ScheduleExceptionKind.BUSINESS_CLOSURE,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                true,
                List.of())));
        for (ScheduleExceptionKind kind : List.of(
                ScheduleExceptionKind.STAFF_TIME_OFF,
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE,
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS)) {
            assertField(InputField.STAFF_MEMBER_ID, () -> validator.validateCreate(create(
                    kind, null, DATE, DATE, false, List.of(period(9, 0, 10, 0)))));
        }
        assertField(InputField.COMMAND, () -> validator.validateCreate(null));
    }

    @Test
    void replacementValidatesExpectedVersionAndSharedShapeWithoutKind() {
        ValidatedReplacement replacement = validator.validateReplacement(
                new ReplaceScheduleExceptionCommand(
                        0L, DATE, DATE, false, List.of(period(9, 0, 10, 0))));

        assertThat(replacement.expectedVersion()).isZero();
        assertThat(replacement.shape().periods()).hasSize(1);
        assertField(InputField.EXPECTED_VERSION, () -> validator.validateReplacement(
                new ReplaceScheduleExceptionCommand(-1L, DATE, DATE, true, List.of())));
        assertField(InputField.EXPECTED_VERSION, () -> validator.validateReplacement(
                new ReplaceScheduleExceptionCommand(null, DATE, DATE, true, List.of())));
        assertField(InputField.COMMAND, () -> validator.validateReplacement(null));
        assertField(InputField.FIRST_DATE, () -> validator.validateReplacement(
                new ReplaceScheduleExceptionCommand(1L, null, DATE, true, List.of())));
    }

    @Test
    void kindSpecificRulesAreAppliedToTheStoredKindOnReplace() {
        ValidatedReplacement fullDay = validator.validateReplacement(
                new ReplaceScheduleExceptionCommand(1L, DATE, DATE, true, List.of()));

        assertField(InputField.ALL_DAY, () -> validator.content(
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE, STAFF_MEMBER_ID, fullDay.shape()));
        assertThat(validator.content(
                        ScheduleExceptionKind.STAFF_TIME_OFF, STAFF_MEMBER_ID, fullDay.shape())
                .kind()).isEqualTo(ScheduleExceptionKind.STAFF_TIME_OFF);

        ValidatedReplacement empty = validator.validateReplacement(
                new ReplaceScheduleExceptionCommand(1L, DATE, DATE, false, List.of()));
        assertField(InputField.PERIODS, () -> validator.content(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS, STAFF_MEMBER_ID, empty.shape()));
        assertThat(validator.content(
                        ScheduleExceptionKind.WORKING_DAY_OVERRIDE, STAFF_MEMBER_ID, empty.shape())
                .periods()).isEmpty();
    }

    @Test
    void expectedVersionMustBeNonNegative() {
        assertThat(validator.validateExpectedVersion(0L)).isZero();
        assertThat(validator.validateExpectedVersion(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertField(InputField.EXPECTED_VERSION, () -> validator.validateExpectedVersion(-1L));
        assertField(InputField.EXPECTED_VERSION, () -> validator.validateExpectedVersion(null));
    }

    @Test
    void listWindowAcceptsNinetyThreeDatesAndRejectsNinetyFour() {
        LocalDate from = LocalDate.of(2026, 1, 1);

        assertThat(validator.validateWindow(from, from)).isEqualTo(new Window(from, from));
        assertThat(validator.validateWindow(from, from.plusDays(92)).to())
                .isEqualTo(from.plusDays(92));
        assertField(InputField.WINDOW, () -> validator.validateWindow(from, from.plusDays(93)));
    }

    @Test
    void listWindowRequiresOrderedBoundedDates() {
        LocalDate from = LocalDate.of(2026, 1, 1);

        assertField(InputField.WINDOW, () -> validator.validateWindow(null, from));
        assertField(InputField.WINDOW, () -> validator.validateWindow(from, null));
        assertField(InputField.WINDOW, () -> validator.validateWindow(from, from.minusDays(1)));
        assertThat(validator.validateWindow(
                        LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 1)).from())
                .isEqualTo(LocalDate.of(2000, 1, 1));
        assertField(InputField.WINDOW, () -> validator.validateWindow(
                LocalDate.of(1999, 12, 31), LocalDate.of(2000, 1, 1)));
        assertThat(validator.validateWindow(
                        LocalDate.of(2100, 12, 31), LocalDate.of(2100, 12, 31)).to())
                .isEqualTo(LocalDate.of(2100, 12, 31));
        assertField(InputField.WINDOW, () -> validator.validateWindow(
                LocalDate.of(2100, 12, 31), LocalDate.of(2101, 1, 1)));
    }

    private void assertPeriodsRejected(List<ExceptionPeriod> periods) {
        assertField(InputField.PERIODS, () -> validator.validateCreate(create(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                STAFF_MEMBER_ID,
                DATE,
                DATE,
                false,
                periods)));
    }

    private void assertField(InputField field, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(InvalidInput.class, failure ->
                        assertThat(failure.field()).isEqualTo(field));
    }

    private static CreateScheduleExceptionCommand create(
            ScheduleExceptionKind kind,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            Boolean allDay,
            List<ExceptionPeriod> periods) {
        return new CreateScheduleExceptionCommand(
                kind, staffMemberId, firstDate, lastDate, allDay, periods);
    }

    private static ExceptionPeriod period(int startHour, int startMinute, int endHour, int endMinute) {
        return new ExceptionPeriod(
                LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }

    private static List<ExceptionPeriod> hourlyPeriods(int count) {
        return IntStream.range(0, count)
                .mapToObj(hour -> period(hour, 0, hour, 30))
                .toList();
    }

    private static List<ExceptionPeriod> twentyFivePeriods() {
        List<ExceptionPeriod> periods = new ArrayList<>(hourlyPeriods(24));
        periods.add(period(23, 45, 23, 59));
        return periods;
    }
}
