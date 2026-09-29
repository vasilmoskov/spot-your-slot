package bg.spotyourslot.scheduling.application;

import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.BUSINESS;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.SERVICE;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.SOFIA;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.STAFF_A;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.STAFF_B;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.everyDay;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.local;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.localStarts;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.period;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.stored;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.withoutSchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.ServiceAvailabilityFailure;
import bg.spotyourslot.scheduling.AvailabilityApplicationException;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.AvailabilityFailure;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.BusyIntervalSource.BusyWindow;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException;
import bg.spotyourslot.workforce.StaffAvailabilityAccess.EligibleStaffMember;
import bg.spotyourslot.workforce.StaffAvailabilityAccess.StaffAvailabilityFailure;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class AvailabilityQueryServiceTests {
    /** Tuesday 11:00 in Sofia (UTC+3); the earliest candidate start is 13:00. */
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final LocalDate DAY = TODAY.plusDays(3);

    private AvailabilityQueryHarness harness() {
        return new AvailabilityQueryHarness(SOFIA, NOW)
                .staff(everyDay(STAFF_A, "09:00", "18:00"));
    }

    private static LocalPeriod localPeriod(String start, String end) {
        return new LocalPeriod(LocalTime.parse(start), LocalTime.parse(end));
    }

    // ---- Business eligibility ----

    @Test
    void activeBusinessProducesSlotsWithTimezoneClockAndDuration() {
        AvailabilitySnapshot snapshot = harness().service(60).calculate(null);

        assertThat(snapshot.timezone()).isEqualTo(SOFIA);
        assertThat(snapshot.calculatedAt()).isEqualTo(NOW);
        assertThat(snapshot.occupiedDuration()).isEqualTo(Duration.ofMinutes(60));
        assertThat(localStarts(snapshot, TODAY)).first().isEqualTo(LocalTime.of(13, 0));
        AvailabilitySlot first = snapshot.slots().getFirst();
        assertThat(first.end()).isEqualTo(first.start().plus(Duration.ofMinutes(60)));
        assertThat(first.staffMemberIds()).containsExactly(STAFF_A);
    }

    @Test
    void missingBusinessIsNotBookableAndReadsNothingElse() {
        AvailabilityQueryHarness harness = harness();
        when(harness.businesses.findScheduleContext(BUSINESS)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> harness.calculate(null)).isInstanceOf(BusinessNotBookable.class);

        verifyNoInteractions(harness.services, harness.staffMembers, harness.exceptions, harness.busy);
    }

    @ParameterizedTest
    @EnumSource(value = LifecycleStatus.class, names = {"DRAFT", "SUSPENDED"})
    void nonActiveBusinessSharesOneFailureAndReadsNothingElse(LifecycleStatus status) {
        AvailabilityQueryHarness harness = harness().business(status);

        assertThatThrownBy(() -> harness.calculate(null))
                .isInstanceOf(BusinessNotBookable.class)
                .hasMessage("Business is not available for booking");

        verifyNoInteractions(harness.services, harness.staffMembers, harness.exceptions, harness.busy);
    }

    // ---- Service eligibility and duration ----

    @Test
    void missingForeignOrInactiveServiceIsNotBookable() {
        AvailabilityQueryHarness harness = harness();
        when(harness.services.findBookableService(BUSINESS, SERVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> harness.calculate(null)).isInstanceOf(ServiceNotBookable.class);

        verifyNoInteractions(harness.staffMembers, harness.exceptions, harness.busy);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -15, 481, 600})
    void corruptDurationMinutesBecomeASanitizedFailure(long minutes) {
        AvailabilityQueryHarness harness = harness().service(Duration.ofMinutes(minutes));

        assertSanitizedFailure(harness);
        verifyNoInteractions(harness.staffMembers, harness.exceptions, harness.busy);
    }

    @Test
    void fractionalMinuteDurationBecomesASanitizedFailure() {
        assertSanitizedFailure(harness().service(Duration.ofSeconds(90)));
    }

    @Test
    void serviceIdMismatchBecomesASanitizedFailure() {
        AvailabilityQueryHarness harness = harness();
        when(harness.services.findBookableService(BUSINESS, SERVICE)).thenReturn(Optional.of(
                new BookableService(
                        UUID.randomUUID(), Duration.ofMinutes(30))));

        assertSanitizedFailure(harness);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 480})
    void theCommittedDurationBoundsAreAccepted(int minutes) {
        assertThat(harness().service(minutes).calculate(null).occupiedDuration())
                .isEqualTo(Duration.ofMinutes(minutes));
    }

    // ---- StaffMember selection ----

    @Test
    void specificStaffMemberPassesOnlyThatMemberEverywhere() {
        AvailabilityQueryHarness harness = harness().staff(
                everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "09:00", "18:00"));

        AvailabilitySnapshot snapshot = harness.calculate(STAFF_B);

        assertThat(snapshot.slots()).isNotEmpty();
        assertThat(snapshot.slots()).allSatisfy(
                slot -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_B));
        assertThat(exceptionQueryStaffIds(harness)).containsExactly(STAFF_B);
        assertThat(busyQueryStaffIds(harness)).containsExactly(STAFF_B);
    }

    @Test
    void inactiveForeignOrUnassignedSpecificStaffMemberCollapsesToOneFailure() {
        AvailabilityQueryHarness harness = harness();

        assertThatThrownBy(() -> harness.calculate(UUID.randomUUID()))
                .isInstanceOf(StaffMemberNotEligible.class)
                .hasMessage("StaffMember is not eligible for the Service");

        verifyNoInteractions(harness.exceptions, harness.busy);
    }

    @Test
    void anyStaffMemberCombinesEligibleMembersWithoutDuplicateStartsInNaturalOrder() {
        AvailabilityQueryHarness harness = harness().staff(
                everyDay(STAFF_B, "09:00", "18:00"), everyDay(STAFF_A, "09:00", "18:00"));

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(snapshot.slots()).extracting(AvailabilitySlot::start).doesNotHaveDuplicates();
        assertThat(snapshot.slots()).allSatisfy(
                slot -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_A, STAFF_B));
        assertThat(exceptionQueryStaffIds(harness)).containsExactlyInAnyOrder(STAFF_A, STAFF_B);
    }

    @Test
    void noEligibleStaffMembersIsASuccessfulEmptySnapshotThatReadsNoFurtherData() {
        AvailabilityQueryHarness harness = harness().staff();

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(snapshot.slots()).isEmpty();
        assertThat(snapshot.timezone()).isEqualTo(SOFIA);
        assertThat(snapshot.calculatedAt()).isEqualTo(NOW);
        verifyNoInteractions(harness.exceptions, harness.busy);
    }

    @Test
    void staffMemberWithoutPeriodsIsPassedAndProducesNoSlotsUntilAnAdditionalPeriodExists() {
        AvailabilityQueryHarness harness = harness().staff(withoutSchedule(STAFF_A));
        assertThat(harness.calculate(null).slots()).isEmpty();

        harness.exceptions(stored(ScheduleExceptionContent.additionalWorkingPeriods(
                STAFF_A, DAY, List.of(localPeriod("10:00", "12:00")))));

        assertThat(localStarts(harness.calculate(null), DAY)).containsExactly(
                LocalTime.of(10, 0), LocalTime.of(10, 15), LocalTime.of(10, 30),
                LocalTime.of(10, 45), LocalTime.of(11, 0), LocalTime.of(11, 15),
                LocalTime.of(11, 30));
    }

    // ---- Exceptions through the real translator ----

    @Test
    void emptyOverrideRemovesTheRecurringDayWhileNeighboursRemain() {
        AvailabilityQueryHarness harness = harness().exceptions(stored(
                ScheduleExceptionContent.workingDayOverride(STAFF_A, DAY, List.of())));

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(localStarts(snapshot, DAY)).isEmpty();
        assertThat(localStarts(snapshot, DAY.plusDays(1))).isNotEmpty();
    }

    @Test
    void overrideReplacesRecurringPeriodsForItsDate() {
        AvailabilityQueryHarness harness = harness().exceptions(stored(
                ScheduleExceptionContent.workingDayOverride(
                        STAFF_A, DAY, List.of(localPeriod("12:00", "13:00")))));

        assertThat(localStarts(harness.calculate(null), DAY)).containsExactly(
                LocalTime.of(12, 0), LocalTime.of(12, 15), LocalTime.of(12, 30));
    }

    @Test
    void businessClosureRemovesEveryStaffMemberAndTimeOffOnlyItsOwner() {
        AvailabilityQueryHarness harness = harness()
                .staff(everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "09:00", "18:00"))
                .exceptions(
                        stored(ScheduleExceptionContent.businessClosureDays(DAY, DAY)),
                        stored(ScheduleExceptionContent.staffTimeOffDays(
                                STAFF_B, DAY.plusDays(1), DAY.plusDays(1))));

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(localStarts(snapshot, DAY)).isEmpty();
        assertThat(snapshot.slots()).filteredOn(
                slot -> slot.start().atZone(SOFIA).toLocalDate().equals(DAY.plusDays(1)))
                .isNotEmpty()
                .allSatisfy(slot -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_A));
    }

    @Test
    void partialClosureBlocksOnlyItsWallClockRange() {
        AvailabilityQueryHarness harness = harness().exceptions(stored(
                ScheduleExceptionContent.businessClosurePartial(
                        DAY, List.of(localPeriod("10:00", "11:00")))));

        List<LocalTime> starts = localStarts(harness.calculate(null), DAY);

        assertThat(starts).contains(LocalTime.of(9, 30), LocalTime.of(11, 0));
        assertThat(starts).doesNotContain(LocalTime.of(9, 45), LocalTime.of(10, 30));
    }

    @Test
    void ambiguousOverridesBecomeASanitizedFailure() {
        AvailabilityQueryHarness harness = harness().exceptions(
                stored(ScheduleExceptionContent.workingDayOverride(STAFF_A, DAY, List.of())),
                stored(ScheduleExceptionContent.workingDayOverride(STAFF_A, DAY, List.of())));

        assertSanitizedFailure(harness);
    }

    @Test
    void exceptionOfAnotherBusinessBecomesASanitizedFailure() {
        AvailabilityQueryHarness harness = harness();
        when(harness.exceptions.findOverlappingForStaff(eq(BUSINESS), any(), any(), anyCollection()))
                .thenReturn(List.of(new ScheduleException(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ScheduleExceptionContent.businessClosureDays(DAY, DAY),
                        0,
                        AvailabilityQueryHarness.STORED_AT,
                        AvailabilityQueryHarness.STORED_AT)));

        assertSanitizedFailure(harness);
    }

    @Test
    void exceptionQueryReceivesTheExactThirtyDateWindow() {
        AvailabilityQueryHarness harness = harness();
        harness.calculate(null);

        verify(harness.exceptions).findOverlappingForStaff(
                eq(BUSINESS), eq(TODAY), eq(TODAY.plusDays(29)), anyCollection());
    }

    // ---- Busy windows ----

    @Test
    void busyWindowOverlapRemovesSlotsAndAdjacencyDoesNot() {
        AvailabilityQueryHarness harness = harness().service(60).busyWindows(Map.of(
                STAFF_A,
                List.of(new BusyWindow(local(SOFIA, DAY, "10:00"), local(SOFIA, DAY, "11:00")))));

        List<LocalTime> starts = localStarts(harness.calculate(null), DAY);

        assertThat(starts).contains(LocalTime.of(9, 0), LocalTime.of(11, 0));
        assertThat(starts).doesNotContain(
                LocalTime.of(9, 15), LocalTime.of(10, 0), LocalTime.of(10, 45));
    }

    @Test
    void busyWindowsAffectOnlyTheirOwnStaffMember() {
        AvailabilityQueryHarness harness = harness()
                .staff(everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "09:00", "18:00"))
                .busyWindows(Map.of(STAFF_A, List.of(new BusyWindow(
                        local(SOFIA, DAY, "09:00"), local(SOFIA, DAY, "18:00")))));

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(snapshot.slots()).filteredOn(
                slot -> slot.start().atZone(SOFIA).toLocalDate().equals(DAY))
                .isNotEmpty()
                .allSatisfy(slot -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_B));
    }

    @Test
    void busySourceReceivesACopyOfTheRequestedStaffMembersAndTheZoneRuleWindow() {
        AvailabilityQueryHarness harness = harness().staff(
                everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "09:00", "18:00"));
        harness.calculate(null);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(harness.busy).findBusyWindows(
                eq(BUSINESS), anyCollection(), from.capture(), to.capture());
        assertThat(busyQueryStaffIds(harness)).containsExactlyInAnyOrder(STAFF_A, STAFF_B);
        assertThat(from.getValue()).isEqualTo(local(SOFIA, TODAY, "00:00"));
        assertThat(to.getValue()).isEqualTo(local(SOFIA, TODAY.plusDays(30), "00:00"));
    }

    @Test
    void busySourceReturningNullBecomesASanitizedFailure() {
        AvailabilityQueryHarness harness = harness();
        when(harness.busy.findBusyWindows(any(), anyCollection(), any(), any())).thenReturn(null);

        assertSanitizedFailure(harness);
    }

    @Test
    void busySourceFailureBecomesASanitizedFailure() {
        AvailabilityQueryHarness harness = harness();
        when(harness.busy.findBusyWindows(any(), anyCollection(), any(), any()))
                .thenThrow(new IllegalStateException("SELECT secret FROM appointment"));

        assertSanitizedFailure(harness);
    }

    @Test
    void busyWindowsForAnUnrequestedStaffMemberAreRejected() {
        AvailabilityQueryHarness harness = harness().busyWindows(Map.of(
                UUID.randomUUID(),
                List.of(new BusyWindow(local(SOFIA, DAY, "10:00"), local(SOFIA, DAY, "11:00")))));

        assertSanitizedFailure(harness);
    }

    @Test
    void busyWindowsForAStaffMemberOutsideASpecificRequestAreRejected() {
        AvailabilityQueryHarness harness = harness()
                .staff(everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "09:00", "18:00"))
                .busyWindows(Map.of(STAFF_A, List.of(new BusyWindow(
                        local(SOFIA, DAY, "10:00"), local(SOFIA, DAY, "11:00")))));

        assertThatThrownBy(() -> harness.calculate(STAFF_B))
                .isInstanceOf(AvailabilityFailure.class);
    }

    @Test
    void busyWindowsWithANullKeyAreRejected() {
        Map<UUID, List<BusyWindow>> windows = new HashMap<>();
        windows.put(null, List.of());

        assertSanitizedFailure(harness().busyWindows(windows));
    }

    @Test
    void aNullBusyListOrWindowIsRejected() {
        Map<UUID, List<BusyWindow>> nullList = new HashMap<>();
        nullList.put(STAFF_A, null);
        assertSanitizedFailure(harness().busyWindows(nullList));

        List<BusyWindow> nullWindow = new ArrayList<>();
        nullWindow.add(null);
        assertSanitizedFailure(harness().busyWindows(Map.of(STAFF_A, nullWindow)));
    }

    @Test
    void unorderedBusyWindowsAreRejected() {
        BusyWindow early = new BusyWindow(local(SOFIA, DAY, "09:00"), local(SOFIA, DAY, "10:00"));
        BusyWindow late = new BusyWindow(local(SOFIA, DAY, "11:00"), local(SOFIA, DAY, "12:00"));

        assertSanitizedFailure(harness().busyWindows(Map.of(STAFF_A, List.of(late, early))));
    }

    @Test
    void equalStartWindowsMustBeOrderedByEnd() {
        Instant start = local(SOFIA, DAY, "09:00");
        BusyWindow shorter = new BusyWindow(start, local(SOFIA, DAY, "10:00"));
        BusyWindow longer = new BusyWindow(start, local(SOFIA, DAY, "11:00"));

        assertThat(harness().busyWindows(Map.of(STAFF_A, List.of(shorter, longer)))
                .calculate(null)).isNotNull();
        assertSanitizedFailure(harness().busyWindows(Map.of(STAFF_A, List.of(longer, shorter))));
    }

    @Test
    void emptyOrReversedBusyWindowsCannotBeConstructed() {
        Instant instant = local(SOFIA, DAY, "10:00");

        assertThatThrownBy(() -> new BusyWindow(instant, instant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusyWindow(instant, instant.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Failures are sanitized ----

    @ParameterizedTest
    @ValueSource(strings = {"business", "service", "staff", "exceptions"})
    void unexpectedAccessFailuresBecomeSanitizedFailures(String failing) {
        AvailabilityQueryHarness harness = harness();
        RuntimeException leak = new IllegalStateException(
                "SELECT * FROM business WHERE id = " + BUSINESS + " constraint x_fk");
        switch (failing) {
            case "business" -> when(harness.businesses.findScheduleContext(BUSINESS)).thenThrow(leak);
            case "service" -> when(harness.services.findBookableService(BUSINESS, SERVICE))
                    .thenThrow(new ServiceAvailabilityFailure(leak));
            case "staff" -> when(harness.staffMembers.findEligibleForService(BUSINESS, SERVICE))
                    .thenThrow(new StaffAvailabilityFailure(leak));
            default -> when(harness.exceptions.findOverlappingForStaff(
                    eq(BUSINESS), any(), any(), anyCollection()))
                    .thenThrow(new ScheduleExceptionPersistenceException.UnexpectedFailure(leak));
        }

        assertSanitizedFailure(harness);
    }

    // ---- Determinism, clock, immutability ----

    @Test
    void theClockIsReadExactlyOnce() {
        AvailabilityQueryHarness harness = harness().staff(
                everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "09:00", "18:00"));

        harness.calculate(null);

        assertThat(harness.clock.reads()).isEqualTo(1);
    }

    @Test
    void theClockIsReadOnceEvenWhenTheCalculationFails() {
        AvailabilityQueryHarness harness = harness().business(LifecycleStatus.DRAFT);

        assertThatThrownBy(() -> harness.calculate(null)).isInstanceOf(BusinessNotBookable.class);

        assertThat(harness.clock.reads()).isEqualTo(1);
    }

    @Test
    void repeatedCalculationsAndInputOrderGiveIdenticalResults() {
        AvailabilityQueryHarness harness = harness().staff(
                everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "10:00", "17:00"));
        AvailabilitySnapshot first = harness.calculate(null);
        AvailabilitySnapshot second = harness.calculate(null);
        harness.staff(everyDay(STAFF_B, "10:00", "17:00"), everyDay(STAFF_A, "09:00", "18:00"));
        AvailabilitySnapshot reversed = harness.calculate(null);

        assertThat(second).isEqualTo(first);
        assertThat(reversed).isEqualTo(first);
        assertThat(first.slots()).extracting(AvailabilitySlot::start).isSorted();
    }

    @Test
    void theBusinessTimezoneDecidesTheLocalToday() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant now = Instant.parse("2026-09-29T02:00:00Z");
        AvailabilityQueryHarness harness = new AvailabilityQueryHarness(newYork, now)
                .staff(everyDay(STAFF_A, "09:00", "18:00"));

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(snapshot.timezone()).isEqualTo(newYork);
        // 02:00Z is 22:00 on 28 September in New York.
        assertThat(snapshot.slots().getFirst().start().atZone(newYork).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 29));
        verify(harness.exceptions).findOverlappingForStaff(
                eq(BUSINESS), eq(LocalDate.of(2026, 9, 28)), eq(LocalDate.of(2026, 10, 27)),
                anyCollection());
    }

    @Test
    void publishedRecordsAreDeeplyImmutable() {
        AvailabilitySnapshot snapshot = harness().staff(
                everyDay(STAFF_A, "09:00", "18:00"), everyDay(STAFF_B, "09:00", "18:00"))
                .calculate(null);
        AvailabilitySlot slot = snapshot.slots().getFirst();

        assertThatThrownBy(() -> snapshot.slots().add(slot))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.slots().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> slot.staffMemberIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> slot.staffMemberIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void publishedRecordsCopyTheirInputs() {
        List<UUID> ids = new ArrayList<>(List.of(STAFF_A));
        Instant start = Instant.parse("2026-10-01T07:00:00Z");
        AvailabilitySlot slot = new AvailabilitySlot(
                start, start.plusSeconds(1800), ZoneOffset.ofHours(3), ids);
        List<AvailabilitySlot> slots = new ArrayList<>(List.of(slot));
        AvailabilitySnapshot snapshot = new AvailabilitySnapshot(
                SOFIA, NOW, Duration.ofMinutes(30), slots);

        ids.add(STAFF_B);
        slots.clear();

        assertThat(slot.staffMemberIds()).containsExactly(STAFF_A);
        assertThat(snapshot.slots()).containsExactly(slot);
    }

    @Test
    void publishedSlotRejectsUnorderedDuplicateOrEmptyStaffMemberIds() {
        Instant start = Instant.parse("2026-10-01T07:00:00Z");
        Instant end = start.plusSeconds(1800);
        ZoneOffset offset = ZoneOffset.ofHours(3);

        assertThatThrownBy(() -> new AvailabilitySlot(start, end, offset, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilitySlot(start, end, offset, List.of(STAFF_B, STAFF_A)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilitySlot(start, end, offset, List.of(STAFF_A, STAFF_A)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvailabilitySlot(end, start, offset, List.of(STAFF_A)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIdentifiersAreProgrammingErrorsNotAvailabilityFailures() {
        AvailabilityQueryService subject = harness().subject();

        assertThatThrownBy(() -> AvailabilityQueryHarness.inRepeatableReadTransaction(
                () -> subject.calculate(null, SERVICE, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AvailabilityQueryHarness.inRepeatableReadTransaction(
                () -> subject.calculate(BUSINESS, null, null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void weekdaysMapToTheRecurringPeriodOfTheirOwnDayOnly() {
        LocalDate wednesday = LocalDate.of(2026, 9, 30);
        assertThat(wednesday.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        AvailabilityQueryHarness harness = harness().staff(new EligibleStaffMember(STAFF_A, List.of(
                        period(DayOfWeek.WEDNESDAY, "09:00", "10:00"),
                        period(DayOfWeek.WEDNESDAY, "14:00", "15:00"))));

        AvailabilitySnapshot snapshot = harness.calculate(null);

        assertThat(localStarts(snapshot, wednesday)).containsExactly(
                LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30),
                LocalTime.of(14, 0), LocalTime.of(14, 15), LocalTime.of(14, 30));
        assertThat(localStarts(snapshot, wednesday.plusDays(1))).isEmpty();
    }

    // ---- transaction isolation precondition ----

    @Test
    void repeatableReadAndSerializableTransactionsAreAccepted() {
        for (int isolation : new int[] {
            Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE
        }) {
            AvailabilityQueryHarness harness = harness();

            AvailabilitySnapshot snapshot = AvailabilityQueryHarness.inTransactionState(
                    true, isolation, () -> harness.subject().calculate(BUSINESS, SERVICE, null));

            assertThat(snapshot.slots()).isNotEmpty();
        }
    }

    @Test
    void noTransactionAWeakerIsolationOrAnUnexposedIsolationFailBeforeAnyRead() {
        List<Integer> unacceptable = new ArrayList<>(List.of(
                Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED));
        unacceptable.add(null);
        for (Integer isolation : unacceptable) {
            assertRejectedBeforeAnyRead(true, isolation);
        }
        assertRejectedBeforeAnyRead(false, Connection.TRANSACTION_REPEATABLE_READ);
        assertRejectedBeforeAnyRead(false, null);
    }

    @Test
    void theIsolationFailureIsSanitizedAndExposesNoTransactionInternals() {
        AvailabilityQueryHarness harness = harness();

        assertThatThrownBy(() -> AvailabilityQueryHarness.inTransactionState(
                true,
                Connection.TRANSACTION_READ_COMMITTED,
                () -> harness.subject().calculate(BUSINESS, SERVICE, null)))
                .isInstanceOf(AvailabilityFailure.class)
                .hasMessage("Availability calculation failed")
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).doesNotContain(BUSINESS.toString());
                    assertThat(failure.getMessage()).doesNotContainIgnoringCase("isolation");
                    assertThat(failure.getMessage()).doesNotContainIgnoringCase("transaction");
                });
    }

    private void assertRejectedBeforeAnyRead(boolean active, Integer isolation) {
        AvailabilityQueryHarness harness = harness();

        assertThatThrownBy(() -> AvailabilityQueryHarness.inTransactionState(
                active, isolation, () -> harness.subject().calculate(BUSINESS, SERVICE, null)))
                .isInstanceOf(AvailabilityFailure.class);

        assertThat(harness.clock.reads()).isZero();
        verifyNoInteractions(
                harness.businesses,
                harness.services,
                harness.staffMembers,
                harness.exceptions,
                harness.busy);
    }

    // ---- helpers ----

    private static void assertSanitizedFailure(AvailabilityQueryHarness harness) {
        assertThatThrownBy(() -> harness.calculate(null))
                .isInstanceOf(AvailabilityFailure.class)
                .isInstanceOf(AvailabilityApplicationException.class)
                .hasMessage("Availability calculation failed")
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).doesNotContain(BUSINESS.toString());
                    assertThat(failure.getMessage()).doesNotContain(SERVICE.toString());
                    assertThat(failure.getMessage()).doesNotContain(STAFF_A.toString());
                    assertThat(failure.getMessage()).doesNotContainIgnoringCase("select");
                });
    }

    @SuppressWarnings("unchecked")
    private static Collection<UUID> exceptionQueryStaffIds(AvailabilityQueryHarness harness) {
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(harness.exceptions).findOverlappingForStaff(
                eq(BUSINESS), any(), any(), ids.capture());
        return ids.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Collection<UUID> busyQueryStaffIds(AvailabilityQueryHarness harness) {
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(harness.busy).findBusyWindows(eq(BUSINESS), ids.capture(), any(), any());
        return ids.getValue();
    }
}
