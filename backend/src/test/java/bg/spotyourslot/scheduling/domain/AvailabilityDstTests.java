package bg.spotyourslot.scheduling.domain;

import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.SOFIA;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_A;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.minutes;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.request;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.staff;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.times;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DST behavior for Europe/Sofia; transitions are derived from the JDK zone rules. */
class AvailabilityDstTests {
    private static final ZoneRules RULES = SOFIA.getRules();

    private static ZoneOffsetTransition firstTransition(boolean gap) {
        ZoneOffsetTransition transition = RULES.nextTransition(Instant.parse("2026-01-01T00:00:00Z"));
        while (transition.isGap() != gap) {
            transition = RULES.nextTransition(transition.getInstant());
        }
        return transition;
    }

    private static List<AvailableSlot> calculateAround(
            ZoneOffsetTransition transition, LocalTime start, LocalTime end, Duration duration) {
        LocalDate date = transition.getDateTimeBefore().toLocalDate();
        Instant now = date.minusDays(1).atStartOfDay(SOFIA).toInstant();
        StaffAvailabilityInput working = staff(STAFF_A)
                .override(date, new LocalPeriod(start, end))
                .build();
        return AvailabilityEngine.calculate(request(SOFIA, duration, now, working)).stream()
                .filter(slot -> slot.localStart().toLocalDate().equals(date))
                .toList();
    }

    @Test
    void springForwardGapProducesNoCandidateInTheNonexistentLocalHour() {
        ZoneOffsetTransition gap = firstTransition(true);
        assertThat(gap.getDuration()).isEqualTo(Duration.ofHours(1));
        LocalTime beforeGap = gap.getDateTimeBefore().toLocalTime();
        LocalTime afterGap = gap.getDateTimeAfter().toLocalTime();

        List<AvailableSlot> slots = calculateAround(
                gap, beforeGap.minusHours(1), afterGap.plusHours(1), minutes(30));

        assertThat(slots).extracting(slot -> slot.localStart().toLocalTime())
                .allSatisfy(time -> assertThat(time.isBefore(beforeGap) || !time.isBefore(afterGap)).isTrue());
        assertThat(slots).extracting(slot -> slot.localStart().toLocalTime()).isEqualTo(times(
                beforeGap.minusHours(1).toString(),
                beforeGap.minusMinutes(45).toString(),
                beforeGap.minusMinutes(30).toString(),
                beforeGap.minusMinutes(15).toString(),
                afterGap.toString(),
                afterGap.plusMinutes(15).toString(),
                afterGap.plusMinutes(30).toString()));
    }

    @Test
    void serviceDurationIsElapsedTimeAcrossTheSpringForwardGap() {
        ZoneOffsetTransition gap = firstTransition(true);
        LocalTime beforeGap = gap.getDateTimeBefore().toLocalTime();
        LocalTime afterGap = gap.getDateTimeAfter().toLocalTime();

        List<AvailableSlot> slots = calculateAround(
                gap, beforeGap.minusHours(1), afterGap.plusHours(1), minutes(30));

        AvailableSlot lastBeforeGap = slots.stream()
                .filter(slot -> slot.localStart().toLocalTime().equals(beforeGap.minusMinutes(15)))
                .findFirst().orElseThrow();
        // Fifteen local minutes before the gap plus fifteen elapsed minutes after it.
        assertThat(lastBeforeGap.end()).isEqualTo(gap.getInstant().plus(Duration.ofMinutes(15)));
        assertThat(lastBeforeGap.startOffset()).isEqualTo(gap.getOffsetBefore());
    }

    @Test
    void periodEndingInsideTheGapEndsAtTheTransition() {
        ZoneOffsetTransition gap = firstTransition(true);
        LocalTime beforeGap = gap.getDateTimeBefore().toLocalTime();

        List<AvailableSlot> slots = calculateAround(
                gap, beforeGap.minusHours(1), beforeGap.plusMinutes(30), minutes(30));

        assertThat(slots).extracting(slot -> slot.localStart().toLocalTime()).isEqualTo(times(
                beforeGap.minusHours(1).toString(),
                beforeGap.minusMinutes(45).toString(),
                beforeGap.minusMinutes(30).toString()));
        assertThat(slots.getLast().end()).isEqualTo(gap.getInstant());
    }

