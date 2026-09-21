package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffMemberApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffMemberApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InputField;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffMemberApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.workforce.StaffMemberApplicationException.StaffMemberNotFound;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.jdbc.Sql;

@Import(StaffMemberAdministrationServiceIntegrationTests.FixedClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffMemberAdministrationServiceIntegrationTests extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired StaffMemberAdministration staffMembers;
    @Autowired JdbcClient jdbc;

    @Test
    void platformAdminWithActiveOwnerMembershipCanPerformCompleteLifecycle() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        grantPlatformAdmin(fixture.userId());

        StaffMemberDetails created = staffMembers.create(
                fixture.context(), create("  Анна\u00A0 Иванова  "));
        StaffMemberDetails updated = staffMembers.update(
                fixture.context(),
                created.id(),
                update("Анна Петрова", created.version()));
        StaffMemberDetails inactive = staffMembers.deactivate(
                fixture.context(), updated.id(), version(updated.version()));
        StaffMemberDetails inactiveUpdated = staffMembers.update(
                fixture.context(),
                inactive.id(),
                update("Анна Георгиева", inactive.version()));
        StaffMemberDetails active = staffMembers.reactivate(
                fixture.context(),
                inactiveUpdated.id(),
                version(inactiveUpdated.version()));

        assertThat(created.displayName()).isEqualTo("Анна Иванова");
        assertThat(created.contactEmail()).isEqualTo("team@example.invalid");
        assertThat(created.contactPhone()).isEqualTo("+359 (2) 123-45-67");
        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(2);
        assertThat(inactiveUpdated.active()).isFalse();
        assertThat(inactiveUpdated.version()).isEqualTo(3);
        assertThat(active.active()).isTrue();
        assertThat(active.version()).isEqualTo(4);
        assertThat(active.createdAt()).isEqualTo(created.createdAt());
        assertThat(active.updatedAt()).isEqualTo(NOW);
        assertThat(staffMembers.get(fixture.context(), created.id())).isEqualTo(active);
        assertThat(staffMembers.list(fixture.context(), 0, 20).staffMembers())
                .containsExactly(active);
    }

    @Test
    void tenantIsolationUsesSameNotFoundAndNeverMutatesForeignStaffMember() {
        Fixture first = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        Fixture second = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails foreign = staffMembers.create(first.context(), create("Protected"));
        Snapshot original = snapshot(foreign.id());

        assertThatThrownBy(() -> staffMembers.get(second.context(), foreign.id()))
                .isInstanceOf(StaffMemberNotFound.class)
                .hasMessage("StaffMember was not found");
        assertThatThrownBy(() -> staffMembers.get(second.context(), UUID.randomUUID()))
                .isInstanceOf(StaffMemberNotFound.class)
                .hasMessage("StaffMember was not found");
        assertThatThrownBy(() -> staffMembers.update(
                        second.context(), foreign.id(), update("Intrusion", foreign.version())))
                .isInstanceOf(StaffMemberNotFound.class);
        assertThatThrownBy(() -> staffMembers.deactivate(
                        second.context(), foreign.id(), version(foreign.version())))
                .isInstanceOf(StaffMemberNotFound.class);
        assertThatThrownBy(() -> staffMembers.reactivate(
                        second.context(), foreign.id(), version(foreign.version())))
                .isInstanceOf(StaffMemberNotFound.class);

        assertThat(snapshot(foreign.id())).isEqualTo(original);
        assertThat(staffMembers.list(second.context(), 0, 20).staffMembers()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("deniedMemberships")
    void nonqualifyingMembershipIsDeniedForReadsAndMutations(String role, boolean active) {
        Fixture fixture = ownerFixture("ACTIVE", active, role);

        assertThatThrownBy(() -> staffMembers.list(fixture.context(), 0, 20))
                .isInstanceOf(BusinessAccessDenied.class)
                .hasMessage("Business access to StaffMembers is denied");
        assertThatThrownBy(() -> staffMembers.create(fixture.context(), create("Denied")))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThat(countStaff(fixture.businessId())).isZero();
    }

    @Test
    void platformAdminWithoutOwnerMembershipIsDenied() {
        UUID businessId = business("ACTIVE");
        UUID userId = user();
        grantPlatformAdmin(userId);
        var context = new TestContext(userId, businessId);

        assertThatThrownBy(() -> staffMembers.list(context, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> staffMembers.create(context, create("Denied")))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThat(countStaff(businessId)).isZero();
    }

    @Test
    void missingAndForeignMembershipsAreDenied() {
        UUID selectedBusiness = business("ACTIVE");
        UUID membershipBusiness = business("ACTIVE");
        UUID userId = user();
        var missingMembership = new TestContext(userId, selectedBusiness);

        assertThatThrownBy(() -> staffMembers.list(missingMembership, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);

        membership(membershipBusiness, userId, "BUSINESS_OWNER", true);
        assertThatThrownBy(() -> staffMembers.create(
                        missingMembership, create("Foreign membership")))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThat(countStaff(selectedBusiness)).isZero();
    }

    @Test
    void authenticationSelectionAndMissingBusinessRemainDistinct() {
        UUID userId = user();

        assertThatThrownBy(() -> staffMembers.list(null, 0, 20))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> staffMembers.list(
                        new TestContext(null, UUID.randomUUID()), 0, 20))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> staffMembers.list(
                        new TestContext(userId, null), 0, 20))
                .isInstanceOf(SelectedBusinessRequired.class);
        assertThatThrownBy(() -> staffMembers.list(
                        new TestContext(userId, UUID.randomUUID()), 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesPermitReadsAndMutations(String status) {
        Fixture fixture = ownerFixture(status, true, "BUSINESS_OWNER");

        StaffMemberDetails created = staffMembers.create(
                fixture.context(), create(status + " member"));
        StaffMemberDetails updated = staffMembers.update(
                fixture.context(), created.id(), update("Updated", created.version()));

        assertThat(staffMembers.get(fixture.context(), created.id())).isEqualTo(updated);
        assertThat(staffMembers.list(fixture.context(), 0, 20).staffMembers())
                .containsExactly(updated);
    }

    @Test
    void suspendedBusinessPermitsReadsAndRejectsEveryMutationWithoutChanges() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails original = staffMembers.create(
                fixture.context(), create("Read only"));
        setBusinessStatus(fixture.businessId(), "SUSPENDED");
        Snapshot before = snapshot(original.id());

        assertThat(staffMembers.get(fixture.context(), original.id())).isEqualTo(original);
        assertThat(staffMembers.list(fixture.context(), 0, 20).staffMembers())
                .containsExactly(original);
        assertThatThrownBy(() -> staffMembers.create(fixture.context(), create("New")))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> staffMembers.update(
                        fixture.context(), original.id(), update("Changed", original.version())))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> staffMembers.deactivate(
                        fixture.context(), original.id(), version(original.version())))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> staffMembers.reactivate(
                        fixture.context(), original.id(), version(original.version())))
                .isInstanceOf(BusinessSuspended.class);

        assertThat(snapshot(original.id())).isEqualTo(before);
        assertThat(countStaff(fixture.businessId())).isEqualTo(1);
    }

    @Test
    void listingIsDeterministicTenantScopedAndIncludesInactiveStaffMembers() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails beta = staffMembers.create(
                fixture.context(), create("  BeTa\u00A0 member  "));
        StaffMemberDetails alpha = staffMembers.create(
                fixture.context(), create("Alpha member"));
        StaffMemberDetails inactive = staffMembers.deactivate(
                fixture.context(), beta.id(), version(beta.version()));

        var first = staffMembers.list(fixture.context(), 0, 1);
        var second = staffMembers.list(fixture.context(), 1, 1);

        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(2);
        assertThat(first.staffMembers()).containsExactly(alpha);
        assertThat(second.staffMembers()).containsExactly(inactive);
        assertThat(staffMembers.list(fixture.context(), 0, 20).staffMembers())
                .extracting(StaffMemberDetails::active)
                .containsExactly(true, false);
        assertThat(StaffMemberDetails.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("businessId", "membershipId", "userId");
    }

    @ParameterizedTest
    @MethodSource("invalidOperations")
    void validatesInputsThroughSafeApplicationOutcomes(
            InputField expectedField, InvalidCall call) {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails existing = staffMembers.create(
                fixture.context(), create("Existing"));
        Snapshot before = snapshot(existing.id());

        assertThatThrownBy(() -> call.invoke(staffMembers, fixture.context(), existing.id()))
                .isInstanceOfSatisfying(InvalidInput.class,
                        failure -> assertThat(failure.field()).isEqualTo(expectedField));
        assertThat(snapshot(existing.id())).isEqualTo(before);
    }

    @Test
    void classifiesStaleAndRepeatedLifecycleMutationsPrecisely() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        StaffMemberDetails created = staffMembers.create(
                fixture.context(), create("Versioned"));
        StaffMemberDetails updated = staffMembers.update(
                fixture.context(), created.id(), update("Updated", created.version()));

        assertThatThrownBy(() -> staffMembers.update(
                        fixture.context(), created.id(), update("Stale", created.version())))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> staffMembers.deactivate(
                        fixture.context(), created.id(), version(created.version())))
                .isInstanceOf(ConcurrentUpdate.class);
        StaffMemberDetails inactive = staffMembers.deactivate(
                fixture.context(), updated.id(), version(updated.version()));
        assertThatThrownBy(() -> staffMembers.deactivate(
                        fixture.context(), inactive.id(), version(inactive.version())))
                .isInstanceOf(InvalidLifecycleTransition.class);
        assertThatThrownBy(() -> staffMembers.reactivate(
                        fixture.context(), inactive.id(), version(updated.version())))
                .isInstanceOf(ConcurrentUpdate.class);
        StaffMemberDetails active = staffMembers.reactivate(
                fixture.context(), inactive.id(), version(inactive.version()));
        assertThatThrownBy(() -> staffMembers.reactivate(
                        fixture.context(), active.id(), version(active.version())))
                .isInstanceOf(InvalidLifecycleTransition.class);

        assertThat(snapshot(active.id())).isEqualTo(new Snapshot(
                active.id(),
                fixture.businessId(),
                active.displayName(),
                active.contactEmail(),
                active.contactPhone(),
                true,
                active.version(),
                active.createdAt(),
                active.updatedAt()));
    }

    @Test
    void unexpectedPostgresqlFailureIsSanitizedAndRollsBack() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        jdbc.sql("""
                        CREATE FUNCTION phase3_reject_staff_member() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'phase3 private failure' USING ERRCODE = 'XX000';
                        END
                        $$
                        """)
                .update();
        jdbc.sql("""
                        CREATE TRIGGER phase3_reject_staff_member_trigger
                        BEFORE INSERT ON staff_member
                        FOR EACH ROW EXECUTE FUNCTION phase3_reject_staff_member()
                        """)
                .update();

        try {
            assertThatThrownBy(() -> staffMembers.create(fixture.context(), create("Failure")))
                    .isInstanceOf(UnexpectedFailure.class)
                    .hasMessage("StaffMember persistence operation failed")
                    .hasCauseInstanceOf(Exception.class);
            assertThat(countStaff(fixture.businessId())).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER phase3_reject_staff_member_trigger ON staff_member").update();
            jdbc.sql("DROP FUNCTION phase3_reject_staff_member()").update();
        }
    }

    private static Stream<Arguments> deniedMemberships() {
        return Stream.of(
                Arguments.of("BUSINESS_OWNER", false),
                Arguments.of("MANAGER", true),
                Arguments.of("STAFF", true));
    }

    private static Stream<Arguments> invalidOperations() {
        return Stream.of(
                Arguments.of(InputField.DISPLAY_NAME,
                        (InvalidCall) (service, context, id) ->
                                service.create(context, create(" "))),
                Arguments.of(InputField.CONTACT_EMAIL,
                        (InvalidCall) (service, context, id) -> service.create(
                                context,
                                new CreateStaffMemberCommand("Valid", "invalid", null))),
                Arguments.of(InputField.CONTACT_PHONE,
                        (InvalidCall) (service, context, id) -> service.create(
                                context,
                                new CreateStaffMemberCommand("Valid", null, "12"))),
                Arguments.of(InputField.PAGE,
                        (InvalidCall) (service, context, id) -> service.list(context, -1, 20)),
                Arguments.of(InputField.SIZE,
                        (InvalidCall) (service, context, id) -> service.list(context, 0, 0)),
                Arguments.of(InputField.STAFF_MEMBER_ID,
                        (InvalidCall) (service, context, id) -> service.get(context, null)),
                Arguments.of(InputField.EXPECTED_VERSION,
                        (InvalidCall) (service, context, id) ->
                                service.update(context, id, update("Valid", -1))),
                Arguments.of(InputField.EXPECTED_VERSION,
                        (InvalidCall) (service, context, id) ->
                                service.deactivate(context, id, version(-1))));
    }

    private Fixture ownerFixture(String status, boolean active, String role) {
        UUID businessId = business(status);
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
                .param("slug", "staff-" + id.toString().substring(0, 8))
                .param("status", status)
                .param("now", databaseNow())
                .update();
        return id;
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

    private long countStaff(UUID businessId) {
        return jdbc.sql("SELECT count(*) FROM staff_member WHERE business_id=:businessId")
                .param("businessId", businessId)
                .query(Long.class)
                .single();
    }

    private Snapshot snapshot(UUID staffMemberId) {
        return jdbc.sql("""
                        SELECT id,business_id,display_name,contact_email,contact_phone,
                               active,version,created_at,updated_at
                        FROM staff_member WHERE id=:id
                        """)
                .param("id", staffMemberId)
                .query((resultSet, rowNumber) -> new Snapshot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("business_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getString("contact_email"),
                        resultSet.getString("contact_phone"),
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private OffsetDateTime databaseNow() {
        return NOW.atOffset(ZoneOffset.UTC);
    }

    private static CreateStaffMemberCommand create(String displayName) {
        return new CreateStaffMemberCommand(
                displayName,
                " TEAM@EXAMPLE.INVALID ",
                " +359 (2) 123-45-67 ");
    }

    private static UpdateStaffMemberCommand update(String displayName, long version) {
        return new UpdateStaffMemberCommand(
                displayName,
                " UPDATED@EXAMPLE.INVALID ",
                " +359 888 123 456 ",
                version);
    }

    private static StaffMemberVersionCommand version(long version) {
        return new StaffMemberVersionCommand(version);
    }

    @FunctionalInterface
    private interface InvalidCall {
        Object invoke(
                StaffMemberAdministration service,
                AuthenticatedBusinessContext context,
                UUID staffMemberId);
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

    private record Snapshot(
            UUID id,
            UUID businessId,
            String displayName,
            String contactEmail,
            String contactPhone,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedStaffMemberAdministrationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
