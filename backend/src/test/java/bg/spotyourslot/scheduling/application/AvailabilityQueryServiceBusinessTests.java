package bg.spotyourslot.scheduling.application;

import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.BUSINESS;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.SOFIA;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.STAFF_A;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.STAFF_B;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.everyDay;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.AvailabilityFailure;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.BusinessAvailability;
import bg.spotyourslot.scheduling.BusyIntervalSource.BusyWindow;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Business-side entry of the availability service (ADR-0029 section 1) with mocked published
 * contracts: it looks up no Service, reads no clock, forwards the ignored Appointment only through
 * the second busy-source method, rejects caller errors before any read, joins only a
 * repeatable-read or serializable transaction, and sanitizes every unexpected failure.
 */
class AvailabilityQueryServiceBusinessTests {
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    private AvailabilityQueryHarness harness;

    @BeforeEach
    void setUp() {
        harness = new AvailabilityQueryHarness(SOFIA, NOW);
        when(harness.staffMembers.findActiveWithWeeklyPeriods(BUSINESS, STAFF_A))
                .thenReturn(Optional.of(everyDay(STAFF_A, "09:00", "12:00")));
        harness.clock.resetReads();
    }

    private BusinessAvailability calculate(UUID ignored) {
        return AvailabilityQueryHarness.inRepeatableReadTransaction(() -> harness.subject()
                .calculateForBusiness(BUSINESS, Duration.ofMinutes(30), STAFF_A, DAY, DAY, NOW, ignored));
    }

    @Test
    void itLooksUpNoServiceAndNoAssignmentBasedEligibilityAndReadsNoClock() {
        BusinessAvailability result = calculate(null);

        assertThat(result.slots()).isNotEmpty();
        verifyNoInteractions(harness.services);
        verify(harness.staffMembers, never()).findEligibleForServices(any(), anyCollection());
        verify(harness.staffMembers, never()).findEligibleForService(any(), any());
        assertThat(harness.clock.reads()).isZero();
        assertThat(result.calculatedAt()).isEqualTo(NOW);
    }

    @Test
    void withoutAnIgnoredAppointmentOnlyTheFourArgumentBusyMethodIsUsed() {
        calculate(null);

        verify(harness.busy).findBusyWindows(eq(BUSINESS), eq(List.of(STAFF_A)), any(), any());
        verify(harness.busy, never()).findBusyWindows(any(), anyCollection(), any(), any(), any());
    }

    @Test
    void anIgnoredAppointmentIsForwardedThroughTheSecondMethodOnlyAndWithTheZoneRuleWindow() {
        UUID ignored = UUID.randomUUID();

        calculate(ignored);

        verify(harness.busy).findBusyWindows(
                BUSINESS, List.of(STAFF_A),
                DAY.atStartOfDay(SOFIA).toInstant(),
                DAY.plusDays(1).atStartOfDay(SOFIA).toInstant(),
                ignored);
        verify(harness.busy, never()).findBusyWindows(any(), anyCollection(), any(), any());
    }

    @Test
    void theBusyWindowReturnedForTheIgnoredAppointmentIsTheOnlyOneTheEngineSeesBlocked() {
        Instant blockedStart = DAY.atTime(LocalTime.of(10, 0)).atZone(SOFIA).toInstant();
        when(harness.busy.findBusyWindows(any(), anyCollection(), any(), any())).thenReturn(
                Map.of(STAFF_A, List.of(new BusyWindow(blockedStart, blockedStart.plusSeconds(1800)))));
        when(harness.busy.findBusyWindows(any(), anyCollection(), any(), any(), any()))
                .thenReturn(Map.of());

        List<Instant> blocked = calculate(null).slots().stream().map(AvailabilitySlot::start).toList();
        List<Instant> ignoring = calculate(UUID.randomUUID()).slots().stream()
                .map(AvailabilitySlot::start).toList();

        assertThat(blocked).doesNotContain(blockedStart);
        assertThat(ignoring).contains(blockedStart);
    }

    @Test
    void anInactiveBusinessAndAnUnknownStaffMemberAreTheTypedNotEligibleResults() {
        harness.business(LifecycleStatus.DRAFT);
        assertThatThrownBy(() -> calculate(null)).isInstanceOf(BusinessNotBookable.class);
        harness.business(LifecycleStatus.SUSPENDED);
        assertThatThrownBy(() -> calculate(null)).isInstanceOf(BusinessNotBookable.class);
        harness.business(LifecycleStatus.ACTIVE);

        when(harness.staffMembers.findActiveWithWeeklyPeriods(BUSINESS, STAFF_A)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> calculate(null)).isInstanceOf(StaffMemberNotEligible.class);
    }

