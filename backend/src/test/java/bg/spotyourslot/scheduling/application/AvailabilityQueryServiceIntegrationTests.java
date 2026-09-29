package bg.spotyourslot.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/** Full assembly of committed Business, Service, StaffMember, schedule, and V9 exception data. */
@Import(AvailabilityQueryServiceIntegrationTests.ClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AvailabilityQueryServiceIntegrationTests extends PostgresIntegrationTest {
    /** Tuesday 11:00 in Sofia (UTC+3), so the earliest candidate start is 13:00. */
    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    private static final Instant STORED_AT = Instant.parse("2026-09-29T08:00:00Z");

    @Autowired AvailabilityQuery availability;
    @Autowired ScheduleExceptionStore exceptions;
    @Autowired JdbcClient jdbc;
    @Autowired MutableTestClock clock;

    private AvailabilityFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AvailabilityFixtures(jdbc);
        clock.set(NOW);
        clock.resetReads();
    }

    private Tenant tenant(String status, int minutes) {
        UUID business = fixtures.business(status, "Europe/Sofia");
        return new Tenant(business, fixtures.service(business, minutes, true));
    }

    private UUID staff(Tenant tenant, String start, String end) {
        UUID staff = fixtures.staffMember(tenant.business(), true);
        fixtures.assign(tenant.business(), staff, tenant.service());
        fixtures.everyDay(tenant.business(), staff, start, end);
        return staff;
    }

    private AvailabilitySnapshot calculate(Tenant tenant, UUID staffOrNull) {
        return availability.calculate(tenant.business(), tenant.service(), staffOrNull);
    }

    private void store(UUID business, ScheduleExceptionContent content) {
        exceptions.insert(new NewScheduleException(UUID.randomUUID(), business, content, STORED_AT));
    }

    private static LocalPeriod period(String start, String end) {
        return new LocalPeriod(LocalTime.parse(start), LocalTime.parse(end));
    }

    private static List<LocalTime> starts(AvailabilitySnapshot snapshot, LocalDate date) {
        return snapshot.slots().stream()
                .map(slot -> slot.start().atZone(snapshot.timezone()))
                .filter(start -> start.toLocalDate().equals(date))
                .map(start -> start.toLocalTime())
                .toList();
    }

    private static List<LocalDate> dates(AvailabilitySnapshot snapshot) {
        return snapshot.slots().stream()
                .map(slot -> slot.start().atZone(snapshot.timezone()).toLocalDate())
                .distinct()
                .toList();
    }

    // ---- assembly ----

    @Test
    void assemblesRecurringSplitPeriodsServiceDurationTimezoneAndHorizon() {
        Tenant tenant = tenant("ACTIVE", 60);
        UUID staff = fixtures.staffMember(tenant.business(), true);
        fixtures.assign(tenant.business(), staff, tenant.service());
        for (DayOfWeek day : DayOfWeek.values()) {
            fixtures.period(tenant.business(), staff, day, "09:00", "13:00");
            fixtures.period(tenant.business(), staff, day, "14:00", "18:00");
        }

        AvailabilitySnapshot snapshot = calculate(tenant, null);

        assertThat(snapshot.timezone()).isEqualTo(SOFIA);
        assertThat(snapshot.calculatedAt()).isEqualTo(NOW);
        assertThat(snapshot.occupiedDuration()).isEqualTo(Duration.ofMinutes(60));
        // 13:00 would end in the gap between the split periods, so today starts at 14:00.
        assertThat(starts(snapshot, TODAY)).first().isEqualTo(LocalTime.of(14, 0));
        assertThat(starts(snapshot, THURSDAY)).containsExactlyElementsOf(
                times("09:00", "09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45",
                        "11:00", "11:15", "11:30", "11:45", "12:00",
                        "14:00", "14:15", "14:30", "14:45", "15:00", "15:15", "15:30", "15:45",
                        "16:00", "16:15", "16:30", "16:45", "17:00"));
        assertThat(dates(snapshot)).hasSize(30);
        assertThat(dates(snapshot).getFirst()).isEqualTo(TODAY);
        assertThat(dates(snapshot).getLast()).isEqualTo(TODAY.plusDays(29));
        assertThat(snapshot.slots()).allSatisfy(slot -> assertThat(slot.staffMemberIds())
                .containsExactly(staff));
    }

    @Test
    void takesTheTimezoneFromTheStoredBusiness() {
        UUID business = fixtures.business("ACTIVE", "America/New_York");
        UUID service = fixtures.service(business, 30, true);
        UUID staff = fixtures.staffMember(business, true);
        fixtures.assign(business, staff, service);
        fixtures.everyDay(business, staff, "09:00", "18:00");

        AvailabilitySnapshot snapshot = availability.calculate(business, service, null);

        ZoneId newYork = ZoneId.of("America/New_York");
        assertThat(snapshot.timezone()).isEqualTo(newYork);
        // 08:00Z is 04:00 in New York, so the earliest start is 06:00 and the first slot is 09:00.
        assertThat(snapshot.slots().getFirst().start().atZone(newYork).toLocalTime())
                .isEqualTo(LocalTime.of(9, 0));
    }

    // ---- lifecycle and eligibility ----

    @Test
    void missingDraftAndSuspendedBusinessesAreIndistinguishable() {
        Tenant draft = tenant("DRAFT", 30);
        staff(draft, "09:00", "18:00");
        Tenant suspended = tenant("SUSPENDED", 30);
        staff(suspended, "09:00", "18:00");

        assertThatThrownBy(() -> calculate(draft, null)).isInstanceOf(BusinessNotBookable.class);
        assertThatThrownBy(() -> calculate(suspended, null)).isInstanceOf(BusinessNotBookable.class);
        assertThatThrownBy(() -> availability.calculate(UUID.randomUUID(), draft.service(), null))
                .isInstanceOf(BusinessNotBookable.class);

        fixtures.setBusinessStatus(draft.business(), "ACTIVE");
        assertThat(calculate(draft, null).slots()).isNotEmpty();
        fixtures.setBusinessStatus(draft.business(), "SUSPENDED");
        assertThatThrownBy(() -> calculate(draft, null)).isInstanceOf(BusinessNotBookable.class);
    }

    @Test
    void anInactiveOrUnknownServiceIsNotBookableAndBecomesBookableWhenReactivated() {
        Tenant tenant = tenant("ACTIVE", 30);
        staff(tenant, "09:00", "18:00");
        fixtures.setServiceActive(tenant.service(), false);

        assertThatThrownBy(() -> calculate(tenant, null)).isInstanceOf(ServiceNotBookable.class);
        assertThatThrownBy(() -> availability.calculate(tenant.business(), UUID.randomUUID(), null))
                .isInstanceOf(ServiceNotBookable.class);

        fixtures.setServiceActive(tenant.service(), true);
        assertThat(calculate(tenant, null).slots()).isNotEmpty();
    }

    @Test
    void inactiveAndUnassignedStaffMembersContributeNothing() {
        Tenant tenant = tenant("ACTIVE", 30);
        UUID active = staff(tenant, "09:00", "18:00");
        UUID inactive = staff(tenant, "09:00", "18:00");
        UUID unassigned = fixtures.staffMember(tenant.business(), true);
        fixtures.everyDay(tenant.business(), unassigned, "09:00", "18:00");
        fixtures.setStaffMemberActive(inactive, false);

        AvailabilitySnapshot any = calculate(tenant, null);

        assertThat(any.slots()).isNotEmpty();
        assertThat(any.slots()).allSatisfy(slot -> assertThat(slot.staffMemberIds())
                .containsExactly(active));
        assertThatThrownBy(() -> calculate(tenant, inactive))
                .isInstanceOf(StaffMemberNotEligible.class);
        assertThatThrownBy(() -> calculate(tenant, unassigned))
                .isInstanceOf(StaffMemberNotEligible.class);
        assertThatThrownBy(() -> calculate(tenant, UUID.randomUUID()))
                .isInstanceOf(StaffMemberNotEligible.class);
    }

    @Test
    void aRemovedAssignmentRemovesAvailabilityAndAnEmptyEligibleSetIsASuccessfulEmptyResult() {
        Tenant tenant = tenant("ACTIVE", 30);
        UUID staff = staff(tenant, "09:00", "18:00");
        assertThat(calculate(tenant, staff).slots()).isNotEmpty();

        fixtures.unassign(tenant.business(), staff, tenant.service());

        assertThat(calculate(tenant, null).slots()).isEmpty();
        assertThat(calculate(tenant, null).timezone()).isEqualTo(SOFIA);
        assertThatThrownBy(() -> calculate(tenant, staff))
                .isInstanceOf(StaffMemberNotEligible.class);
    }

    @Test
    void anAssignedStaffMemberWithoutAnyScheduleYieldsAnEmptyResult() {
        Tenant tenant = tenant("ACTIVE", 30);
        UUID staff = fixtures.staffMember(tenant.business(), true);
        fixtures.assign(tenant.business(), staff, tenant.service());

        assertThat(calculate(tenant, null).slots()).isEmpty();
        assertThat(calculate(tenant, staff).slots()).isEmpty();
    }

    // ---- tenant isolation ----

    @Test
    void identifiersAndExceptionsOfAnotherBusinessNeverLeakIn() {
        Tenant a = tenant("ACTIVE", 30);
        UUID staffA = staff(a, "09:00", "18:00");
        Tenant b = tenant("ACTIVE", 30);
        UUID staffB = staff(b, "09:00", "18:00");
        store(b.business(), ScheduleExceptionContent.businessClosureDays(
                TODAY, TODAY.plusDays(29)));
        store(b.business(), ScheduleExceptionContent.staffTimeOffDays(
                staffB, TODAY, TODAY.plusDays(29)));

        AvailabilitySnapshot forA = calculate(a, null);

        assertThat(forA.slots()).isNotEmpty();
        assertThat(forA.slots()).allSatisfy(slot -> assertThat(slot.staffMemberIds())
                .containsExactly(staffA));
        assertThat(calculate(b, null).slots()).isEmpty();
        assertThatThrownBy(() -> availability.calculate(a.business(), b.service(), null))
                .isInstanceOf(ServiceNotBookable.class);
        assertThatThrownBy(() -> availability.calculate(b.business(), a.service(), null))
                .isInstanceOf(ServiceNotBookable.class);
        assertThatThrownBy(() -> availability.calculate(a.business(), a.service(), staffB))
                .isInstanceOf(StaffMemberNotEligible.class);
    }

    // ---- V9 exceptions end to end ----

    @Test
    void anEmptyOverrideRemovesTheDayAndAnOverrideReplacesTheRecurringPeriods() {
        Tenant tenant = tenant("ACTIVE", 60);
        UUID staff = staff(tenant, "09:00", "18:00");
        store(tenant.business(), ScheduleExceptionContent.workingDayOverride(
                staff, THURSDAY, List.of()));
        store(tenant.business(), ScheduleExceptionContent.workingDayOverride(
                staff, THURSDAY.plusDays(1), List.of(period("12:00", "14:00"))));

        AvailabilitySnapshot snapshot = calculate(tenant, null);

        assertThat(starts(snapshot, THURSDAY)).isEmpty();
        assertThat(starts(snapshot, THURSDAY.plusDays(1))).containsExactlyElementsOf(
                times("12:00", "12:15", "12:30", "12:45", "13:00"));
        assertThat(starts(snapshot, THURSDAY.plusDays(2))).isNotEmpty();
    }

    @Test
    void anAdditionalPeriodExtendsTheRecurringDay() {
        Tenant tenant = tenant("ACTIVE", 60);
        UUID staff = fixtures.staffMember(tenant.business(), true);
        fixtures.assign(tenant.business(), staff, tenant.service());
        fixtures.period(tenant.business(), staff, THURSDAY.getDayOfWeek(), "09:00", "10:00");
        store(tenant.business(), ScheduleExceptionContent.additionalWorkingPeriods(
                staff, THURSDAY, List.of(period("16:00", "17:00"))));

        assertThat(starts(calculate(tenant, null), THURSDAY)).containsExactly(
                LocalTime.of(9, 0), LocalTime.of(16, 0));
    }

    @Test
    void aBusinessClosureBlocksEveryStaffMemberAndTimeOffOnlyItsOwner() {
        Tenant tenant = tenant("ACTIVE", 30);
        UUID first = staff(tenant, "09:00", "18:00");
        UUID second = staff(tenant, "09:00", "18:00");
        store(tenant.business(), ScheduleExceptionContent.businessClosureDays(
                THURSDAY, THURSDAY));
        store(tenant.business(), ScheduleExceptionContent.staffTimeOffDays(
                first, THURSDAY.plusDays(1), THURSDAY.plusDays(1)));

        AvailabilitySnapshot snapshot = calculate(tenant, null);

        assertThat(starts(snapshot, THURSDAY)).isEmpty();
        assertThat(snapshot.slots()).filteredOn(
                slot -> slot.start().atZone(SOFIA).toLocalDate().equals(THURSDAY.plusDays(1)))
                .isNotEmpty()
                .allSatisfy(slot -> assertThat(slot.staffMemberIds()).containsExactly(second));
        assertThat(starts(calculate(tenant, first), THURSDAY.plusDays(1))).isEmpty();
        assertThat(starts(calculate(tenant, second), THURSDAY.plusDays(1))).isNotEmpty();
    }

    @Test
    void aPartialClosureAndPartialTimeOffRemoveOnlyTheirWallClockRanges() {
        Tenant tenant = tenant("ACTIVE", 60);
        UUID staff = staff(tenant, "09:00", "13:00");
        store(tenant.business(), ScheduleExceptionContent.businessClosurePartial(
                THURSDAY, List.of(period("10:00", "11:00"))));
        store(tenant.business(), ScheduleExceptionContent.staffTimeOffPartial(
                staff, THURSDAY, List.of(period("12:00", "13:00"))));

        assertThat(starts(calculate(tenant, null), THURSDAY)).containsExactly(
                LocalTime.of(9, 0), LocalTime.of(11, 0));
    }

    // ---- one and ten StaffMembers ----

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void oneAndTenEligibleStaffMembersCombineWithoutDuplicateStarts(int count) {
        Tenant tenant = tenant("ACTIVE", 30);
        List<UUID> members = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            members.add(staff(tenant, "09:00", "18:00"));
        }

        AvailabilitySnapshot snapshot = calculate(tenant, null);

        assertThat(snapshot.slots()).extracting(AvailabilitySlot::start)
                .doesNotHaveDuplicates()
                .isSorted();
        List<UUID> natural = members.stream().sorted(Comparator.naturalOrder()).toList();
        assertThat(snapshot.slots()).allSatisfy(
                slot -> assertThat(slot.staffMemberIds()).containsExactlyElementsOf(natural));
        AvailabilitySnapshot specific = calculate(tenant, members.getFirst());
        assertThat(specific.slots()).hasSameSizeAs(snapshot.slots());
        assertThat(specific.slots()).allSatisfy(slot -> assertThat(slot.staffMemberIds())
                .containsExactly(members.getFirst()));
    }

    @Test
    void repeatedCalculationsOverUnchangedDataAreIdentical() {
        Tenant tenant = tenant("ACTIVE", 45);
        staff(tenant, "09:00", "18:00");
        staff(tenant, "10:00", "16:00");

        assertThat(calculate(tenant, null)).isEqualTo(calculate(tenant, null));
        assertThat(clock.reads()).isEqualTo(2);
    }

    private static List<LocalTime> times(String... values) {
        List<LocalTime> times = new ArrayList<>();
        for (String value : values) {
            times.add(LocalTime.parse(value));
        }
        return times;
    }

    private record Tenant(UUID business, UUID service) {
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableTestClock availabilityTestClock() {
            return new MutableTestClock(NOW);
        }
    }
}
