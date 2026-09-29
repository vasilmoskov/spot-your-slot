package bg.spotyourslot.scheduling.application;

import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.BUSINESS;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.SOFIA;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.STAFF_A;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.distinctDates;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.everyDay;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.local;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.localStarts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.BusyIntervalSource.BusyWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Orchestration wiring for notice, horizon, Business-local dates and DST. Every
 * transition is derived from {@link ZoneRules}; the pure engine's own matrix is
 * covered in the domain tests and is not repeated here.
 */
class AvailabilityQueryServiceTimeTests {
    private static final ZoneRules RULES = SOFIA.getRules();

    private AvailabilityQueryHarness harness(Instant now, String start, String end, int minutes) {
        return new AvailabilityQueryHarness(SOFIA, now)
                .service(minutes)
                .staff(everyDay(STAFF_A, start, end));
    }

    // ---- minimum notice ----

    @Test
    void theExactTwoHourNoticeInstantIsIncluded() {
        // 04:45Z is 07:45 in Sofia (UTC+3), so the earliest start is exactly 09:45.
        Instant now = Instant.parse("2026-09-29T04:45:00Z");
        LocalDate today = LocalDate.of(2026, 9, 29);

        AvailabilitySnapshot snapshot = harness(now, "09:00", "18:00", 30).calculate(null);

        assertThat(localStarts(snapshot, today)).first().isEqualTo(LocalTime.of(9, 45));
        assertThat(snapshot.slots().getFirst().start()).isEqualTo(now.plus(Duration.ofHours(2)));
    }

    @Test
    void oneNanosecondPastTheNoticeInstantExcludesThatStart() {
        Instant now = Instant.parse("2026-09-29T04:45:00Z").plusNanos(1);
        LocalDate today = LocalDate.of(2026, 9, 29);

        AvailabilitySnapshot snapshot = harness(now, "09:00", "18:00", 30).calculate(null);

        assertThat(localStarts(snapshot, today)).first().isEqualTo(LocalTime.of(10, 0));
    }

    // ---- horizon and Business-local today ----

    @Test
    void theLastIncludedHorizonDateHasSlotsAndTheFirstExcludedDateHasNone() {
        Instant now = Instant.parse("2026-09-29T08:00:00Z");
        LocalDate today = LocalDate.of(2026, 9, 29);

        AvailabilitySnapshot snapshot = harness(now, "09:00", "18:00", 30).calculate(null);

        assertThat(distinctDates(snapshot)).hasSize(30);
        assertThat(distinctDates(snapshot).getFirst()).isEqualTo(today);
        assertThat(distinctDates(snapshot).getLast()).isEqualTo(today.plusDays(29));
        assertThat(localStarts(snapshot, today.plusDays(29))).isNotEmpty();
        assertThat(localStarts(snapshot, today.plusDays(30))).isEmpty();
    }

    @Test
    void oneSecondBeforeLocalMidnightStillBelongsToTheEarlierLocalDate() {
        // 20:59:59Z is 23:59:59 on 5 October in Sofia (UTC+3).
        Instant now = Instant.parse("2026-10-05T20:59:59Z");
        LocalDate today = LocalDate.of(2026, 10, 5);
        AvailabilityQueryHarness harness = harness(now, "09:00", "18:00", 30);

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(localStarts(snapshot, today)).isEmpty();
        assertThat(distinctDates(snapshot).getFirst()).isEqualTo(today.plusDays(1));
        assertThat(distinctDates(snapshot).getLast()).isEqualTo(today.plusDays(29));
        assertThat(busyWindowBounds(harness)[0]).isEqualTo(local(SOFIA, today, "00:00"));
        verify(harness.exceptions).findOverlappingForStaff(
                eq(BUSINESS), eq(today), eq(today.plusDays(29)), anyCollection());
    }

    @Test
    void exactlyLocalMidnightStartsTheNextLocalDateAndItsThirtyDateWindow() {
        // 21:00Z is 00:00 on 6 October in Sofia.
        Instant now = Instant.parse("2026-10-05T21:00:00Z");
        LocalDate today = LocalDate.of(2026, 10, 6);
        AvailabilityQueryHarness harness = harness(now, "09:00", "18:00", 30);

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(distinctDates(snapshot).getFirst()).isEqualTo(today);
        assertThat(distinctDates(snapshot).getLast()).isEqualTo(today.plusDays(29));
        assertThat(busyWindowBounds(harness)[0]).isEqualTo(local(SOFIA, today, "00:00"));
        verify(harness.exceptions).findOverlappingForStaff(
                eq(BUSINESS), eq(today), eq(today.plusDays(29)), anyCollection());
    }

    // ---- DST ----