    @Test
    void callerErrorsAreRejectedBeforeAnyRead() {
        List<Runnable> invalid = List.of(
                () -> call(Duration.ofMinutes(0), DAY, DAY),
                () -> call(Duration.ofMinutes(481), DAY, DAY),
                () -> call(Duration.ofSeconds(90), DAY, DAY),
                () -> call(Duration.ofMinutes(30), DAY.plusDays(1), DAY),
                () -> call(Duration.ofMinutes(30), DAY, DAY.plusDays(31)),
                () -> call(Duration.ofMinutes(30), LocalDate.of(1999, 12, 31), DAY),
                () -> call(Duration.ofMinutes(30), DAY, LocalDate.of(2101, 1, 1)));
        for (Runnable action : invalid) {
            assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class);
        }

        verifyNoInteractions(harness.businesses, harness.services, harness.exceptions, harness.busy);
        assertThat(harness.clock.reads()).isZero();
    }

    private void call(Duration duration, LocalDate first, LocalDate last) {
        AvailabilityQueryHarness.inRepeatableReadTransaction(() -> harness.subject()
                .calculateForBusiness(BUSINESS, duration, STAFF_A, first, last, NOW, null));
    }

    @Test
    void theBoundaryDurationsAndWindowsAreAccepted() {
        call(Duration.ofMinutes(1), DAY, DAY);
        call(Duration.ofMinutes(480), DAY, DAY.plusDays(30));
        call(Duration.ofMinutes(30), LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 1));
        call(Duration.ofMinutes(30), LocalDate.of(2100, 12, 31), LocalDate.of(2100, 12, 31));
    }

    @Test
    void withoutARepeatableReadOrSerializableTransactionItFailsBeforeAnyReadAndSanitized() {
        assertThatThrownBy(() -> harness.subject().calculateForBusiness(
                BUSINESS, Duration.ofMinutes(30), STAFF_A, DAY, DAY, NOW, null))
                .isInstanceOf(AvailabilityFailure.class).hasMessage("Availability calculation failed");
        assertThatThrownBy(() -> AvailabilityQueryHarness.inTransactionState(
                true, Connection.TRANSACTION_READ_COMMITTED, () -> harness.subject()
                        .calculateForBusiness(BUSINESS, Duration.ofMinutes(30), STAFF_A, DAY, DAY, NOW, null)))
                .isInstanceOf(AvailabilityFailure.class);
        assertThatThrownBy(() -> AvailabilityQueryHarness.inTransactionState(
                true, null, () -> harness.subject()
                        .calculateForBusiness(BUSINESS, Duration.ofMinutes(30), STAFF_A, DAY, DAY, NOW, null)))
                .isInstanceOf(AvailabilityFailure.class);

        verifyNoInteractions(harness.businesses, harness.services, harness.exceptions, harness.busy);
        assertThat(AvailabilityQueryHarness.inTransactionState(
                true, Connection.TRANSACTION_SERIALIZABLE, () -> harness.subject()
                        .calculateForBusiness(BUSINESS, Duration.ofMinutes(30), STAFF_A, DAY, DAY, NOW, null)))
                .isNotNull();
    }

    @Test
    void anUnexpectedFailureAndAnInconsistentAnswerAreSanitized() {
        RuntimeException leaked = new IllegalStateException("SQL secret " + STAFF_B);
        when(harness.exceptions.findOverlappingForStaff(any(), any(), any(), anyCollection())).thenThrow(leaked);
        assertThatThrownBy(() -> calculate(null))
                .isInstanceOf(AvailabilityFailure.class)
                .hasMessage("Availability calculation failed")
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("secret"));

        reset(harness.exceptions);
        harness.exceptions();
        when(harness.staffMembers.findActiveWithWeeklyPeriods(BUSINESS, STAFF_A))
                .thenReturn(Optional.of(everyDay(STAFF_B, "09:00", "12:00")));
        assertThatThrownBy(() -> calculate(null)).isInstanceOf(AvailabilityFailure.class);

        when(harness.staffMembers.findActiveWithWeeklyPeriods(BUSINESS, STAFF_A))
                .thenReturn(Optional.of(everyDay(STAFF_A, "09:00", "12:00")));
        when(harness.busy.findBusyWindows(any(), anyCollection(), any(), any()))
                .thenReturn(Map.of(STAFF_B, List.of()));
        assertThatThrownBy(() -> calculate(null)).isInstanceOf(AvailabilityFailure.class);
    }
}
