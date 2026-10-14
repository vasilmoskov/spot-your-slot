package bg.spotyourslot.scheduling.domain;

import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.SOFIA;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_A;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.instant;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.localTimesOn;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.minutes;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.period;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.staff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The explicit notice and date-window inputs of the engine (ADR-0029 section 1): the public policy
 * is exactly the former constants, the Business policy is zero notice over a requested window, and
 * invalid inputs are refused. The recorded results of the public policy are pinned separately by
 * {@link PublicAvailabilityCharacterisationTests}.
 */
class AvailabilityPolicyParametersTests {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final Instant NOW = instant(SOFIA, "2026-09-29T11:00:00");

    private static StaffAvailabilityInput everyDay() {
        return staff(STAFF_A).everyDay(period("09:00", "18:00")).build();
    }

    @Test
    void theFiveArgumentRequestIsExactlyThePublicPolicy() {
        AvailabilityRequest legacy = new AvailabilityRequest(
                SOFIA, minutes(30), NOW, List.of(), List.of(everyDay()));

        assertThat(legacy.minimumNotice()).isEqualTo(Duration.ofHours(2));
        assertThat(legacy.firstDate()).isEqualTo(DAY);
        assertThat(legacy.dateCount()).isEqualTo(30);
        assertThat(legacy.minimumNotice()).isEqualTo(AvailabilityPolicy.MINIMUM_NOTICE);
        assertThat(legacy.dateCount()).isEqualTo(AvailabilityPolicy.HORIZON_DAYS);
        assertThat(AvailabilityEngine.calculate(legacy)).isEqualTo(AvailabilityEngine.calculate(
                new AvailabilityRequest(
                        SOFIA, minutes(30), NOW, Duration.ofHours(2), DAY, 30, List.of(), List.of(everyDay()))));
    }

    @Test
    void thePublicFirstDateIsTheBusinessLocalDateOfNowNotTheUtcDate() {
        Instant lateUtc = Instant.parse("2026-09-29T22:30:00Z");   // already 2026-09-30 in Sofia

        AvailabilityRequest legacy = new AvailabilityRequest(
                SOFIA, minutes(30), lateUtc, List.of(), List.of(everyDay()));

        assertThat(legacy.firstDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void zeroNoticeOffersTheInstantNowItselfAndNotAnEarlierOne() {
        AvailabilityRequest request = new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ZERO, DAY, 1, List.of(), List.of(everyDay()));

        List<LocalTime> times = localTimesOn(AvailabilityEngine.calculate(request), DAY);

        assertThat(times.get(0)).isEqualTo(LocalTime.of(11, 0));
        assertThat(times).doesNotContain(LocalTime.of(10, 45));
    }

    @Test
    void twoHoursOfNoticeOnTheSameRequestMovesTheFirstStartTwoHoursLater() {
        AvailabilityRequest request = new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ofHours(2), DAY, 1, List.of(), List.of(everyDay()));

        assertThat(localTimesOn(AvailabilityEngine.calculate(request), DAY).get(0))
                .isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    void theWindowDecidesWhichDatesAreCalculatedAndMayLieBeyondThirtyDaysOrBeforeNow() {
        LocalDate far = DAY.plusDays(200);
        AvailabilityRequest farWindow = new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ZERO, far, 2, List.of(), List.of(everyDay()));
        AvailabilityRequest pastWindow = new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ZERO, DAY.minusDays(3), 2, List.of(), List.of(everyDay()));

        List<AvailableSlot> farSlots = AvailabilityEngine.calculate(farWindow);

        assertThat(farSlots).isNotEmpty();
        assertThat(farSlots.stream().map(slot -> slot.localStart().toLocalDate()).distinct().toList())
                .containsExactly(far, far.plusDays(1));
        assertThat(AvailabilityEngine.calculate(pastWindow)).isEmpty();
    }

    @Test
    void aThirtyOneDateWindowCoversAllThirtyOneDates() {
        AvailabilityRequest request = new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ZERO, DAY.plusDays(1), 31, List.of(), List.of(everyDay()));

        assertThat(AvailabilityEngine.calculate(request).stream()
                .map(slot -> slot.localStart().toLocalDate()).distinct().count()).isEqualTo(31);
    }

    @Test
    void invalidNoticeAndWindowInputsAreRefused() {
        assertThatThrownBy(() -> new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ofMinutes(-1), DAY, 1, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ZERO, DAY, 0, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ZERO, DAY, 32, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilityRequest(
                SOFIA, minutes(30), NOW, null, DAY, 1, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AvailabilityRequest(
                SOFIA, minutes(30), NOW, Duration.ZERO, null, 1, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
