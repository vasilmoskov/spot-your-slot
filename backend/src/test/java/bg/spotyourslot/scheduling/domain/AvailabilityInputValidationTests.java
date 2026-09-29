package bg.spotyourslot.scheduling.domain;

import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.SOFIA;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.STAFF_A;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.minutes;
import static bg.spotyourslot.scheduling.domain.AvailabilityTestSupport.staff;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityInputValidationTests {
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void localPeriodRequiresStartBeforeEnd() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalPeriod(LocalTime.of(10, 0), LocalTime.of(10, 0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalPeriod(LocalTime.of(11, 0), LocalTime.of(10, 0)));
    }

    @Test
    void localPeriodRejectsSecondsInEitherBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalPeriod(LocalTime.of(9, 0, 1), LocalTime.of(10, 0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalPeriod(LocalTime.of(9, 0), LocalTime.of(10, 0, 30)));
    }

    @Test
    void localPeriodRejectsNanosecondsInEitherBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalPeriod(LocalTime.of(9, 0, 0, 1), LocalTime.of(10, 0)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalPeriod(LocalTime.of(9, 0), LocalTime.of(10, 0, 0, 1)));
    }

    @Test
    void localPeriodAcceptsWholeMinutePeriodsIncludingOneEndingAt2359() {
        assertThat(new LocalPeriod(LocalTime.of(9, 0), LocalTime.of(17, 30))).isNotNull();
        assertThat(new LocalPeriod(LocalTime.MIDNIGHT, LocalTime.of(23, 59)).end())
                .isEqualTo(LocalTime.of(23, 59));
    }

    @Test
    void busyIntervalRequiresStartBeforeEnd() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BusyInterval(NOW, NOW));
    }

    @Test
    void fullDayBlockRequiresOrderedDates() {
        LocalDate date = LocalDate.of(2026, 6, 2);
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalBlock.FullDays(date, date.minusDays(1)));
        assertThat(LocalBlock.FullDays.singleDay(date).lastDate()).isEqualTo(date);
    }

    @Test
    void occupiedDurationMustBeAPositiveWholeNumberOfMinutes() {
        for (Duration invalid : List.of(
                Duration.ZERO, Duration.ofMinutes(-5), Duration.ofSeconds(90), Duration.ofNanos(1))) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new AvailabilityRequest(SOFIA, invalid, NOW, List.of(), List.of()));
        }
        assertThat(new AvailabilityRequest(SOFIA, minutes(1), NOW, List.of(), List.of())).isNotNull();
    }

    @Test
    void aStaffMemberMayAppearOnlyOnce() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AvailabilityRequest(
                SOFIA,
                minutes(30),
                NOW,
                List.of(),
                List.of(staff(STAFF_A).build(), staff(STAFF_A).build())));
    }

    @Test
    void inputRecordsAreDefensivelyCopied() {
        List<LocalBlock> blocks = new java.util.ArrayList<>();
        AvailabilityRequest request = new AvailabilityRequest(SOFIA, minutes(30), NOW, blocks, List.of());
        blocks.add(LocalBlock.FullDays.singleDay(LocalDate.of(2026, 6, 1)));

        assertThat(request.businessClosures()).isEmpty();
    }

    @Test
    void availabilityPolicyMatchesTheApprovedMvpValues() {
        assertThat(AvailabilityPolicy.SLOT_STEP_MINUTES).isEqualTo(15);
        assertThat(AvailabilityPolicy.MINIMUM_NOTICE).isEqualTo(Duration.ofHours(2));
        assertThat(AvailabilityPolicy.HORIZON_DAYS).isEqualTo(30);
    }
}
