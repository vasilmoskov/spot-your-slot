package bg.spotyourslot.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessAccessDenied;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessSuspended;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ConcurrentUpdate;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.OverlapConflict;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ScheduleExceptionNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberInactive;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionAdministrationDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionWindow;
import bg.spotyourslot.scheduling.application.ScheduleExceptionTestSupport.Fixture;
import bg.spotyourslot.scheduling.application.ScheduleExceptionTestSupport.TestContext;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

@Import(ScheduleExceptionAdministrationServiceIntegrationTests.FixedClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleExceptionAdministrationServiceIntegrationTests extends PostgresIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);

    @Autowired ScheduleExceptionAdministration exceptions;
    @Autowired JdbcClient jdbc;

    private ScheduleExceptionTestSupport support;

    @BeforeEach
    void setUp() {
        support = new ScheduleExceptionTestSupport(jdbc);
    }

    @Test
    void ownerRoundTripsAllFourKindsWithVersionsTimestampsAndTimezone() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);

        ScheduleExceptionAdministrationDetails closure = exceptions.create(
                fixture.context(), closure(DATE, DATE.plusDays(2)));
        ScheduleExceptionAdministrationDetails timeOff = exceptions.create(
                fixture.context(), timeOffPartial(staffMemberId, DATE, period(13, 0, 14, 0)));
        ScheduleExceptionAdministrationDetails override = exceptions.create(
                fixture.context(), override(staffMemberId, DATE));
        ScheduleExceptionAdministrationDetails additional = exceptions.create(
                fixture.context(),
                additional(staffMemberId, DATE, period(18, 0, 20, 0), period(9, 0, 10, 0)));

        assertThat(closure.timezone()).isEqualTo(ZoneId.of("Europe/Sofia"));
        assertThat(closure.exception().version()).isZero();
        assertThat(closure.exception().createdAt()).isEqualTo(ScheduleExceptionTestSupport.NOW);
        assertThat(closure.exception().updatedAt()).isEqualTo(ScheduleExceptionTestSupport.NOW);
        assertThat(closure.exception().allDay()).isTrue();
        assertThat(closure.exception().staffMemberId()).isNull();
        assertThat(timeOff.exception().kind()).isEqualTo(ScheduleExceptionKind.STAFF_TIME_OFF);
        assertThat(override.exception().periods()).isEmpty();
        assertThat(additional.exception().periods()).containsExactly(
                new ExceptionPeriod(LocalTime.of(9, 0), LocalTime.of(10, 0)),
                new ExceptionPeriod(LocalTime.of(18, 0), LocalTime.of(20, 0)));

        for (ScheduleExceptionAdministrationDetails created :
                List.of(closure, timeOff, override, additional)) {
            ScheduleExceptionAdministrationDetails read = exceptions.get(
                    fixture.context(), created.exception().id());
            assertThat(read.exception()).isEqualTo(created.exception());
        }
        assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(4);
    }

    @Test
    void listReturnsOverlappingExceptionsInDeterministicOrderWithInclusiveEdges() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails before = exceptions.create(
                fixture.context(), closure(DATE.minusDays(10), DATE.minusDays(1))).exception();
        ScheduleExceptionDetails endsOnFrom = exceptions.create(
                fixture.context(), timeOffDays(staffMemberId, DATE.minusDays(3), DATE)).exception();
        ScheduleExceptionDetails inside = exceptions.create(
                fixture.context(), closure(DATE.plusDays(1), DATE.plusDays(2))).exception();
        ScheduleExceptionDetails startsOnTo = exceptions.create(
                fixture.context(), override(staffMemberId, DATE.plusDays(5))).exception();
        ScheduleExceptionDetails after = exceptions.create(
                fixture.context(), closure(DATE.plusDays(6), DATE.plusDays(7))).exception();

        ScheduleExceptionWindow window = exceptions.list(
                fixture.context(), DATE, DATE.plusDays(5));

        assertThat(window.from()).isEqualTo(DATE);
        assertThat(window.to()).isEqualTo(DATE.plusDays(5));
        assertThat(window.timezone()).isEqualTo(ZoneId.of("Europe/Sofia"));
        assertThat(window.exceptions()).extracting(ScheduleExceptionDetails::id)
                .containsExactly(endsOnFrom.id(), inside.id(), startsOnTo.id());
        assertThat(window.exceptions()).extracting(ScheduleExceptionDetails::id)
                .doesNotContain(before.id(), after.id());
    }

    @Test
    void replacementKeepsKindAndStaffMemberAndAtomicallyReplacesPeriods() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(),
                additional(
                        staffMemberId, DATE,
                        period(8, 0, 9, 0), period(10, 0, 11, 0), period(12, 0, 13, 0)))
                .exception();
        assertThat(support.periodCount(created.id())).isEqualTo(3);

        ScheduleExceptionAdministrationDetails replaced = exceptions.replace(
                fixture.context(),
                created.id(),
                new ReplaceScheduleExceptionCommand(
                        0L, DATE.plusDays(1), DATE.plusDays(1), false, List.of(period(15, 0, 16, 0))));

        assertThat(replaced.exception().id()).isEqualTo(created.id());
        assertThat(replaced.exception().kind())
                .isEqualTo(ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS);
        assertThat(replaced.exception().staffMemberId()).isEqualTo(staffMemberId);
        assertThat(replaced.exception().firstDate()).isEqualTo(DATE.plusDays(1));
        assertThat(replaced.exception().version()).isEqualTo(1);
        assertThat(replaced.exception().createdAt()).isEqualTo(created.createdAt());
        assertThat(replaced.exception().periods()).containsExactly(
                new ExceptionPeriod(LocalTime.of(15, 0), LocalTime.of(16, 0)));
        assertThat(support.periodCount(created.id())).isEqualTo(1);
        String storedKind = jdbc.sql("SELECT kind FROM schedule_exception WHERE id = :id")
                .param("id", created.id())
                .query(String.class)
                .single();
        assertThat(storedKind).isEqualTo("ADDITIONAL_WORKING_PERIODS");
    }

    @Test
    void overrideMayBeReplacedByAnEmptyPeriodSetAndIdenticalReplacementAdvancesTheVersion() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(),
                new CreateScheduleExceptionCommand(
                        ScheduleExceptionKind.WORKING_DAY_OVERRIDE, staffMemberId, DATE, DATE, false,
                        List.of(period(9, 0, 12, 0)))).exception();

        ScheduleExceptionDetails emptied = exceptions.replace(
                fixture.context(),
                created.id(),
                new ReplaceScheduleExceptionCommand(0L, DATE, DATE, false, List.of())).exception();
        ScheduleExceptionDetails again = exceptions.replace(
                fixture.context(),
                created.id(),
                new ReplaceScheduleExceptionCommand(1L, DATE, DATE, false, List.of())).exception();

        assertThat(emptied.periods()).isEmpty();
        assertThat(emptied.version()).isEqualTo(1);
        assertThat(again.version()).isEqualTo(2);
        assertThat(support.periodCount(created.id())).isZero();
    }

    @Test
    void replacementOfAWorkingKindCannotBecomeFullDay() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), override(staffMemberId, DATE)).exception();

        assertThatThrownBy(() -> exceptions.replace(
                        fixture.context(),
                        created.id(),
                        new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())))
                .isInstanceOf(InvalidInput.class);
        assertThat(support.storedVersion(created.id())).isZero();
    }

    @Test
    void sameKindOverlapIsRejectedByPostgreSqlAndRollsBackEveryChange() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails first = exceptions.create(
                fixture.context(), timeOffDays(staffMemberId, DATE, DATE.plusDays(3))).exception();
        ScheduleExceptionDetails second = exceptions.create(
                fixture.context(),
                timeOffDays(staffMemberId, DATE.plusDays(10), DATE.plusDays(12))).exception();

        assertThatThrownBy(() -> exceptions.create(
                        fixture.context(), timeOffDays(staffMemberId, DATE.plusDays(3), DATE.plusDays(4))))
                .isInstanceOf(OverlapConflict.class);
        assertThatThrownBy(() -> exceptions.replace(
                        fixture.context(),
                        second.id(),
                        new ReplaceScheduleExceptionCommand(
                                0L, DATE.plusDays(2), DATE.plusDays(11), true, List.of())))
                .isInstanceOf(OverlapConflict.class);

        assertThat(support.storedVersion(second.id())).isZero();
        assertThat(exceptions.get(fixture.context(), second.id()).exception().firstDate())
                .isEqualTo(DATE.plusDays(10));
        assertThat(exceptions.get(fixture.context(), first.id()).exception().version()).isZero();
        assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(2);
    }

    @Test
    void overlapMatrixAllowsCrossKindCrossStaffAndAdjacentRangesButRejectsDuplicateWorkingDates() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffA = support.staffMember(fixture.businessId(), true);
        UUID staffB = support.staffMember(fixture.businessId(), true);

        exceptions.create(fixture.context(), closure(DATE, DATE.plusDays(2)));
        exceptions.create(fixture.context(), timeOffDays(staffA, DATE, DATE.plusDays(2)));
        exceptions.create(fixture.context(), override(staffA, DATE));
        exceptions.create(fixture.context(), additional(staffA, DATE, period(9, 0, 10, 0)));
        exceptions.create(fixture.context(), timeOffDays(staffB, DATE, DATE.plusDays(2)));
        exceptions.create(fixture.context(), closure(DATE.plusDays(3), DATE.plusDays(4)));
        exceptions.create(fixture.context(), timeOffDays(staffA, DATE.plusDays(3), DATE.plusDays(3)));

        assertThatThrownBy(() -> exceptions.create(fixture.context(), override(staffA, DATE)))
                .isInstanceOf(OverlapConflict.class);
        assertThatThrownBy(() -> exceptions.create(
                        fixture.context(), additional(staffA, DATE, period(11, 0, 12, 0))))
                .isInstanceOf(OverlapConflict.class);
        assertThatThrownBy(() -> exceptions.create(
                        fixture.context(), closure(DATE.plusDays(2), DATE.plusDays(3))))
                .isInstanceOf(OverlapConflict.class);
        assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(7);
    }

    @Test
    void staleVersionsAndDeletedAggregatesAreConcurrentUpdatesNotNotFound() {
        Fixture fixture = support.fixture("ACTIVE");
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), closure(DATE, DATE)).exception();
        exceptions.replace(
                fixture.context(),
                created.id(),
                new ReplaceScheduleExceptionCommand(0L, DATE, DATE.plusDays(1), true, List.of()));

        assertThatThrownBy(() -> exceptions.replace(
                        fixture.context(),
                        created.id(),
                        new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> exceptions.delete(fixture.context(), created.id(), 0L))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> exceptions.delete(fixture.context(), created.id(), 2L))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThat(support.storedVersion(created.id())).isEqualTo(1);

        exceptions.delete(fixture.context(), created.id(), 1L);
        assertThat(support.exceptionCount(fixture.businessId())).isZero();
        assertThat(support.periodCount(created.id())).isZero();
        assertThatThrownBy(() -> exceptions.get(fixture.context(), created.id()))
                .isInstanceOf(ScheduleExceptionNotFound.class);
        assertThatThrownBy(() -> exceptions.delete(fixture.context(), created.id(), 1L))
                .isInstanceOf(ScheduleExceptionNotFound.class);
    }

    @Test
    void deleteRemovesTheAggregateAndItsPeriodsInOneOperation() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(),
                additional(staffMemberId, DATE, period(9, 0, 10, 0), period(11, 0, 12, 0)))
                .exception();

        exceptions.delete(fixture.context(), created.id(), 0L);

        assertThat(support.exceptionCount(fixture.businessId())).isZero();
        assertThat(support.periodCount(created.id())).isZero();
    }

    @Test
    void foreignExceptionIdsBehaveExactlyLikeMissingIdsAndForeignDataIsUntouched() {
        Fixture businessA = support.fixture("ACTIVE");
        Fixture businessB = support.fixture("ACTIVE");
        UUID staffA = support.staffMember(businessA.businessId(), true);
        ScheduleExceptionDetails inA = exceptions.create(
                businessA.context(), timeOffDays(staffA, DATE, DATE.plusDays(1))).exception();
        UUID missing = UUID.randomUUID();

        for (UUID id : List.of(inA.id(), missing)) {
            assertThatThrownBy(() -> exceptions.get(businessB.context(), id))
                    .isInstanceOf(ScheduleExceptionNotFound.class);
            assertThatThrownBy(() -> exceptions.replace(
                            businessB.context(),
                            id,
                            new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())))
                    .isInstanceOf(ScheduleExceptionNotFound.class);
            assertThatThrownBy(() -> exceptions.delete(businessB.context(), id, 0L))
                    .isInstanceOf(ScheduleExceptionNotFound.class);
        }
        assertThat(exceptions.list(businessB.context(), DATE, DATE.plusDays(5)).exceptions())
                .isEmpty();
        assertThat(support.storedVersion(inA.id())).isZero();
        assertThat(exceptions.get(businessA.context(), inA.id()).exception().version()).isZero();
    }

    @Test
    void staffMemberFromAnotherBusinessIsNotFoundAndNothingIsWritten() {
        Fixture businessA = support.fixture("ACTIVE");
        Fixture businessB = support.fixture("ACTIVE");
        UUID staffInB = support.staffMember(businessB.businessId(), true);

        assertThatThrownBy(() -> exceptions.create(
                        businessA.context(), timeOffDays(staffInB, DATE, DATE)))
                .isInstanceOf(StaffMemberNotFound.class);
        assertThatThrownBy(() -> exceptions.create(
                        businessA.context(), timeOffDays(UUID.randomUUID(), DATE, DATE)))
                .isInstanceOf(StaffMemberNotFound.class);
        assertThat(support.exceptionCount(businessA.businessId())).isZero();
        assertThat(support.exceptionCount(businessB.businessId())).isZero();
    }

    @Test
    void inactiveStaffMemberIsReadableButItsExceptionsCannotBeCreatedReplacedOrDeleted() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), timeOffDays(staffMemberId, DATE, DATE)).exception();
        support.setStaffMemberActive(staffMemberId, false);

        assertThat(exceptions.get(fixture.context(), created.id()).exception().id())
                .isEqualTo(created.id());
        assertThat(exceptions.list(fixture.context(), DATE, DATE).exceptions()).hasSize(1);
        assertThatThrownBy(() -> exceptions.create(
                        fixture.context(), timeOffDays(staffMemberId, DATE.plusDays(5), DATE.plusDays(5))))
                .isInstanceOf(StaffMemberInactive.class);
        assertThatThrownBy(() -> exceptions.replace(
                        fixture.context(),
                        created.id(),
                        new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())))
                .isInstanceOf(StaffMemberInactive.class);
        assertThatThrownBy(() -> exceptions.delete(fixture.context(), created.id(), 0L))
                .isInstanceOf(StaffMemberInactive.class);
        assertThat(support.storedVersion(created.id())).isZero();

        // Documented trade-off: cleanup of an inactive StaffMember's exception
        // requires temporary reactivation.
        support.setStaffMemberActive(staffMemberId, true);
        exceptions.delete(fixture.context(), created.id(), 0L);
        assertThat(support.exceptionCount(fixture.businessId())).isZero();
    }

    @Test
    void inactiveStaffMemberDoesNotAffectBusinessClosures() {
        Fixture fixture = support.fixture("ACTIVE");
        support.staffMember(fixture.businessId(), false);

        ScheduleExceptionDetails closure = exceptions.create(
                fixture.context(), closure(DATE, DATE)).exception();

        exceptions.delete(fixture.context(), closure.id(), 0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesAllowReadsAndMutations(String status) {
        Fixture fixture = support.fixture(status);

        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), closure(DATE, DATE)).exception();
        exceptions.replace(
                fixture.context(),
                created.id(),
                new ReplaceScheduleExceptionCommand(0L, DATE, DATE.plusDays(1), true, List.of()));
        exceptions.delete(fixture.context(), created.id(), 1L);

        assertThat(exceptions.list(fixture.context(), DATE, DATE).exceptions()).isEmpty();
    }

    @Test
    void suspendedBusinessRemainsReadableAndRejectsEveryMutationWithoutChangingData() {
        Fixture fixture = support.fixture("ACTIVE");
        UUID staffMemberId = support.staffMember(fixture.businessId(), true);
        ScheduleExceptionDetails created = exceptions.create(
                fixture.context(), timeOffDays(staffMemberId, DATE, DATE)).exception();
        support.setBusinessStatus(fixture.businessId(), "SUSPENDED");

        assertThat(exceptions.get(fixture.context(), created.id()).exception().id())
                .isEqualTo(created.id());
        assertThat(exceptions.list(fixture.context(), DATE, DATE).exceptions()).hasSize(1);
        assertThatThrownBy(() -> exceptions.create(fixture.context(), closure(DATE, DATE)))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> exceptions.replace(
                        fixture.context(),
                        created.id(),
                        new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> exceptions.delete(fixture.context(), created.id(), 0L))
                .isInstanceOf(BusinessSuspended.class);
        assertThat(support.storedVersion(created.id())).isZero();
        assertThat(support.exceptionCount(fixture.businessId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void nonOwnerRolesAreDeniedForEveryOperation(String role) {
        Fixture owner = support.fixture("ACTIVE");
        ScheduleExceptionDetails created = exceptions.create(
                owner.context(), closure(DATE, DATE)).exception();
        UUID userId = support.user();
        support.membership(owner.businessId(), userId, role, true);
        AuthenticatedBusinessContext context = new TestContext(userId, owner.businessId());

        assertAllDenied(context, created.id());
        assertThat(support.storedVersion(created.id())).isZero();
    }

    @Test
    void inactiveOwnerMembershipMissingMembershipForeignMembershipAndPlatformAdminAreDenied() {
        Fixture business = support.fixture("ACTIVE");
        ScheduleExceptionDetails created = exceptions.create(
                business.context(), closure(DATE, DATE)).exception();

        UUID inactiveOwner = support.user();
        support.membership(business.businessId(), inactiveOwner, "BUSINESS_OWNER", false);
        UUID noMembership = support.user();
        UUID foreignOwner = support.user();
        support.membership(support.business("ACTIVE"), foreignOwner, "BUSINESS_OWNER", true);
        UUID platformAdmin = support.user();
        jdbc.sql("""
                        INSERT INTO platform_role(user_id,role,created_at)
                        VALUES (:user,'PLATFORM_ADMIN',:now)
                        """)
                .param("user", platformAdmin)
                .param("now", ScheduleExceptionTestSupport.databaseNow())
                .update();

        for (UUID userId : List.of(inactiveOwner, noMembership, foreignOwner, platformAdmin)) {
            assertAllDenied(new TestContext(userId, business.businessId()), created.id());
        }
        assertThat(support.storedVersion(created.id())).isZero();
    }

    @Test
    void ownerAuthorityOfAnotherBusinessDoesNotGrantAccessToTheSelectedBusiness() {
        Fixture businessA = support.fixture("ACTIVE");
        Fixture businessB = support.fixture("ACTIVE");
        ScheduleExceptionDetails inA = exceptions.create(
                businessA.context(), closure(DATE, DATE)).exception();

        AuthenticatedBusinessContext crossed = new TestContext(
                businessB.userId(), businessA.businessId());

        assertAllDenied(crossed, inA.id());
    }

    private void assertAllDenied(AuthenticatedBusinessContext context, UUID exceptionId) {
        assertThatThrownBy(() -> exceptions.list(context, DATE, DATE))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> exceptions.get(context, exceptionId))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> exceptions.create(context, closure(DATE.plusDays(30), DATE.plusDays(30))))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> exceptions.replace(
                        context,
                        exceptionId,
                        new ReplaceScheduleExceptionCommand(0L, DATE, DATE, true, List.of())))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> exceptions.delete(context, exceptionId, 0L))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    private static CreateScheduleExceptionCommand closure(LocalDate first, LocalDate last) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, first, last, true, List.of());
    }

    private static CreateScheduleExceptionCommand timeOffDays(
            UUID staffMemberId, LocalDate first, LocalDate last) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.STAFF_TIME_OFF, staffMemberId, first, last, true, List.of());
    }

    private static CreateScheduleExceptionCommand timeOffPartial(
            UUID staffMemberId, LocalDate date, ExceptionPeriod period) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.STAFF_TIME_OFF, staffMemberId, date, date, false, List.of(period));
    }

    private static CreateScheduleExceptionCommand override(UUID staffMemberId, LocalDate date) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.WORKING_DAY_OVERRIDE, staffMemberId, date, date, false, List.of());
    }

    private static CreateScheduleExceptionCommand additional(
            UUID staffMemberId, LocalDate date, ExceptionPeriod... periods) {
        return new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                staffMemberId,
                date,
                date,
                false,
                List.of(periods));
    }

    private static ExceptionPeriod period(int startHour, int startMinute, int endHour, int endMinute) {
        return new ExceptionPeriod(
                LocalTime.of(startHour, startMinute), LocalTime.of(endHour, endMinute));
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedScheduleExceptionClock() {
            return Clock.fixed(ScheduleExceptionTestSupport.NOW, ZoneOffset.UTC);
        }
    }
}