    @Test
    void theBusyWindowSpansThirtyZoneRuleDaysAcrossAnAutumnOverlap() {
        ZoneOffsetTransition overlap = firstTransition(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(overlap.isOverlap()).isTrue();
        Instant now = overlap.getInstant().minus(Duration.ofDays(5));
        AvailabilityQueryHarness harness = harness(now, "09:00", "18:00", 30);

        harness.calculate(null);

        Instant[] bounds = busyWindowBounds(harness);
        LocalDate today = LocalDate.ofInstant(now, SOFIA);
        assertThat(bounds[0]).isEqualTo(today.atStartOfDay(SOFIA).toInstant());
        assertThat(bounds[1]).isEqualTo(today.plusDays(30).atStartOfDay(SOFIA).toInstant());
        assertThat(Duration.between(bounds[0], bounds[1]))
                .isEqualTo(Duration.ofDays(30).plus(overlap.getDuration().abs()))
                .isNotEqualTo(Duration.ofDays(30));
    }

    @Test
    void theBusyWindowSpansThirtyZoneRuleDaysAcrossASpringGap() {
        ZoneOffsetTransition gap = firstTransition(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(gap.isGap()).isTrue();
        Instant now = gap.getInstant().minus(Duration.ofDays(5));
        AvailabilityQueryHarness harness = harness(now, "09:00", "18:00", 30);

        harness.calculate(null);

        Instant[] bounds = busyWindowBounds(harness);
        assertThat(Duration.between(bounds[0], bounds[1]))
                .isEqualTo(Duration.ofDays(30).minus(gap.getDuration()))
                .isNotEqualTo(Duration.ofDays(30));
    }

    @Test
    void aSpringGapProducesNoSlotStartInsideTheSkippedWallClockHour() {
        ZoneOffsetTransition gap = firstTransition(Instant.parse("2027-01-01T00:00:00Z"));
        Instant now = gap.getInstant().minus(Duration.ofDays(5));
        LocalDate gapDate = gap.getDateTimeBefore().toLocalDate();

        AvailabilitySnapshot snapshot = harness(now, "00:00", "23:59", 30).calculate(null);

        LocalDateTime skippedFrom = gap.getDateTimeBefore();
        LocalDateTime skippedUntil = gap.getDateTimeAfter();
        List<AvailabilitySlot> onGapDate = snapshot.slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(gapDate))
                .toList();
        assertThat(onGapDate).isNotEmpty();
        assertThat(onGapDate).noneMatch(slot -> {
            LocalDateTime start = slot.start().atZone(SOFIA).toLocalDateTime();
            return !start.isBefore(skippedFrom) && start.isBefore(skippedUntil);
        });
        assertThat(localStarts(snapshot, gapDate)).contains(
                skippedFrom.toLocalTime().minusMinutes(30), skippedUntil.toLocalTime());
        assertThat(onGapDate).anyMatch(slot -> slot.startOffset().equals(gap.getOffsetBefore()));
        assertThat(onGapDate).anyMatch(slot -> slot.startOffset().equals(gap.getOffsetAfter()));
    }

    @Test
    void anAutumnOverlapKeepsRepeatedWallClockStartsDistinctByInstantAndOffset() {
        ZoneOffsetTransition overlap = firstTransition(Instant.parse("2026-07-01T00:00:00Z"));
        Instant now = overlap.getInstant().minus(Duration.ofDays(5));
        LocalDate overlapDate = overlap.getDateTimeAfter().toLocalDate();
        LocalTime repeated = overlap.getDateTimeAfter().toLocalTime().plusMinutes(30);

        AvailabilitySnapshot snapshot = harness(now, "00:00", "23:59", 30).calculate(null);

        List<AvailabilitySlot> occurrences = snapshot.slots().stream()
                .filter(slot -> slot.start().atZone(slot.startOffset()).toLocalDate()
                        .equals(overlapDate))
                .filter(slot -> LocalDateTime.ofInstant(slot.start(), slot.startOffset())
                        .toLocalTime().equals(repeated))
                .toList();
        assertThat(occurrences).hasSize(2);
        assertThat(occurrences.get(0).startOffset()).isEqualTo(overlap.getOffsetBefore());
        assertThat(occurrences.get(1).startOffset()).isEqualTo(overlap.getOffsetAfter());
        assertThat(occurrences.get(0).start()).isBefore(occurrences.get(1).start());
        assertThat(Duration.between(occurrences.get(0).start(), occurrences.get(1).start()))
                .isEqualTo(overlap.getDuration().abs());
        assertThat(snapshot.slots()).extracting(AvailabilitySlot::start)
                .doesNotHaveDuplicates()
                .isSorted();
    }

    // ---- busy adjacency and overlap through real translation ----

    @Test
    void busyTimeAdjacentToASlotDoesNotBlockItButOverlapDoes() {
        Instant now = Instant.parse("2026-09-29T08:00:00Z");
        LocalDate day = LocalDate.of(2026, 10, 2);
        AvailabilityQueryHarness harness = harness(now, "09:00", "18:00", 60)
                .busyWindows(Map.of(STAFF_A, List.of(
                        new BusyWindow(
                                local(SOFIA, day, "10:00"), local(SOFIA, day, "11:00")))));

        List<LocalTime> starts = localStarts(harness.calculate(null), day);

        assertThat(starts).contains(LocalTime.of(9, 0), LocalTime.of(11, 0));
        assertThat(starts).doesNotContain(LocalTime.of(9, 15), LocalTime.of(10, 45));
    }

    private static ZoneOffsetTransition firstTransition(Instant after) {
        ZoneOffsetTransition transition = RULES.nextTransition(after);
        assertThat(transition).isNotNull();
        return transition;
    }

    private static Instant[] busyWindowBounds(AvailabilityQueryHarness harness) {
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(harness.busy).findBusyWindows(
                eq(BUSINESS), anyCollection(), from.capture(), to.capture());
        return new Instant[] {from.getValue(), to.getValue()};
    }
}
