package bg.spotyourslot.scheduling.domain;

import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.SOFIA;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_A;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_B;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.instant;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.localTimesOn;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.minutes;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.period;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.times;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduleExceptionInputsTests {
    private static final UUID BUSINESS = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant STORED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final LocalDate TUESDAY = LocalDate.of(2026, 6, 2);

    private static ScheduleException stored(ScheduleExceptionContent content) {
        return new ScheduleException(UUID.randomUUID(), BUSINESS, content, 0, STORED_AT, STORED_AT);
    }

    @Test
    void fullDayBlocksBecomeInclusiveRangesAndPartialPeriodsBecomeSeparateBlocks() {
        List<ScheduleException> exceptions = List.of(
                stored(ScheduleExceptionContent.businessClosureDays(
                        TUESDAY, TUESDAY.plusDays(2))),
                stored(ScheduleExceptionContent.businessClosurePartial(
                        TUESDAY.plusDays(5),
                        List.of(period("09:00", "10:00"), period("10:00", "11:00")))),
                stored(ScheduleExceptionContent.staffTimeOffDays(STAFF_A, TUESDAY, TUESDAY)));

        assertThat(ScheduleExceptionInputs.businessClosures(exceptions))
                .containsExactly(
                        new LocalBlock.FullDays(TUESDAY, TUESDAY.plusDays(2)),
                        new LocalBlock.PartialDay(TUESDAY.plusDays(5), period("09:00", "10:00")),
                        new LocalBlock.PartialDay(TUESDAY.plusDays(5), period("10:00", "11:00")));
        assertThat(ScheduleExceptionInputs.timeOff(STAFF_A, exceptions))
                .containsExactly(LocalBlock.FullDays.singleDay(TUESDAY));
        assertThat(ScheduleExceptionInputs.timeOff(STAFF_B, exceptions)).isEmpty();
    }

    @Test
    void emptyOverrideRemainsAPresentEmptyOverrideAndPeriodsStaySeparate() {
        List<ScheduleException> exceptions = List.of(
                stored(ScheduleExceptionContent.workingDayOverride(STAFF_A, TUESDAY, List.of())),
                stored(ScheduleExceptionContent.workingDayOverride(
                        STAFF_A,
                        TUESDAY.plusDays(1),
                        List.of(period("09:00", "12:00"), period("12:00", "15:00")))),
                stored(ScheduleExceptionContent.workingDayOverride(
                        STAFF_B, TUESDAY, List.of(period("08:00", "09:00")))));

        Map<LocalDate, List<LocalPeriod>> overrides =
                ScheduleExceptionInputs.overrides(STAFF_A, exceptions);

        assertThat(overrides).containsOnlyKeys(TUESDAY, TUESDAY.plusDays(1));
        assertThat(overrides.get(TUESDAY)).isEmpty();
        assertThat(overrides.get(TUESDAY.plusDays(1)))
                .containsExactly(period("09:00", "12:00"), period("12:00", "15:00"));
    }

    @Test
    void duplicateOverridesForOneDateAreRejectedAsAmbiguous() {
        List<ScheduleException> exceptions = List.of(
                stored(ScheduleExceptionContent.workingDayOverride(STAFF_A, TUESDAY, List.of())),
                stored(ScheduleExceptionContent.workingDayOverride(
                        STAFF_A, TUESDAY, List.of(period("09:00", "10:00")))));

        assertThatThrownBy(() -> ScheduleExceptionInputs.overrides(STAFF_A, exceptions))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void additionalPeriodsRemainSeparateAndAreScopedToTheStaffMember() {
        List<ScheduleException> exceptions = List.of(
                stored(ScheduleExceptionContent.additionalWorkingPeriods(
                        STAFF_A,
                        TUESDAY,
                        List.of(period("14:00", "15:00"), period("15:00", "16:00")))),
                stored(ScheduleExceptionContent.additionalWorkingPeriods(
                        STAFF_B, TUESDAY, List.of(period("07:00", "08:00")))));

        assertThat(ScheduleExceptionInputs.additionalPeriods(STAFF_A, exceptions))
                .containsOnlyKeys(TUESDAY)
                .containsEntry(
                        TUESDAY, List.of(period("14:00", "15:00"), period("15:00", "16:00")));
    }

    @Test
    void additionalPeriodsResultIsDeeplyImmutableAndKeepsSeparateOrderedPeriods() {
        List<ScheduleException> exceptions = List.of(
                stored(ScheduleExceptionContent.additionalWorkingPeriods(
                        STAFF_A,
                        TUESDAY,
                        List.of(period("15:00", "16:00"), period("14:00", "15:00")))));

        Map<LocalDate, List<LocalPeriod>> additional =
                ScheduleExceptionInputs.additionalPeriods(STAFF_A, exceptions);

        assertThatThrownBy(() -> additional.put(TUESDAY.plusDays(1), List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> additional.remove(TUESDAY))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> additional.get(TUESDAY).add(period("18:00", "19:00")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> additional.get(TUESDAY).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(additional).containsOnlyKeys(TUESDAY);
        assertThat(additional.get(TUESDAY))
                .containsExactly(period("14:00", "15:00"), period("15:00", "16:00"));
        StaffAvailabilityInput input = new StaffAvailabilityInput(
                STAFF_A, Map.of(), Map.of(), additional, List.of(), List.of());
        assertThat(input.additionalPeriods()).isEqualTo(additional);
    }

    @Test
    void translatedStoredExceptionsReproduceTheAdr0013PrecedenceInTheEngine() {
        List<ScheduleException> exceptions = List.of(
                stored(ScheduleExceptionContent.workingDayOverride(
                        STAFF_A, TUESDAY, List.of(period("10:00", "12:00")))),
                stored(ScheduleExceptionContent.additionalWorkingPeriods(
                        STAFF_A, TUESDAY, List.of(period("14:00", "15:00")))),
                stored(ScheduleExceptionContent.staffTimeOffPartial(
                        STAFF_A, TUESDAY, List.of(period("11:00", "11:30")))),
                stored(ScheduleExceptionContent.businessClosurePartial(
                        TUESDAY, List.of(period("10:00", "10:15")))));
        StaffAvailabilityInput staffInput = new StaffAvailabilityInput(
                STAFF_A,
                Map.of(DayOfWeek.TUESDAY, List.of(period("09:00", "12:00"))),
                ScheduleExceptionInputs.overrides(STAFF_A, exceptions),
                ScheduleExceptionInputs.additionalPeriods(STAFF_A, exceptions),
                ScheduleExceptionInputs.timeOff(STAFF_A, exceptions),
                List.of());

        List<AvailableSlot> slots = AvailabilityEngine.calculate(new AvailabilityRequest(
                SOFIA,
                minutes(30),
                instant(SOFIA, "2026-06-01T05:00:00"),
                ScheduleExceptionInputs.businessClosures(exceptions),
                List.of(staffInput)));

        // The override replaces the recurring 09:00 start; the closure blocks
        // 10:00-10:15 and the time off 11:00-11:30; adjacency is allowed.
        assertThat(localTimesOn(slots, TUESDAY))
                .isEqualTo(times("10:15", "10:30", "11:30", "14:00", "14:15", "14:30"));
    }

    @Test
    void emptyOverrideRemovesTheRecurringDayInTheEngine() {
        List<ScheduleException> exceptions = List.of(
                stored(ScheduleExceptionContent.workingDayOverride(STAFF_A, TUESDAY, List.of())));
        StaffAvailabilityInput staffInput = new StaffAvailabilityInput(
                STAFF_A,
                Map.of(DayOfWeek.TUESDAY, List.of(period("09:00", "12:00"))),
                ScheduleExceptionInputs.overrides(STAFF_A, exceptions),
                Map.of(),
                List.of(),
                List.of());

        List<AvailableSlot> slots = AvailabilityEngine.calculate(new AvailabilityRequest(
                SOFIA,
                minutes(30),
                instant(SOFIA, "2026-06-01T05:00:00"),
                List.of(),
                List.of(staffInput)));

        assertThat(localTimesOn(slots, TUESDAY)).isEmpty();
        assertThat(localTimesOn(slots, TUESDAY.plusDays(7))).isNotEmpty();
    }
}