    @Test
    void periodStartingInsideTheGapBeginsAtTheTransition() {
        ZoneOffsetTransition gap = firstTransition(true);
        LocalTime beforeGap = gap.getDateTimeBefore().toLocalTime();
        LocalTime afterGap = gap.getDateTimeAfter().toLocalTime();

        List<AvailableSlot> slots = calculateAround(
                gap, beforeGap.plusMinutes(30), afterGap.plusHours(1), minutes(30));

        assertThat(slots.getFirst().localStart().toLocalTime()).isEqualTo(afterGap);
        assertThat(slots.getFirst().start()).isEqualTo(gap.getInstant());
    }

    @Test
    void autumnOverlapProducesTwoDistinctStartInstantsForARepeatedWallClockTime() {
        ZoneOffsetTransition overlap = firstTransition(false);
        assertThat(overlap.getDuration()).isEqualTo(Duration.ofHours(-1));
        LocalTime repeatedStart = overlap.getDateTimeAfter().toLocalTime();

        List<AvailableSlot> slots = calculateAround(
                overlap,
                repeatedStart.minusHours(1),
                overlap.getDateTimeBefore().toLocalTime().plusHours(1),
                minutes(30));

        List<AvailableSlot> repeated = slots.stream()
                .filter(slot -> slot.localStart().toLocalTime().equals(repeatedStart))
                .toList();
        assertThat(repeated).hasSize(2);
        assertThat(repeated.get(0).startOffset()).isEqualTo(overlap.getOffsetBefore());
        assertThat(repeated.get(1).startOffset()).isEqualTo(overlap.getOffsetAfter());
        assertThat(repeated.get(0).start()).isNotEqualTo(repeated.get(1).start());
        assertThat(Duration.between(repeated.get(0).start(), repeated.get(1).start()))
                .isEqualTo(Duration.ofHours(1));
        assertThat(slots).extracting(AvailableSlot::start).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void autumnOverlapPeriodOffersEveryElapsedGridInstantAndTheServiceMayCrossTheTransition() {
        ZoneOffsetTransition overlap = firstTransition(false);
        LocalTime periodStart = overlap.getDateTimeAfter().toLocalTime().minusHours(1);
        LocalTime periodEnd = overlap.getDateTimeBefore().toLocalTime().plusHours(1);

        List<AvailableSlot> slots = calculateAround(overlap, periodStart, periodEnd, minutes(30));

        // 02:00-05:00 local spans four elapsed hours: 15 grid starts of 30 minutes.
        assertThat(slots).hasSize(15);
        AvailableSlot crossing = slots.stream()
                .filter(slot -> slot.end().isAfter(overlap.getInstant())
                        && slot.start().isBefore(overlap.getInstant()))
                .findFirst().orElseThrow();
        assertThat(crossing.startOffset()).isEqualTo(overlap.getOffsetBefore());
        assertThat(crossing.localStart()).isEqualTo(
                LocalDateTime.of(overlap.getDateTimeBefore().toLocalDate(), overlap.getDateTimeBefore().toLocalTime().minusMinutes(15)));
    }

    @Test
    void periodInsideTheRepeatedHourDoesNotBridgeTheTwoOccurrences() {
        ZoneOffsetTransition overlap = firstTransition(false);
        LocalTime repeatedStart = overlap.getDateTimeAfter().toLocalTime();

        List<AvailableSlot> slots = calculateAround(
                overlap, repeatedStart, repeatedStart.plusMinutes(30), minutes(30));

        // Each occurrence of the wall-clock range is its own contiguous segment.
        assertThat(slots).hasSize(2);
        assertThat(slots).extracting(AvailableSlot::startOffset)
                .containsExactly(overlap.getOffsetBefore(), overlap.getOffsetAfter());
    }

    @Test
    void blockingAWallClockRangeInTheRepeatedHourBlocksBothOccurrences() {
        ZoneOffsetTransition overlap = firstTransition(false);
        LocalDate date = overlap.getDateTimeBefore().toLocalDate();
        LocalTime repeatedStart = overlap.getDateTimeAfter().toLocalTime();
        Instant now = date.minusDays(1).atStartOfDay(SOFIA).toInstant();
        StaffAvailabilityInput working = staff(STAFF_A)
                .override(date, new LocalPeriod(repeatedStart.minusHours(1), repeatedStart.plusHours(2)))
                .timeOff(new LocalBlock.PartialDay(date, new LocalPeriod(repeatedStart, repeatedStart.plusMinutes(30))))
                .build();

        List<AvailableSlot> slots = AvailabilityEngine.calculate(request(SOFIA, minutes(30), now, working));

        assertThat(slots).noneSatisfy(slot -> {
            LocalTime local = slot.localStart().toLocalTime();
            assertThat(!local.isBefore(repeatedStart) && local.isBefore(repeatedStart.plusMinutes(30))).isTrue();
        });
    }
}
