package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleAdministration;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffMemberInactive;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffMemberNotFound;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleAdministrationDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.jdbc.Sql;

@Import(StaffWorkingScheduleAdministrationServiceIntegrationTests.FixedClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffWorkingScheduleAdministrationServiceIntegrationTests extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired StaffWorkingScheduleAdministration schedules;
    @Autowired StaffMemberAdministration staffMembers;
    @Autowired JdbcClient jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesPermitReadsAndReplacements(String status) {
        Fixture fixture = ownerFixture(status, true, "BUSINESS_OWNER");
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Working member"));

        StaffWorkingScheduleAdministrationDetails empty = schedules.get(
                fixture.context(), staffMember.id());
        assertThat(empty.staffMemberId()).isEqualTo(staffMember.id());
        assertThat(empty.periods()).isEmpty();
        assertThat(empty.version()).isZero();
        assertThat(empty.timezone()).isEqualTo(ZoneId.of("Europe/Sofia"));

        StaffWorkingScheduleAdministrationDetails replaced = schedules.replace(
                fixture.context(), staffMember.id(), command(List.of(monday()), 0));
        assertThat(replaced.periods()).containsExactly(monday());
        assertThat(replaced.version()).isEqualTo(1);
        assertThat(replaced.updatedAt()).isEqualTo(NOW);

        assertThat(schedules.get(fixture.context(), staffMember.id())).isEqualTo(replaced);
    }

    @Test
    void suspendedBusinessAllowsReadButRejectsReplacementWithoutChange() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Read only"));
        StaffWorkingScheduleAdministrationDetails original = schedules.replace(
                fixture.context(), staffMember.id(), command(List.of(monday()), 0));
        setBusinessStatus(fixture.businessId(), "SUSPENDED");

        assertThat(schedules.get(fixture.context(), staffMember.id())).isEqualTo(original);
        assertThatThrownBy(() -> schedules.replace(
                        fixture.context(),
                        staffMember.id(),
                        command(List.of(), original.version())))
                .isInstanceOf(BusinessSuspended.class)
                .hasMessage("Suspended Business cannot mutate working schedules");

        assertScheduleState(staffMember.id(), original.version(), List.of(monday()));
    }

    @Test
    void activeAndInactiveStaffMembersAreBothReadable() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Toggle"));
        schedules.replace(fixture.context(), staffMember.id(), command(List.of(monday()), 0));
        staffMembers.deactivate(
                fixture.context(), staffMember.id(), new StaffMemberVersionCommand(0L));

        StaffWorkingScheduleAdministrationDetails afterDeactivation = schedules.get(
                fixture.context(), staffMember.id());
        assertThat(afterDeactivation.periods()).containsExactly(monday());
        assertThat(afterDeactivation.version()).isEqualTo(1);
    }

    @Test
    void inactiveStaffMemberReplacementIsRejectedWithoutChangingScheduleState() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Deactivated"));
        schedules.replace(fixture.context(), staffMember.id(), command(List.of(monday()), 0));
        staffMembers.deactivate(
                fixture.context(), staffMember.id(), new StaffMemberVersionCommand(0L));

        assertThatThrownBy(() -> schedules.replace(
                        fixture.context(), staffMember.id(), command(List.of(), 1)))
                .isInstanceOf(StaffMemberInactive.class)
                .hasMessage("Inactive StaffMember cannot receive a schedule replacement");

        assertScheduleState(staffMember.id(), 1, List.of(monday()));
    }

    @ParameterizedTest
    @MethodSource("deniedMemberships")
    void nonqualifyingMembershipIsDeniedForReadsAndReplacements(String role, boolean active) {
        Fixture owner = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails staffMember = staffMembers.create(
                owner.context(), create("Protected"));
        Fixture denied = memberFixture(owner.businessId(), role, active);

        assertThatThrownBy(() -> schedules.get(denied.context(), staffMember.id()))
                .isInstanceOf(BusinessAccessDenied.class)
                .hasMessage("Business access to working schedules is denied");
        assertThatThrownBy(() -> schedules.replace(
                        denied.context(), staffMember.id(), command(List.of(monday()), 0)))
                .isInstanceOf(BusinessAccessDenied.class);
        assertScheduleState(staffMember.id(), 0, List.of());
    }

    @Test
    void platformAdminWithoutOwnerMembershipIsDenied() {
        UUID businessId = business("ACTIVE");
        UUID userId = user();
        grantPlatformAdmin(userId);
        var context = new TestContext(userId, businessId);
        StaffMemberDetails staffMember = insertStaffMember(businessId);

        assertThatThrownBy(() -> schedules.get(context, staffMember.id()))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> schedules.replace(
                        context, staffMember.id(), command(List.of(), 0)))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @Test
    void missingAndForeignMembershipsAreDenied() {
        UUID selectedBusiness = business("ACTIVE");
        UUID membershipBusiness = business("ACTIVE");
        UUID userId = user();
        StaffMemberDetails staffMember = insertStaffMember(selectedBusiness);
        var missingMembership = new TestContext(userId, selectedBusiness);

        assertThatThrownBy(() -> schedules.get(missingMembership, staffMember.id()))
                .isInstanceOf(BusinessAccessDenied.class);

        membership(membershipBusiness, userId, "BUSINESS_OWNER", true);
        assertThatThrownBy(() -> schedules.replace(
                        missingMembership, staffMember.id(), command(List.of(), 0)))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @Test
    void authenticationAndSelectionRemainDistinctFromAccessDenial() {
        UUID userId = user();

        assertThatThrownBy(() -> schedules.get(null, UUID.randomUUID()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> schedules.get(
                        new TestContext(null, UUID.randomUUID()), UUID.randomUUID()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> schedules.get(
                        new TestContext(userId, null), UUID.randomUUID()))
                .isInstanceOf(SelectedBusinessRequired.class);
        assertThatThrownBy(() -> schedules.get(
                        new TestContext(userId, UUID.randomUUID()), UUID.randomUUID()))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @Test
    void missingAndCrossBusinessStaffMemberAreIndistinguishableAndTenantScoped() {
        Fixture first = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        Fixture second = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails foreign = staffMembers.create(first.context(), create("Protected"));
        schedules.replace(first.context(), foreign.id(), command(List.of(monday()), 0));

        assertThatThrownBy(() -> schedules.get(second.context(), foreign.id()))
                .isInstanceOf(StaffMemberNotFound.class)
                .hasMessage("StaffMember was not found");
        assertThatThrownBy(() -> schedules.get(second.context(), UUID.randomUUID()))
                .isInstanceOf(StaffMemberNotFound.class)
                .hasMessage("StaffMember was not found");
        assertThatThrownBy(() -> schedules.replace(
                        second.context(), foreign.id(), command(List.of(), 1)))
                .isInstanceOf(StaffMemberNotFound.class);
        assertThatThrownBy(() -> schedules.replace(
                        second.context(), UUID.randomUUID(), command(List.of(), 0)))
                .isInstanceOf(StaffMemberNotFound.class);

        assertScheduleState(foreign.id(), 1, List.of(monday()));
    }

    @Test
    void versionConflictProducesSafeConcurrentUpdateWithoutPersistenceChange() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Versioned"));
        schedules.replace(fixture.context(), staffMember.id(), command(List.of(monday()), 0));

        assertThatThrownBy(() -> schedules.replace(
                        fixture.context(), staffMember.id(), command(List.of(), 0)))
                .isInstanceOf(ConcurrentUpdate.class)
                .hasMessage("Working schedule was changed by another operation");

        assertScheduleState(staffMember.id(), 1, List.of(monday()));
    }

    @Test
    void liveAuthoritativeBusinessTimezoneIsReturnedNotCached() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails staffMember = staffMembers.create(
                fixture.context(), create("Timezone"));
        assertThat(schedules.get(fixture.context(), staffMember.id()).timezone())
                .isEqualTo(ZoneId.of("Europe/Sofia"));

        jdbc.sql("UPDATE business SET timezone=:timezone WHERE id=:id")
                .param("timezone", "America/New_York")
                .param("id", fixture.businessId())
                .update();

        StaffWorkingScheduleAdministrationDetails afterChange = schedules.get(
                fixture.context(), staffMember.id());
        assertThat(afterChange.timezone()).isEqualTo(ZoneId.of("America/New_York"));

        StaffWorkingScheduleAdministrationDetails replaced = schedules.replace(
                fixture.context(), staffMember.id(), command(List.of(monday()), 0));
        assertThat(replaced.timezone()).isEqualTo(ZoneId.of("America/New_York"));
    }

    private static Stream<Arguments> deniedMemberships() {
        return Stream.of(
                Arguments.of("BUSINESS_OWNER", false),
                Arguments.of("MANAGER", true),
                Arguments.of("STAFF", true));
    }

    private Fixture ownerFixture(String status, boolean active, String role) {
        UUID businessId = business(status);
        UUID userId = user();
        membership(businessId, userId, role, active);
        return new Fixture(businessId, userId, new TestContext(userId, businessId));
    }

    private Fixture memberFixture(UUID businessId, String role, boolean active) {
        UUID userId = user();
        membership(businessId, userId, role, active);
        return new Fixture(businessId, userId, new TestContext(userId, businessId));
    }

    private UUID business(String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Test Business','OTHER',:status,'Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", "schedule-" + id.toString().substring(0, 8))
                .param("status", status)
                .param("now", databaseNow())
                .update();
        return id;
    }

    private StaffMemberDetails insertStaffMember(UUID businessId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,active,version,created_at,updated_at)
                        VALUES (:id,:businessId,'Inserted',true,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("now", databaseNow())
                .update();
        jdbc.sql("""
                        INSERT INTO staff_working_schedule(
                            business_id,staff_member_id,version,created_at,updated_at)
                        VALUES (:businessId,:id,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("now", databaseNow())
                .update();
        return new StaffMemberDetails(id, "Inserted", null, null, true, 0, NOW, NOW);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,
                            password_changed_at,created_at,updated_at)
                        VALUES (
                            :id,:email,'Test User','test-hash',:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", id + "@example.invalid")
                .param("now", databaseNow())
                .update();
        return id;
    }

    private void membership(UUID businessId, UUID userId, String role, boolean active) {
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (
                            :id,:businessId,:userId,:role,:active,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .param("userId", userId)
                .param("role", role)
                .param("active", active)
                .param("now", databaseNow())
                .update();
    }

    private void grantPlatformAdmin(UUID userId) {
        jdbc.sql("""
                        INSERT INTO platform_role(user_id,role,created_at)
                        VALUES (:userId,'PLATFORM_ADMIN',:now)
                        """)
                .param("userId", userId)
                .param("now", databaseNow())
                .update();
    }

    private void setBusinessStatus(UUID businessId, String status) {
        jdbc.sql("UPDATE business SET status=:status WHERE id=:id")
                .param("status", status)
                .param("id", businessId)
                .update();
    }

    private void assertScheduleState(
            UUID staffMemberId, long version, List<WorkingPeriod> periods) {
        assertThat(jdbc.sql(
                        "SELECT version FROM staff_working_schedule WHERE staff_member_id=:id")
                        .param("id", staffMemberId)
                        .query(Long.class)
                        .single())
                .isEqualTo(version);
        List<WorkingPeriod> stored = jdbc.sql("""
                        SELECT weekday, start_time, end_time
                        FROM staff_working_period
                        WHERE staff_member_id=:id
                        ORDER BY weekday ASC, start_time ASC, end_time ASC
                        """)
                .param("id", staffMemberId)
                .query((resultSet, rowNumber) -> new WorkingPeriod(
                        DayOfWeek.of(resultSet.getInt("weekday")),
                        resultSet.getObject("start_time", LocalTime.class),
                        resultSet.getObject("end_time", LocalTime.class)))
                .list();
        assertThat(stored).containsExactlyElementsOf(periods);
    }

    private OffsetDateTime databaseNow() {
        return NOW.atOffset(ZoneOffset.UTC);
    }

    private static CreateStaffMemberCommand create(String displayName) {
        return new CreateStaffMemberCommand(
                displayName, "team@example.invalid", "+359 (2) 123-45-67");
    }

    private static WorkingPeriod monday() {
        return new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    private static ReplaceWorkingPeriodsCommand command(
            List<WorkingPeriod> periods, long expectedVersion) {
        return new ReplaceWorkingPeriodsCommand(periods, expectedVersion);
    }

    private record Fixture(
            UUID businessId, UUID userId, AuthenticatedBusinessContext context) {
    }

    private record TestContext(UUID userId, UUID businessId)
            implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.ofNullable(businessId);
        }
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedStaffWorkingScheduleAdministrationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
