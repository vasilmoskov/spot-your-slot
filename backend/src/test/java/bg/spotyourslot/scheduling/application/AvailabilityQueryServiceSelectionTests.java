package bg.spotyourslot.scheduling.application;

import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.BUSINESS;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.SERVICE;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.SOFIA;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.STAFF_A;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.STAFF_B;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.everyDay;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.local;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.localStarts;
import static bg.spotyourslot.scheduling.application.AvailabilityQueryHarness.withoutSchedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.AvailabilityFailure;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.SelectionTooLong;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.SelectionAvailability;
import bg.spotyourslot.scheduling.BusyIntervalSource.BusyWindow;
import bg.spotyourslot.workforce.StaffAvailabilityAccess.EligibleStaffMember;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * The set-based calculation (ADR-0027) over mocked published contracts and the real pure engine.
 * Every date and instant is explicit; the clock is the harness' controlled clock.
 */
class AvailabilityQueryServiceSelectionTests {
    private static final UUID SECOND = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("20000000-0000-0000-0000-000000000003");
    /** Tuesday 11:00 in Sofia (UTC+3). */
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);

    private static BookableService bookable(UUID id, int minutes) {
        return new BookableService(id, Duration.ofMinutes(minutes));
    }

    private static AvailabilityQueryHarness harness(
            Instant now, String start, String end, BookableService... catalog) {
        return new AvailabilityQueryHarness(SOFIA, now)
                .services(catalog)
                .staff(everyDay(STAFF_A, start, end));
    }

    private static SelectionAvailability select(
            AvailabilityQueryHarness harness, Collection<UUID> serviceIds, UUID staffOrNull) {
        return AvailabilityQueryHarness.inRepeatableReadTransaction(
                () -> harness.subject().calculate(BUSINESS, serviceIds, staffOrNull));
    }

    // ---- summed duration, one engine call ----

    @Test
    void theOccupiedDurationIsTheSumAndEverySlotSpansIt() {
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "18:00", bookable(SERVICE, 30), bookable(SECOND, 45));

        SelectionAvailability result = select(harness, List.of(SERVICE, SECOND), null);

        assertThat(result.snapshot().occupiedDuration()).isEqualTo(Duration.ofMinutes(75));
        assertThat(result.snapshot().slots()).allSatisfy(slot ->
                assertThat(Duration.between(slot.start(), slot.end())).isEqualTo(Duration.ofMinutes(75)));
        List<LocalTime> starts = localStarts(result.snapshot(), FRIDAY);
        assertThat(starts).first().isEqualTo(LocalTime.of(9, 0));
        // 18:00 minus 75 minutes is the last start on the 15-minute grid.
        assertThat(starts).last().isEqualTo(LocalTime.of(16, 45));
        assertThat(starts).allSatisfy(start -> assertThat(start.getMinute() % 15).isZero());
    }

    @Test
    void theOrderOfTheRequestedIdentifiersDoesNotChangeTheResult() {
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "18:00", bookable(SERVICE, 30), bookable(SECOND, 45), bookable(THIRD, 15));

        SelectionAvailability forward = select(harness, List.of(SERVICE, SECOND, THIRD), null);
        SelectionAvailability reversed = select(harness, List.of(THIRD, SECOND, SERVICE), null);

        assertThat(reversed).isEqualTo(forward);
    }

    @Test
    void anIntervalThatFitsEachServiceButNotTheirCombinedDurationOffersNothing() {
        // Working 09:00-10:00: each Service fits alone, thirty plus forty-five minutes does not.
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "10:00", bookable(SERVICE, 30), bookable(SECOND, 45));

        assertThat(localStarts(select(harness, List.of(SERVICE), null).snapshot(), FRIDAY))
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30));
        assertThat(localStarts(select(harness, List.of(SECOND), null).snapshot(), FRIDAY))
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 15));

        SelectionAvailability combined = select(harness, List.of(SERVICE, SECOND), null);

        assertThat(combined.snapshot().slots()).isEmpty();
        // Eligible StaffMembers exist; they only have no available time for the whole visit.
        assertThat(combined.hasEligibleStaffMembers()).isTrue();
        assertThat(combined.eligibleStaffMemberIds()).containsExactly(STAFF_A);
    }

    @Test
    void theLastIntervalThatJustFitsTheCombinedDurationIsOffered() {
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "10:30", bookable(SERVICE, 30), bookable(SECOND, 45));

        assertThat(localStarts(select(harness, List.of(SERVICE, SECOND), null).snapshot(), FRIDAY))
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 15));
    }

    // ---- busy intervals over the whole visit, half-open adjacency ----

    @Test
    void aBusyIntervalCoveringAnyPartOfTheWholeVisitBlocksItsStart() {
        // Visit of 60 minutes, busy 10:00-10:30 inside a 09:00-12:00 working period.
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "12:00", bookable(SERVICE, 30), bookable(SECOND, 30))
                .busyWindows(Map.of(STAFF_A, List.of(
                        new BusyWindow(local(SOFIA, FRIDAY, "10:00"), local(SOFIA, FRIDAY, "10:30")))));

        List<LocalTime> starts = localStarts(select(harness, List.of(SERVICE, SECOND), null).snapshot(), FRIDAY);

        // 09:00 ends exactly where the busy interval starts (adjacent, allowed). 09:15, 09:30,
        // and 09:45 overlap its head; 10:00 and 10:15 start inside it; 10:30 starts exactly where
        // it ends (adjacent, allowed).
        assertThat(starts).containsExactly(
                LocalTime.of(9, 0), LocalTime.of(10, 30), LocalTime.of(10, 45), LocalTime.of(11, 0));
    }

    @Test
    void aBusyIntervalInsideTheSecondServicePartAlsoBlocks() {
        // The busy interval lies entirely in the second half of a visit starting 09:00.
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "12:00", bookable(SERVICE, 60), bookable(SECOND, 60))
                .busyWindows(Map.of(STAFF_A, List.of(
                        new BusyWindow(local(SOFIA, FRIDAY, "10:15"), local(SOFIA, FRIDAY, "10:45")))));

        List<LocalTime> starts = localStarts(select(harness, List.of(SERVICE, SECOND), null).snapshot(), FRIDAY);

        // The first Service alone could start at 09:00; the whole visit ending 11:00 cannot.
        assertThat(starts).isEmpty();
    }

    @Test
    void halfOpenAdjacencyOnBothSidesOfTheVisitIsAllowed() {
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "18:00", bookable(SERVICE, 30), bookable(SECOND, 30))
                .busyWindows(Map.of(STAFF_A, List.of(
                        new BusyWindow(local(SOFIA, FRIDAY, "10:00"), local(SOFIA, FRIDAY, "11:00")))));

        List<LocalTime> starts = localStarts(select(harness, List.of(SERVICE, SECOND), null).snapshot(), FRIDAY);

        assertThat(starts).contains(LocalTime.of(9, 0), LocalTime.of(11, 0));
        assertThat(starts).doesNotContain(
                LocalTime.of(9, 15), LocalTime.of(10, 0), LocalTime.of(10, 45));
    }

    // ---- DST: elapsed time, repeated and skipped hours ----

    @Test
    void aVisitAcrossTheRepeatedAutumnHourKeepsItsElapsedLengthAndBothOccurrences() {
        ZoneOffsetTransition overlap = SOFIA.getRules().nextTransition(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(overlap.isOverlap()).isTrue();
        LocalDate day = overlap.getDateTimeAfter().toLocalDate();
        assertThat(day).isEqualTo(LocalDate.of(2026, 10, 25));
        Instant now = Instant.parse("2026-10-20T08:00:00Z");
        AvailabilityQueryHarness harness = harness(
                now, "00:00", "08:00", bookable(SERVICE, 45), bookable(SECOND, 45));

        List<AvailabilitySlot> slots = select(harness, List.of(SERVICE, SECOND), null)
                .snapshot().slots().stream()
                .filter(slot -> slot.start().atZone(slot.startOffset()).toLocalDate().equals(day))
                .toList();

        assertThat(slots).allSatisfy(slot ->
                assertThat(Duration.between(slot.start(), slot.end())).isEqualTo(Duration.ofMinutes(90)));
        // 03:30 happens twice; each occurrence is a distinct slot with its own offset.
        List<AvailabilitySlot> repeated = slots.stream()
                .filter(slot -> slot.start().atOffset(slot.startOffset()).toLocalTime()
                        .equals(LocalTime.of(3, 30)))
                .toList();
        assertThat(repeated).hasSize(2);
        assertThat(repeated.get(0).startOffset()).isEqualTo(overlap.getOffsetBefore());
        assertThat(repeated.get(1).startOffset()).isEqualTo(overlap.getOffsetAfter());
        // The visit starting at the first 03:30 ends at the later 04:00 (an hour is repeated),
        // 90 elapsed minutes after it began, not 90 wall-clock minutes.
        assertThat(repeated.get(0).end().atZone(SOFIA).toLocalTime()).isEqualTo(LocalTime.of(4, 0));
        assertThat(repeated.get(0).end().atZone(SOFIA).getOffset()).isEqualTo(overlap.getOffsetAfter());
        // The working period ends 08:00 (UTC+2): the last start is 90 elapsed minutes earlier.
        assertThat(slots.getLast().start().atZone(SOFIA).toLocalTime()).isEqualTo(LocalTime.of(6, 30));
        assertThat(slots).extracting(AvailabilitySlot::start).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void aVisitAcrossTheSkippedSpringHourIsMeasuredInElapsedTime() {
        ZoneOffsetTransition gap = SOFIA.getRules().nextTransition(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(gap.isGap()).isTrue();
        LocalDate day = gap.getDateTimeBefore().toLocalDate();
        assertThat(day).isEqualTo(LocalDate.of(2027, 3, 28));
        Instant now = Instant.parse("2027-03-23T08:00:00Z");
        // Working 00:00-04:30 local; 04:30 is after the gap (UTC+3).
        AvailabilityQueryHarness harness = harness(
                now, "00:00", "04:30", bookable(SERVICE, 45), bookable(SECOND, 45));

        List<AvailabilitySlot> slots = select(harness, List.of(SERVICE, SECOND), null)
                .snapshot().slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(day))
                .toList();

        assertThat(slots).allSatisfy(slot ->
                assertThat(Duration.between(slot.start(), slot.end())).isEqualTo(Duration.ofMinutes(90)));
        // 02:00 (UTC+2) + 90 elapsed minutes = 04:30 (UTC+3): the last visit that fits. The
        // wall-clock gap hour does not shorten or lengthen it.
        ZonedDateTime last = slots.getLast().start().atZone(SOFIA);
        assertThat(last.toLocalTime()).isEqualTo(LocalTime.of(2, 0));
        assertThat(slots.getLast().end().atZone(SOFIA).toLocalTime()).isEqualTo(LocalTime.of(4, 30));
        // No start inside the skipped 03:00-04:00 hour exists.
        assertThat(slots).noneMatch(slot -> {
            LocalTime start = slot.start().atZone(SOFIA).toLocalTime();
            return !start.isBefore(LocalTime.of(3, 0)) && start.isBefore(LocalTime.of(4, 0));
        });
        // The one before it started 15 minutes earlier and also fits.
        assertThat(localStarts(select(harness, List.of(SERVICE, SECOND), null).snapshot(), day))
                .contains(LocalTime.of(1, 45), LocalTime.of(2, 0))
                .doesNotContain(LocalTime.of(2, 15));
    }

    // ---- eligibility: intersection is the workforce contract, aggregation is here ----

    @Test
    void noPreferenceAggregatesEveryEligibleStaffMemberPerSlotInStableOrder() {
        AvailabilityQueryHarness harness = new AvailabilityQueryHarness(SOFIA, NOW)
                .services(bookable(SERVICE, 30), bookable(SECOND, 30))
                .staff(everyDay(STAFF_B, "09:00", "12:00"), everyDay(STAFF_A, "11:00", "14:00"));

        SelectionAvailability result = select(harness, List.of(SERVICE, SECOND), null);

        assertThat(result.eligibleStaffMemberIds()).containsExactlyInAnyOrder(STAFF_A, STAFF_B);
        for (AvailabilitySlot slot : result.snapshot().slots()) {
            if (!slot.start().atZone(SOFIA).toLocalDate().equals(FRIDAY)) {
                continue;
            }
            LocalTime start = slot.start().atZone(SOFIA).toLocalTime();
            if (start.isBefore(LocalTime.of(11, 0))) {
                assertThat(slot.staffMemberIds()).containsExactly(STAFF_B);
            } else if (start.isAfter(LocalTime.of(11, 0))) {
                assertThat(slot.staffMemberIds()).containsExactly(STAFF_A);
            } else {
                assertThat(slot.staffMemberIds()).containsExactly(STAFF_A, STAFF_B);
            }
        }
    }

    @Test
    void anExplicitStaffMemberNarrowsEveryReadToThatMemberOnly() {
        AvailabilityQueryHarness harness = new AvailabilityQueryHarness(SOFIA, NOW)
                .services(bookable(SERVICE, 30), bookable(SECOND, 30))
                .staff(everyDay(STAFF_A, "09:00", "12:00"), everyDay(STAFF_B, "09:00", "12:00"));

        SelectionAvailability result = select(harness, List.of(SERVICE, SECOND), STAFF_B);

        assertThat(result.eligibleStaffMemberIds()).containsExactly(STAFF_B);
        assertThat(result.snapshot().slots()).isNotEmpty()
                .allSatisfy(slot -> assertThat(slot.staffMemberIds()).containsExactly(STAFF_B));
        ArgumentCaptor<Collection<UUID>> busyStaff = ArgumentCaptor.captor();
        verify(harness.busy).findBusyWindows(eq(BUSINESS), busyStaff.capture(), any(), any());
        assertThat(busyStaff.getValue()).containsExactly(STAFF_B);
    }

    @Test
    void anExplicitStaffMemberOutsideTheEligibleIntersectionIsNotEligible() {
        // The workforce contract returns only members assigned to every Service; this one is absent.
        AvailabilityQueryHarness harness = new AvailabilityQueryHarness(SOFIA, NOW)
                .services(bookable(SERVICE, 30), bookable(SECOND, 30))
                .staff(everyDay(STAFF_A, "09:00", "12:00"));

        assertThatThrownBy(() -> select(harness, List.of(SERVICE, SECOND), STAFF_B))
                .isInstanceOf(StaffMemberNotEligible.class);
        verifyNoInteractions(harness.exceptions, harness.busy);
    }

    @Test
    void anExplicitStaffMemberWhenNobodyIsEligibleIsNotEligible() {
        AvailabilityQueryHarness harness = new AvailabilityQueryHarness(SOFIA, NOW)
                .services(bookable(SERVICE, 30), bookable(SECOND, 30))
                .staff();

        assertThatThrownBy(() -> select(harness, List.of(SERVICE, SECOND), STAFF_A))
                .isInstanceOf(StaffMemberNotEligible.class);
    }

    @Test
    void nobodySupportingTheSelectionIsDistinctFromEligibleStaffWithoutTime() {
        AvailabilityQueryHarness nobody = new AvailabilityQueryHarness(SOFIA, NOW)
                .services(bookable(SERVICE, 30), bookable(SECOND, 30))
                .staff();
        AvailabilityQueryHarness noTime = new AvailabilityQueryHarness(SOFIA, NOW)
                .services(bookable(SERVICE, 30), bookable(SECOND, 30))
                .staff(withoutSchedule(STAFF_A));

        SelectionAvailability none = select(nobody, List.of(SERVICE, SECOND), null);
        SelectionAvailability scheduled = select(noTime, List.of(SERVICE, SECOND), null);

        assertThat(none.hasEligibleStaffMembers()).isFalse();
        assertThat(none.eligibleStaffMemberIds()).isEmpty();
        assertThat(none.snapshot().slots()).isEmpty();
        assertThat(none.snapshot().occupiedDuration()).isEqualTo(Duration.ofMinutes(60));
        // Nothing is read for an empty set of StaffMembers.
        verifyNoInteractions(nobody.exceptions, nobody.busy);

        assertThat(scheduled.hasEligibleStaffMembers()).isTrue();
        assertThat(scheduled.eligibleStaffMemberIds()).containsExactly(STAFF_A);
        assertThat(scheduled.snapshot().slots()).isEmpty();
    }

    // ---- selection validation ----

    @Test
    void anEmptySelectionIsACallerErrorBeforeAnyClockOrDataRead() {
        AvailabilityQueryHarness harness = harness(NOW, "09:00", "18:00", bookable(SERVICE, 30));

        assertThatThrownBy(() -> select(harness, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(harness.clock.reads()).isZero();
        verifyNoInteractions(harness.businesses, harness.services, harness.staffMembers);
    }

    @Test
    void aDuplicatedServiceIsACallerErrorBeforeAnyClockOrDataRead() {
        AvailabilityQueryHarness harness = harness(NOW, "09:00", "18:00", bookable(SERVICE, 30));

        assertThatThrownBy(() -> select(harness, List.of(SERVICE, SERVICE), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(harness.clock.reads()).isZero();
        verifyNoInteractions(harness.businesses, harness.services, harness.staffMembers);
    }

    @Test
    void moreThanFiveServicesIsACallerErrorBeforeAnyClockOrDataRead() {
        AvailabilityQueryHarness harness = harness(NOW, "09:00", "18:00", bookable(SERVICE, 30));
        List<UUID> six = distinct(6);

        assertThatThrownBy(() -> select(harness, six, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(harness.clock.reads()).isZero();
        verifyNoInteractions(harness.businesses, harness.services, harness.staffMembers);
    }

    @Test
    void aNullSelectionOrElementIsAProgrammingError() {
        AvailabilityQueryHarness harness = harness(NOW, "09:00", "18:00", bookable(SERVICE, 30));
        List<UUID> withNull = new ArrayList<>();
        withNull.add(SERVICE);
        withNull.add(null);

        assertThatThrownBy(() -> select(harness, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> select(harness, withNull, null)).isInstanceOf(NullPointerException.class);
        assertThat(harness.clock.reads()).isZero();
    }

    @Test
    void oneToFiveDistinctServicesAreAcceptedAtTheCountBoundaries() {
        List<UUID> ids = distinct(5);
        BookableService[] catalog = ids.stream().map(id -> bookable(id, 30)).toArray(BookableService[]::new);
        AvailabilityQueryHarness harness = harness(NOW, "09:00", "18:00", catalog);

        assertThat(select(harness, ids.subList(0, 1), null).snapshot().occupiedDuration())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(select(harness, ids, null).snapshot().occupiedDuration())
                .isEqualTo(Duration.ofMinutes(150));
    }

    // ---- Service validation and the 480-minute ceiling ----

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void aMissingForeignOrInactiveServiceMakesTheWholeSelectionNotBookable(int missingIndex) {
        // The Catalog returns only the bookable Services; the absent one is missing, foreign, or inactive.
        List<UUID> ids = List.of(SERVICE, SECOND);
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "18:00", bookable(ids.get(1 - missingIndex), 30));

        assertThatThrownBy(() -> select(harness, ids, null)).isInstanceOf(ServiceNotBookable.class);

        verifyNoInteractions(harness.staffMembers, harness.exceptions, harness.busy);
    }

    @Test
    void theCatalogIsReadOnceForTheWholeSelectionAndNeverPerService() {
        List<UUID> ids = distinct(5);
        BookableService[] catalog = ids.stream().map(id -> bookable(id, 30)).toArray(BookableService[]::new);
        AvailabilityQueryHarness harness = harness(NOW, "09:00", "18:00", catalog);

        select(harness, ids, null);

        ArgumentCaptor<Collection<UUID>> requested = ArgumentCaptor.captor();
        verify(harness.services).findBookableServices(eq(BUSINESS), requested.capture());
        assertThat(Set.copyOf(requested.getValue())).isEqualTo(Set.copyOf(ids));
        verify(harness.services, never()).findBookableService(
                any(), any());
        ArgumentCaptor<Collection<UUID>> eligible = ArgumentCaptor.captor();
        verify(harness.staffMembers).findEligibleForServices(eq(BUSINESS), eligible.capture());
        assertThat(Set.copyOf(eligible.getValue())).isEqualTo(Set.copyOf(ids));
        verify(harness.staffMembers, never()).findEligibleForService(
                any(), any());
    }

    @Test
    void aVisitOfExactlyFourHundredEightyMinutesIsAccepted() {
        List<UUID> ids = distinct(5);
        BookableService[] catalog = ids.stream().map(id -> bookable(id, 96)).toArray(BookableService[]::new);
        AvailabilityQueryHarness harness = harness(NOW, "00:00", "23:59", catalog);

        SelectionAvailability result = select(harness, ids, null);

        assertThat(result.snapshot().occupiedDuration()).isEqualTo(Duration.ofMinutes(480));
        assertThat(result.snapshot().slots()).isNotEmpty();
    }

    @Test
    void aVisitOfFourHundredEightyOneMinutesIsTooLong() {
        List<UUID> ids = distinct(5);
        BookableService[] catalog = new BookableService[5];
        for (int index = 0; index < 5; index++) {
            catalog[index] = bookable(ids.get(index), index == 4 ? 97 : 96);
        }
        AvailabilityQueryHarness harness = harness(NOW, "00:00", "23:59", catalog);

        assertThatThrownBy(() -> select(harness, ids, null)).isInstanceOf(SelectionTooLong.class);

        verifyNoInteractions(harness.staffMembers, harness.exceptions, harness.busy);
    }

    @Test
    void aMissingServiceIsReportedBeforeAnOversizedSum() {
        List<UUID> ids = List.of(SERVICE, SECOND, THIRD);
        AvailabilityQueryHarness harness = harness(
                NOW, "00:00", "23:59", bookable(SERVICE, 300), bookable(SECOND, 300));

        assertThatThrownBy(() -> select(harness, ids, null)).isInstanceOf(ServiceNotBookable.class);
    }

    @Test
    void anOversizedSumIsTooLongWhateverTheStaffMemberRequest() {
        AvailabilityQueryHarness harness = harness(
                NOW, "00:00", "23:59", bookable(SERVICE, 300), bookable(SECOND, 300));

        assertThatThrownBy(() -> select(harness, List.of(SERVICE, SECOND), STAFF_A))
                .isInstanceOf(SelectionTooLong.class);
    }

    @Test
    void aCorruptDurationAmongTheServicesIsASanitizedFailure() {
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "18:00",
                bookable(SERVICE, 30), new BookableService(SECOND, Duration.ofSeconds(90)));

        assertThatThrownBy(() -> select(harness, List.of(SERVICE, SECOND), null))
                .isInstanceOf(AvailabilityFailure.class)
                .hasMessage("Availability calculation failed");
    }

    @Test
    void anUnrequestedOrRepeatedServiceFromTheCatalogIsASanitizedFailure() {
        AvailabilityQueryHarness unrequested = harness(
                NOW, "09:00", "18:00", bookable(SERVICE, 30), bookable(THIRD, 30));
        doReturn(List.of(bookable(SERVICE, 30), bookable(THIRD, 30)))
                .when(unrequested.services).findBookableServices(eq(BUSINESS), anyCollection());
        AvailabilityQueryHarness repeated = harness(NOW, "09:00", "18:00");
        doReturn(List.of(bookable(SERVICE, 30), bookable(SERVICE, 30)))
                .when(repeated.services).findBookableServices(eq(BUSINESS), anyCollection());

        assertThatThrownBy(() -> select(unrequested, List.of(SERVICE, SECOND), null))
                .isInstanceOf(AvailabilityFailure.class);
        assertThatThrownBy(() -> select(repeated, List.of(SERVICE, SECOND), null))
                .isInstanceOf(AvailabilityFailure.class);
    }

    // ---- unchanged single-Service behavior and clock ----

    @Test
    void theOneServiceMethodDelegatesAndReturnsTheSameSnapshot() {
        AvailabilityQueryHarness harness = harness(NOW, "09:00", "18:00", bookable(SERVICE, 30));

        SelectionAvailability viaSelection = select(harness, List.of(SERVICE), null);

        assertThat(harness.calculate(null)).isEqualTo(viaSelection.snapshot());
        assertThat(viaSelection.snapshot().occupiedDuration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void theClockIsReadExactlyOnceForASelection() {
        AvailabilityQueryHarness harness = harness(
                NOW, "09:00", "18:00", bookable(SERVICE, 30), bookable(SECOND, 30));

        select(harness, List.of(SERVICE, SECOND), null);

        assertThat(harness.clock.reads()).isEqualTo(1);
    }

    private static List<UUID> distinct(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ids.add(new UUID(0x2000000000000000L, 0x100L + index));
        }
        return ids;
    }
}
