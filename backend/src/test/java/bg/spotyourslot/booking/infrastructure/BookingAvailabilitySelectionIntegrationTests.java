package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.SelectionTooLong;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.SelectionAvailability;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The real set-based availability (ADR-0027) over committed Business, Service, StaffMember,
 * assignment, and Appointment data: the intersection of assignments, the explicit StaffMember
 * rule, the rejected selections, and the committed busy time over the whole visit. The clock is
 * fixed and every date is explicit.
 */
@Import(BookingAvailabilitySelectionIntegrationTests.ClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BookingAvailabilitySelectionIntegrationTests extends PostgresIntegrationTest {
    /** Tuesday 11:00 in Sofia (UTC+3): the earliest candidate start is 13:00 today. */
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);

    @Autowired AvailabilityQuery availability;
    @Autowired AppointmentStore store;
    @Autowired JdbcClient jdbc;
    @Autowired MutableTestClock clock;
    @Autowired PlatformTransactionManager transactionManager;

    private AppointmentFixtures appointments;
    private AvailabilityFixtures fixtures;
    private Tenant tenant;
    private UUID second;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        appointments = new AppointmentFixtures(jdbc);
        fixtures = new AvailabilityFixtures(jdbc);
        tenant = appointments.tenant();
        second = appointments.service(tenant.business(), 30);
    }

    private UUID member(boolean active, String start, String end, UUID... services) {
        UUID id = fixtures.staffMember(tenant.business(), active);
        for (UUID service : services) {
            fixtures.assign(tenant.business(), id, service);
        }
        if (start != null) {
            fixtures.everyDay(tenant.business(), id, start, end);
        }
        return id;
    }

    private SelectionAvailability select(UUID staffOrNull, UUID... services) {
        return availability.calculate(tenant.business(), List.of(services), staffOrNull);
    }

    private static List<LocalTime> thursdayStarts(SelectionAvailability result) {
        return result.snapshot().slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(THURSDAY))
                .map(slot -> slot.start().atZone(SOFIA).toLocalTime())
                .toList();
    }

    // ---- assignment intersection ----

    @Test
    void onlyActiveStaffMembersAssignedToEveryServiceAreEligible() {
        UUID both = member(true, "09:00", "12:00", tenant.service(), second);
        member(true, "09:00", "12:00", tenant.service());
        member(true, "09:00", "12:00", second);
        member(false, "09:00", "12:00", tenant.service(), second);
        member(true, "09:00", "12:00");

        SelectionAvailability result = select(null, tenant.service(), second);

        assertThat(result.eligibleStaffMemberIds()).containsExactly(both);
        assertThat(result.snapshot().slots()).isNotEmpty()
                .allSatisfy(slot -> assertThat(slot.staffMemberIds()).containsExactly(both));
    }

    @Test
    void theUnionOfPartialAssignmentsIsNeverTheEligibleSet() {
        // Each Service has a StaffMember of its own, and nobody performs both.
        member(true, "09:00", "12:00", tenant.service());
        member(true, "09:00", "12:00", second);

        SelectionAvailability result = select(null, tenant.service(), second);

        assertThat(result.hasEligibleStaffMembers()).isFalse();
        assertThat(result.snapshot().slots()).isEmpty();
        // Each Service alone is still offered.
        assertThat(select(null, tenant.service()).snapshot().slots()).isNotEmpty();
        assertThat(select(null, second).snapshot().slots()).isNotEmpty();
    }

    @Test
    void noPreferenceListsEveryEligibleStaffMemberOfASlot() {
        UUID early = member(true, "09:00", "12:00", tenant.service(), second);
        UUID late = member(true, "11:00", "14:00", tenant.service(), second);

        SelectionAvailability result = select(null, tenant.service(), second);

        assertThat(result.eligibleStaffMemberIds()).containsExactlyInAnyOrder(early, late);
        List<AvailabilitySlot> thursday = result.snapshot().slots().stream()
                .filter(slot -> slot.start().atZone(SOFIA).toLocalDate().equals(THURSDAY))
                .toList();
        assertThat(thursday).filteredOn(slot -> slot.start().atZone(SOFIA).toLocalTime()
                        .equals(LocalTime.of(11, 0)))
                .singleElement()
                .satisfies(slot -> assertThat(slot.staffMemberIds())
                        .containsExactlyInAnyOrder(early, late));
        assertThat(thursday).filteredOn(slot -> slot.start().atZone(SOFIA).toLocalTime()
                        .equals(LocalTime.of(9, 0)))
                .singleElement()
                .satisfies(slot -> assertThat(slot.staffMemberIds()).containsExactly(early));
        assertThat(thursday).filteredOn(slot -> slot.start().atZone(SOFIA).toLocalTime()
                        .equals(LocalTime.of(13, 0)))
                .singleElement()
                .satisfies(slot -> assertThat(slot.staffMemberIds()).containsExactly(late));
    }

    @Test
    void eligibleStaffMembersWithoutTimeAreDistinctFromNobodyEligible() {
        UUID unscheduled = member(true, null, null, tenant.service(), second);

        SelectionAvailability result = select(null, tenant.service(), second);

        assertThat(result.hasEligibleStaffMembers()).isTrue();
        assertThat(result.eligibleStaffMemberIds()).containsExactly(unscheduled);
        assertThat(result.snapshot().slots()).isEmpty();
    }

    // ---- explicit StaffMember ----

    @Test
    void anExplicitStaffMemberMustSupportTheCompleteSelection() {
        UUID both = member(true, "09:00", "12:00", tenant.service(), second);
        UUID firstOnly = member(true, "09:00", "12:00", tenant.service());
        UUID inactive = member(false, "09:00", "12:00", tenant.service(), second);

        assertThat(select(both, tenant.service(), second).eligibleStaffMemberIds())
                .containsExactly(both);
        assertThat(select(both, tenant.service(), second).snapshot().slots()).isNotEmpty();
        // That StaffMember is fine for the one Service it is assigned to...
        assertThat(select(firstOnly, tenant.service()).eligibleStaffMemberIds()).containsExactly(firstOnly);
        // ...but not for the complete selection.
        assertThatThrownBy(() -> select(firstOnly, tenant.service(), second))
                .isInstanceOf(StaffMemberNotEligible.class);
        assertThatThrownBy(() -> select(inactive, tenant.service(), second))
                .isInstanceOf(StaffMemberNotEligible.class);
        assertThatThrownBy(() -> select(UUID.randomUUID(), tenant.service(), second))
                .isInstanceOf(StaffMemberNotEligible.class);
    }

    @Test
    void aForeignStaffMemberIsNotEligible() {
        Tenant other = appointments.tenant();
        UUID otherService = appointments.service(other.business(), 30);
        fixtures.assign(other.business(), other.staff(), otherService);
        member(true, "09:00", "12:00", tenant.service(), second);

        assertThatThrownBy(() -> select(other.staff(), tenant.service(), second))
                .isInstanceOf(StaffMemberNotEligible.class);
    }

    // ---- Service and Business validation ----

    @Test
    void missingForeignAndInactiveServicesMakeTheSelectionNotBookable() {
        member(true, "09:00", "12:00", tenant.service(), second);
        Tenant other = appointments.tenant();
        UUID inactive = fixtures.service(tenant.business(), 30, false);

        assertThatThrownBy(() -> select(null, tenant.service(), UUID.randomUUID()))
                .isInstanceOf(ServiceNotBookable.class);
        assertThatThrownBy(() -> select(null, tenant.service(), other.service()))
                .isInstanceOf(ServiceNotBookable.class);
        assertThatThrownBy(() -> select(null, tenant.service(), inactive))
                .isInstanceOf(ServiceNotBookable.class);
        // A deactivated Service is as unbookable as one that never existed.
        fixtures.setServiceActive(second, false);
        assertThatThrownBy(() -> select(null, tenant.service(), second))
                .isInstanceOf(ServiceNotBookable.class);
    }

    @Test
    void aSuspendedOrDraftBusinessIsNotBookable() {
        member(true, "09:00", "12:00", tenant.service(), second);
        fixtures.setBusinessStatus(tenant.business(), "SUSPENDED");
        assertThatThrownBy(() -> select(null, tenant.service(), second))
                .isInstanceOf(BusinessNotBookable.class);
        fixtures.setBusinessStatus(tenant.business(), "DRAFT");
        assertThatThrownBy(() -> select(null, tenant.service(), second))
                .isInstanceOf(BusinessNotBookable.class);
    }

    @Test
    void emptyDuplicateAndOversizedSelectionsAreCallerErrors() {
        List<UUID> six = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            six.add(appointments.service(tenant.business(), 30));
        }

        assertThatThrownBy(() -> availability.calculate(tenant.business(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> availability.calculate(
                tenant.business(), List.of(tenant.service(), tenant.service()), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> availability.calculate(tenant.business(), six, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(availability.calculate(tenant.business(), six.subList(0, 5), null)
                .snapshot().occupiedDuration()).isEqualTo(Duration.ofMinutes(150));
    }

    // ---- count and duration boundaries ----

    @Test
    void fiveServicesTotalingFourHundredEightyMinutesAreAcceptedAndOneMoreMinuteIsTooLong() {
        List<UUID> accepted = new ArrayList<>();
        List<UUID> tooLong = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            accepted.add(appointments.service(tenant.business(), 96));
            tooLong.add(appointments.service(tenant.business(), index == 4 ? 97 : 96));
        }
        // One StaffMember performs all ten Services, so each selection has an eligible member.
        UUID everything = fixtures.staffMember(tenant.business(), true);
        for (UUID service : concat(accepted, tooLong)) {
            fixtures.assign(tenant.business(), everything, service);
        }
        fixtures.everyDay(tenant.business(), everything, "00:00", "23:59");

        SelectionAvailability fits = availability.calculate(tenant.business(), accepted, null);

        assertThat(fits.snapshot().occupiedDuration()).isEqualTo(Duration.ofMinutes(480));
        assertThat(fits.snapshot().slots()).isNotEmpty();
        assertThatThrownBy(() -> availability.calculate(tenant.business(), tooLong, null))
                .isInstanceOf(SelectionTooLong.class);
    }

    // ---- committed busy time over the whole visit ----

    @Test
    void aCommittedAppointmentBlocksEveryStartWhoseWholeVisitOverlapsIt() {
        UUID staff = member(true, "09:00", "12:00", tenant.service(), second);
        Instant busyStart = THURSDAY.atTime(LocalTime.of(10, 0)).atZone(SOFIA).toInstant();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                store.insert(AppointmentFixtures.online(
                        new Tenant(tenant.business(), tenant.service(), staff, tenant.customer()),
                        busyStart, 30)));

        List<LocalTime> starts = thursdayStarts(select(staff, tenant.service(), second));

        // The visit is 60 minutes; the booked time is 10:00-10:30. 09:00 ends exactly at 10:00 and
        // 10:30 starts exactly at 10:30 (half-open, adjacent). Everything between overlaps it.
        assertThat(starts).containsExactly(
                LocalTime.of(9, 0), LocalTime.of(10, 30), LocalTime.of(10, 45), LocalTime.of(11, 0));
    }

    // ---- unchanged single-Service behavior ----

    @Test
    void theOneServiceMethodEqualsTheOneServiceSelection() {
        member(true, "09:00", "12:00", tenant.service());

        assertThat(availability.calculate(tenant.business(), tenant.service(), null))
                .isEqualTo(select(null, tenant.service()).snapshot());
    }

    private static List<UUID> concat(List<UUID> first, List<UUID> second) {
        List<UUID> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableTestClock bookingAvailabilitySelectionTestClock() {
            return new MutableTestClock(NOW);
        }
    }
}
