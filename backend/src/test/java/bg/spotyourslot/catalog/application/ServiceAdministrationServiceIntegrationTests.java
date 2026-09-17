package bg.spotyourslot.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessAccessDenied;
import bg.spotyourslot.catalog.ServiceApplicationException.BusinessSuspended;
import bg.spotyourslot.catalog.ServiceApplicationException.ConcurrentUpdate;
import bg.spotyourslot.catalog.ServiceApplicationException.InputField;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidInput;
import bg.spotyourslot.catalog.ServiceApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNameConflict;
import bg.spotyourslot.catalog.ServiceApplicationException.ServiceNotFound;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.math.BigDecimal;
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

@Import(ServiceAdministrationServiceIntegrationTests.FixedClockConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ServiceAdministrationServiceIntegrationTests extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

    @Autowired ServiceAdministration services;
    @Autowired JdbcClient jdbc;

    @Test
    void platformAdminWithActiveOwnerMembershipCanReadAndPerformEveryMutation() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        grantPlatformAdmin(fixture.userId());

        ServiceDetails created = services.create(fixture.context(), create("Haircut"));
        ServiceDetails updated = services.update(
                fixture.context(), created.id(), update("Haircut and beard", created.version()));
        ServiceDetails inactive = services.deactivate(
                fixture.context(), updated.id(), version(updated.version()));
        ServiceDetails active = services.reactivate(
                fixture.context(), inactive.id(), version(inactive.version()));

        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(updated.name()).isEqualTo("Haircut and beard");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(2);
        assertThat(active.active()).isTrue();
        assertThat(active.version()).isEqualTo(3);
        assertThat(services.get(fixture.context(), created.id())).isEqualTo(active);
        assertThat(services.list(fixture.context(), 0, 20).services())
                .containsExactly(active);
    }

    @Test
    void tenantIsolationUsesNotFoundForForeignServiceAndNeverMutatesIt() {
        Fixture first = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        Fixture second = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        ServiceDetails foreign = services.create(first.context(), create("Protected"));

        assertThatThrownBy(() -> services.get(second.context(), foreign.id()))
                .isInstanceOf(ServiceNotFound.class);
        assertThatThrownBy(() -> services.update(
                        second.context(), foreign.id(), update("Intrusion", foreign.version())))
                .isInstanceOf(ServiceNotFound.class);
        assertThatThrownBy(() -> services.deactivate(
                        second.context(), foreign.id(), version(foreign.version())))
                .isInstanceOf(ServiceNotFound.class);
        assertThat(services.get(first.context(), foreign.id())).isEqualTo(foreign);
        assertThat(services.list(second.context(), 0, 20).services()).isEmpty();

        ServiceDetails inactive = services.deactivate(
                first.context(), foreign.id(), version(foreign.version()));
        Snapshot beforeForeignReactivation = serviceSnapshot(foreign.id());

        assertThatThrownBy(() -> services.reactivate(
                        second.context(), inactive.id(), version(inactive.version())))
                .isInstanceOf(ServiceNotFound.class);
        assertThat(serviceSnapshot(foreign.id())).isEqualTo(beforeForeignReactivation);
        assertThat(services.get(first.context(), foreign.id())).isEqualTo(inactive);
    }

    @ParameterizedTest
    @MethodSource("deniedMemberships")
    void nonqualifyingMembershipIsDeniedForRepresentativeReadAndMutation(
            String role, boolean active) {
        Fixture fixture = ownerFixture("ACTIVE", active, role);

        assertThatThrownBy(() -> services.list(fixture.context(), 0, 20))
                .isInstanceOf(BusinessAccessDenied.class)
                .hasMessage("Business access to Services is denied");
        assertThatThrownBy(() -> services.create(fixture.context(), create("Denied")))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @Test
    void platformAdminWithoutMembershipIsDeniedForServiceAdministration() {
        UUID businessId = business("ACTIVE");
        UUID userId = user();
        grantPlatformAdmin(userId);
        var context = new TestContext(userId, businessId);

        assertThatThrownBy(() -> services.list(context, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class)
                .hasMessage("Business access to Services is denied");
        assertThatThrownBy(() -> services.create(context, create("Denied")))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @Test
    void deniesUserWithoutMembershipAndSelectedBusinessThatNoLongerExists() {
        UUID userId = user();
        UUID businessId = business("ACTIVE");
        var withoutMembership = new TestContext(userId, businessId);
        var missingBusiness = new TestContext(userId, UUID.randomUUID());

        assertThatThrownBy(() -> services.list(withoutMembership, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> services.list(missingBusiness, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @Test
    void deniesMembershipThatBelongsToAnotherBusiness() {
        UUID selectedBusiness = business("ACTIVE");
        UUID membershipBusiness = business("ACTIVE");
        UUID userId = user();
        membership(membershipBusiness, userId, "BUSINESS_OWNER", true);
        var crossBusinessContext = new TestContext(userId, selectedBusiness);

        assertThatThrownBy(() -> services.list(crossBusinessContext, 0, 20))
                .isInstanceOf(BusinessAccessDenied.class);
        assertThatThrownBy(() -> services.create(crossBusinessContext, create("Denied")))
                .isInstanceOf(BusinessAccessDenied.class);
    }

    @Test
    void keepsAuthenticationAndMissingSelectionOutcomesDistinct() {
        UUID userId = user();

        assertThatThrownBy(() -> services.list(null, 0, 20))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> services.list(new TestContext(userId, null), 0, 20))
                .isInstanceOf(SelectedBusinessRequired.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesPermitReadsAndMutations(String status) {
        Fixture fixture = ownerFixture(status, true, "BUSINESS_OWNER");

        ServiceDetails created = services.create(fixture.context(), create(status + " service"));

        assertThat(services.get(fixture.context(), created.id())).isEqualTo(created);
        assertThat(services.list(fixture.context(), 0, 20).services())
                .containsExactly(created);
    }

    @Test
    void suspendedBusinessPermitsReadsAndRejectsEveryMutationWithoutChanges() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        ServiceDetails original = services.create(fixture.context(), create("Read only"));
        setBusinessStatus(fixture.businessId(), "SUSPENDED");

        assertThat(services.get(fixture.context(), original.id())).isEqualTo(original);
        assertThat(services.list(fixture.context(), 0, 20).services())
                .containsExactly(original);
        assertThatThrownBy(() -> services.create(fixture.context(), create("New")))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> services.update(
                        fixture.context(), original.id(), update("Changed", original.version())))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> services.deactivate(
                        fixture.context(), original.id(), version(original.version())))
                .isInstanceOf(BusinessSuspended.class);
        assertThatThrownBy(() -> services.reactivate(
                        fixture.context(), original.id(), version(original.version())))
                .isInstanceOf(BusinessSuspended.class);

        assertThat(serviceSnapshot(original.id()))
                .isEqualTo(new Snapshot(
                        original.name(), original.active(), original.version(), original.updatedAt()));
    }

    @Test
    void persistsCanonicalValuesAndExactAcceptedPriceAndMapsPagination() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        ServiceDetails beta = services.create(fixture.context(), new CreateServiceCommand(
                "  BeTa\u00A0 service  ",
                "  First line\r\nSecond line  ",
                45,
                new BigDecimal("25.50")));
        ServiceDetails alpha = services.create(fixture.context(), create("Alpha service"));
        ServiceDetails inactive = services.deactivate(
                fixture.context(), beta.id(), version(beta.version()));

        var first = services.list(fixture.context(), 0, 1);
        var second = services.list(fixture.context(), 1, 1);

        assertThat(inactive.name()).isEqualTo("BeTa service");
        assertThat(inactive.description()).isEqualTo("First line\r\nSecond line");
        assertThat(inactive.price()).isEqualByComparingTo("25.50");
        assertThat(databasePrice(inactive.id()).scale()).isEqualTo(2);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(2);
        assertThat(first.services()).containsExactly(alpha);
        assertThat(second.services()).containsExactly(inactive);
        assertThat(services.list(fixture.context(), 0, 20).services())
                .extracting(ServiceDetails::active)
                .containsExactly(true, false);
        assertThat(ServiceDetails.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("businessId");
    }

    @ParameterizedTest
    @MethodSource("invalidOperations")
    void validatesInputsThroughTheApplicationOutcome(String expectedField, InvalidCall call) {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        ServiceDetails existing = services.create(fixture.context(), create("Existing"));

        assertThatThrownBy(() -> call.invoke(services, fixture.context(), existing.id()))
                .isInstanceOf(InvalidInput.class)
                .extracting(failure -> ((InvalidInput) failure).field().name())
                .isEqualTo(expectedField);
    }

    @Test
    void translatesNormalizedNameConflictsOnCreateAndUpdateIncludingInactiveNames() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        ServiceDetails reserved = services.create(fixture.context(), create("Straße"));
        reserved = services.deactivate(
                fixture.context(), reserved.id(), version(reserved.version()));
        ServiceDetails other = services.create(fixture.context(), create("Other"));

        assertThatThrownBy(() -> services.create(fixture.context(), create(" STRASSE ")))
                .isInstanceOf(ServiceNameConflict.class)
                .hasNoCause();
        assertThatThrownBy(() -> services.update(
                        fixture.context(), other.id(), update("STRASSE", other.version())))
                .isInstanceOf(ServiceNameConflict.class)
                .hasNoCause();
    }

    @Test
    void classifiesStaleAndRepeatedLifecycleMutationsPrecisely() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        ServiceDetails created = services.create(fixture.context(), create("Versioned"));
        ServiceDetails updated = services.update(
                fixture.context(), created.id(), update("Updated", created.version()));

        assertThatThrownBy(() -> services.update(
                        fixture.context(), created.id(), update("Stale", created.version())))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> services.deactivate(
                        fixture.context(), created.id(), version(created.version())))
                .isInstanceOf(ConcurrentUpdate.class);
        ServiceDetails inactive = services.deactivate(
                fixture.context(), updated.id(), version(updated.version()));
        assertThatThrownBy(() -> services.deactivate(
                        fixture.context(), inactive.id(), version(inactive.version())))
                .isInstanceOf(InvalidLifecycleTransition.class);
        assertThatThrownBy(() -> services.reactivate(
                        fixture.context(), inactive.id(), version(updated.version())))
                .isInstanceOf(ConcurrentUpdate.class);
        ServiceDetails active = services.reactivate(
                fixture.context(), inactive.id(), version(inactive.version()));
        assertThatThrownBy(() -> services.reactivate(
                        fixture.context(), active.id(), version(active.version())))
                .isInstanceOf(InvalidLifecycleTransition.class);
    }

    @Test
    void preservesCauseForUnexpectedPostgresqlFailure() {
        Fixture fixture = ownerFixture("ACTIVE", true, "BUSINESS_OWNER");
        jdbc.sql("""
                        CREATE FUNCTION phase3_reject_service() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'phase3 forced failure' USING ERRCODE = 'XX000';
                        END
                        $$
                        """)
                .update();
        jdbc.sql("""
                        CREATE TRIGGER phase3_reject_service_trigger
                        BEFORE INSERT ON service
                        FOR EACH ROW EXECUTE FUNCTION phase3_reject_service()
                        """)
                .update();

        try {
            assertThatThrownBy(() -> services.create(fixture.context(), create("Failure")))
                    .isInstanceOf(UnexpectedFailure.class)
                    .hasMessage("Service persistence operation failed")
                    .hasCauseInstanceOf(Exception.class);
        } finally {
            jdbc.sql("DROP TRIGGER phase3_reject_service_trigger ON service").update();
            jdbc.sql("DROP FUNCTION phase3_reject_service()").update();
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
                Arguments.of("NAME", (InvalidCall) (service, context, id) ->
                        service.create(context, new CreateServiceCommand(" ", null, 30, BigDecimal.ONE))),
                Arguments.of("DESCRIPTION", (InvalidCall) (service, context, id) ->
                        service.create(context, new CreateServiceCommand(
                                "Valid", "x".repeat(2_001), 30, BigDecimal.ONE))),
                Arguments.of("DURATION_MINUTES", (InvalidCall) (service, context, id) ->
                        service.create(context, new CreateServiceCommand(
                                "Valid", null, 0, BigDecimal.ONE))),
                Arguments.of("PRICE", (InvalidCall) (service, context, id) ->
                        service.create(context, new CreateServiceCommand(
                                "Valid", null, 30, new BigDecimal("1.001")))),
                Arguments.of("PAGE", (InvalidCall) (service, context, id) ->
                        service.list(context, -1, 20)),
                Arguments.of("SIZE", (InvalidCall) (service, context, id) ->
                        service.list(context, 0, 0)),
                Arguments.of("SERVICE_ID", (InvalidCall) (service, context, id) ->
                        service.get(context, null)),
                Arguments.of("EXPECTED_VERSION", (InvalidCall) (service, context, id) ->
                        service.update(context, id, update("Valid", -1))),
                Arguments.of("EXPECTED_VERSION", (InvalidCall) (service, context, id) ->
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
        String slug = "business-" + id.toString().substring(0, 8);
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Test Business','OTHER',:status,'Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
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

    private Snapshot serviceSnapshot(UUID serviceId) {
        return jdbc.sql("SELECT name,active,version,updated_at FROM service WHERE id=:id")
                .param("id", serviceId)
                .query((resultSet, rowNumber) -> new Snapshot(
                        resultSet.getString("name"),
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version"),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private BigDecimal databasePrice(UUID serviceId) {
        return jdbc.sql("SELECT price FROM service WHERE id=:id")
                .param("id", serviceId)
                .query(BigDecimal.class)
                .single();
    }

    private OffsetDateTime databaseNow() {
        return NOW.atOffset(ZoneOffset.UTC);
    }

    private static CreateServiceCommand create(String name) {
        return new CreateServiceCommand(name, "Description", 30, new BigDecimal("20.00"));
    }

    private static UpdateServiceCommand update(String name, long version) {
        return new UpdateServiceCommand(
                name, "Updated description", 45, new BigDecimal("25.50"), version);
    }

    private static ServiceVersionCommand version(long version) {
        return new ServiceVersionCommand(version);
    }

    @FunctionalInterface
    private interface InvalidCall {
        Object invoke(
                ServiceAdministration service,
                AuthenticatedBusinessContext context,
                UUID serviceId);
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

    private record Snapshot(String name, boolean active, long version, Instant updatedAt) {
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedServiceAdministrationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
