package bg.spotyourslot.integration;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.identity.domain.TokenCodec;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
@Sql(
        statements = "TRUNCATE business,app_user CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessServiceApiIntegrationTests extends PostgresIntegrationTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final UUID MISSING_SERVICE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000404");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TokenCodec tokens;
    @Autowired Clock clock;

    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @Test
    void activeOwnerCompletesCrudAndLifecycleJourneyWithExactVersionChanges()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);

        String creationBody = create(owner, "  Haircut\u00A0 and beard  ", "  Description  ",
                        30, "25.50", null)
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", containsString("/api/business/services/")))
                .andExpect(jsonPath("$", aMapWithSize(9)))
                .andExpect(jsonPath("$.name").value("Haircut and beard"))
                .andExpect(jsonPath("$.description").value("Description"))
                .andExpect(jsonPath("$.price").value(25.50))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.businessId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID serviceId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(creationBody, "$.id"));
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT price FROM service WHERE id=:id")
                .param("id", serviceId)
                .query(BigDecimal.class)
                .single())
                .isEqualByComparingTo(new BigDecimal("25.50"))
                .satisfies(price -> org.assertj.core.api.Assertions.assertThat(price.scale())
                        .isEqualTo(2));

        mvc.perform(get("/api/business/services/{serviceId}", serviceId)
                        .cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serviceId.toString()))
                .andExpect(jsonPath("$.businessId").doesNotExist());

        mvc.perform(get("/api/business/services")
                        .cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].id").value(serviceId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));

        update(owner, serviceId, "Updated", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated"))
                .andExpect(jsonPath("$.version").value(1));
        lifecycle(owner, serviceId, "deactivate", 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(2));
        lifecycle(owner, serviceId, "reactivate", 2, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(3));

        assertServiceState(serviceId, "Updated", true, 3);
    }

    @Test
    void listingIncludesActiveAndInactiveServicesWithDeterministicPagination()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID beta = insertService(owner.businessId(), "Beta", false, 2);
        UUID alphaSecond = insertService(owner.businessId(), "Alpha second", true, 0);
        UUID alphaFirst = UUID.fromString("00000000-0000-0000-0000-000000000001");
        insertService(alphaFirst, owner.businessId(), "Alpha first", true, 0);

        mvc.perform(get("/api/business/services")
                        .cookie(owner.session())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].id").value(alphaFirst.toString()))
                .andExpect(jsonPath("$.services[1].id").value(alphaSecond.toString()))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/business/services")
                        .cookie(owner.session())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].id").value(beta.toString()))
                .andExpect(jsonPath("$.services[0].active").value(false))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void unauthenticatedRequestRetainsTheExistingSafe401Contract() throws Exception {
        mvc.perform(get("/api/business/services"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void realSessionFilterClearsMissingInactiveAndUnrelatedSelections() throws Exception {
        Actor noSelection = actor("ACTIVE", "BUSINESS_OWNER", true, false, false);
        Actor inactive = actor("ACTIVE", "BUSINESS_OWNER", false, false, true);
        Actor unrelated = actorWithUnrelatedSelectedBusiness();

        assertActiveBusinessRequired(mvc.perform(get("/api/business/services")
                .cookie(noSelection.session())));
        assertActiveBusinessRequired(mvc.perform(get("/api/business/services")
                .cookie(inactive.session())));
        assertActiveBusinessRequired(mvc.perform(get("/api/business/services")
                .cookie(unrelated.session())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void retainedNonOwnerSelectionReachesApplicationAccessDenial(String role)
            throws Exception {
        Actor actor = actor("ACTIVE", role, true, false, true);

        assertAccessDenied(mvc.perform(get("/api/business/services")
                .cookie(actor.session())));
        assertAccessDenied(create(actor, "Denied", null, 30, "20.00", null));
    }

    @Test
    void platformRoleAloneIsInsufficientButPlatformAdminOwnerIsAllowed()
            throws Exception {
        Actor platformOnly = actor("ACTIVE", null, false, true, true);
        Actor platformOwner = actor("ACTIVE", "BUSINESS_OWNER", true, true, true);

        assertActiveBusinessRequired(mvc.perform(get("/api/business/services")
                .cookie(platformOnly.session())));
        create(platformOwner, "Allowed", null, 30, "20.00", null)
                .andExpect(status().isCreated());
        mvc.perform(get("/api/business/services").cookie(platformOwner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void tenantIsolationMakesForeignAndMissingIdsIndistinguishableAndImmutable()
            throws Exception {
        Actor first = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        Actor second = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID activeForeign = insertService(first.businessId(), "Foreign active", true, 4);
        UUID inactiveForeign = insertService(first.businessId(), "Foreign inactive", false, 7);

        assertServiceNotFound(mvc.perform(get(
                        "/api/business/services/{serviceId}", activeForeign)
                .cookie(second.session())));
        assertServiceNotFound(mvc.perform(get(
                        "/api/business/services/{serviceId}", MISSING_SERVICE_ID)
                .cookie(second.session())));
        assertServiceNotFound(update(second, activeForeign, "Intrusion", 4));
        assertServiceNotFound(lifecycle(second, activeForeign, "deactivate", 4, true));
        assertServiceNotFound(lifecycle(second, inactiveForeign, "reactivate", 7, true));

        assertServiceState(activeForeign, "Foreign active", true, 4);
        assertServiceState(inactiveForeign, "Foreign inactive", false, 7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesAllowMutations(String status) throws Exception {
        Actor owner = actor(status, "BUSINESS_OWNER", true, false, true);

        create(owner, status + " service", null, 30, "20.00", null)
                .andExpect(status().isCreated());
    }

    @Test
    void suspendedBusinessAllowsReadsAndRejectsEveryMutation() throws Exception {
        Actor owner = actor("SUSPENDED", "BUSINESS_OWNER", true, false, true);
        UUID active = insertService(owner.businessId(), "Read only", true, 2);
        UUID inactive = insertService(owner.businessId(), "Inactive", false, 3);

        mvc.perform(get("/api/business/services").cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/business/services/{serviceId}", active)
                        .cookie(owner.session()))
                .andExpect(status().isOk());
        assertBusinessSuspended(create(owner, "Denied", null, 30, "20.00", null));
        assertBusinessSuspended(update(owner, active, "Denied", 2));
        assertBusinessSuspended(lifecycle(owner, active, "deactivate", 2, true));
        assertBusinessSuspended(lifecycle(owner, inactive, "reactivate", 3, true));
        assertServiceState(active, "Read only", true, 2);
        assertServiceState(inactive, "Inactive", false, 3);
    }

    @Test
    void normalizedNameConflictsIncludeInactiveReservedNames() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID reserved = insertService(owner.businessId(), "Straße", false, 1);
        UUID other = insertService(owner.businessId(), "Other", true, 0);

        assertNameConflict(create(owner, " STRASSE ", null, 30, "20.00", null));
        assertNameConflict(update(owner, other, "STRASSE", 0));
        assertServiceState(reserved, "Straße", false, 1);
        assertServiceState(other, "Other", true, 0);
    }

    @Test
    void staleVersionsAndRepeatedLifecycleTransitionsUseStableConflicts()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID active = insertService(owner.businessId(), "Versioned", true, 2);
        UUID inactive = insertService(owner.businessId(), "Inactive", false, 4);

        assertConcurrent(update(owner, active, "Stale", 1));
        assertConcurrent(lifecycle(owner, active, "deactivate", 1, true));
        assertConcurrent(lifecycle(owner, inactive, "reactivate", 3, true));
        assertInvalidLifecycle(lifecycle(owner, active, "reactivate", 2, true));
        assertInvalidLifecycle(lifecycle(owner, inactive, "deactivate", 4, true));
        assertServiceState(active, "Versioned", true, 2);
        assertServiceState(inactive, "Inactive", false, 4);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidPayloadsAndNumericBoundariesUseOneSafeValidationOutcome(
            String path, String method, String body) throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID serviceId = insertService(owner.businessId(), "Existing", true, 0);
        ResultActions result = switch (method) {
            case "CREATE" -> mvc.perform(post("/api/business/services")
                    .with(csrf())
                    .cookie(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
            case "UPDATE" -> mvc.perform(put(
                            "/api/business/services/{serviceId}", serviceId)
                    .with(csrf())
                    .cookie(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
            case "LIFECYCLE" -> mvc.perform(post(
                            "/api/business/services/{serviceId}/deactivate", serviceId)
                    .with(csrf())
                    .cookie(owner.session())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
            default -> mvc.perform(get(path).cookie(owner.session()));
        };

        assertValidationError(result);
    }

    @Test
    void malformedUuidJsonAndQueryTypesUseTheSafeValidationOutcome() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);

        assertValidationError(mvc.perform(get("/api/business/services/not-a-uuid")
                .cookie(owner.session())));
        assertValidationError(mvc.perform(get("/api/business/services")
                .cookie(owner.session())
                .param("page", "not-a-number")));
        assertValidationError(mvc.perform(post("/api/business/services")
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{")));
    }

    @Test
    void csrfProtectsCreateUpdateDeactivateAndReactivate() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID active = insertService(owner.businessId(), "Active", true, 0);
        UUID inactive = insertService(owner.businessId(), "Inactive", false, 0);

        assertAccessDenied(create(owner, "No csrf", null, 30, "20.00", false));
        assertAccessDenied(update(owner, active, "No csrf", 0, false));
        assertAccessDenied(lifecycle(owner, active, "deactivate", 0, false));
        assertAccessDenied(lifecycle(owner, inactive, "reactivate", 0, false));
    }

    @Test
    void clientBusinessIdCannotRedirectServiceCreation() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID otherBusiness = business("ACTIVE");

        create(owner, "Selected only", null, 30, "20.00", otherBusiness)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.businessId").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                        "SELECT business_id FROM service WHERE name='Selected only'")
                .query(UUID.class)
                .single()).isEqualTo(owner.businessId());
    }

    @Test
    void unexpectedPersistenceFailureUsesGenericProblemWithoutDiagnostics()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        jdbc.sql("""
                        CREATE FUNCTION phase4_reject_service() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'private phase4 SQL diagnostic'
                                USING ERRCODE = 'XX000';
                        END
                        $$
                        """)
                .update();
        jdbc.sql("""
                        CREATE TRIGGER phase4_reject_service_trigger
                        BEFORE INSERT ON service
                        FOR EACH ROW EXECUTE FUNCTION phase4_reject_service()
                        """)
                .update();

        try {
            create(owner, "Failure", null, 30, "20.00", null)
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.detail").value("Възникна неочаквана грешка."))
                    .andExpect(content().string(not(containsString("private phase4"))))
                    .andExpect(content().string(not(containsString("XX000"))))
                    .andExpect(content().string(not(containsString("SQL"))))
                    .andExpect(content().string(not(containsString("constraint"))))
                    .andExpect(content().string(not(containsString("exception"))))
                    .andExpect(content().string(not(containsString("stackTrace"))));
        } finally {
            jdbc.sql("DROP TRIGGER phase4_reject_service_trigger ON service").update();
            jdbc.sql("DROP FUNCTION phase4_reject_service()").update();
        }
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("", "CREATE", createJson(null, null, 30, "20.00", null)),
                Arguments.of("", "CREATE", createJson(" ", null, 30, "20.00", null)),
                Arguments.of("", "CREATE", createJson("x".repeat(201), null, 30, "20.00", null)),
                Arguments.of("", "CREATE", createJson("Valid", "x".repeat(2_001), 30, "20.00", null)),
                Arguments.of("", "CREATE", createJson("Valid", null, null, "20.00", null)),
                Arguments.of("", "CREATE", createJson("Valid", null, 0, "20.00", null)),
                Arguments.of("", "CREATE", createJson("Valid", null, 481, "20.00", null)),
                Arguments.of("", "CREATE", createJson("Valid", null, 30, null, null)),
                Arguments.of("", "CREATE", createJson("Valid", null, 30, "-0.01", null)),
                Arguments.of("", "CREATE", createJson("Valid", null, 30, "1.001", null)),
                Arguments.of("", "CREATE", createJson("Valid", null, 30, "10000000000.00", null)),
                Arguments.of("", "UPDATE", createJson("Valid", null, 30, "20.00", -1L)),
                Arguments.of("", "LIFECYCLE", "{\"expectedVersion\":null}"),
                Arguments.of("", "LIFECYCLE", "{\"expectedVersion\":-1}"),
                Arguments.of("/api/business/services?page=-1", "GET", ""),
                Arguments.of("/api/business/services?size=0", "GET", ""),
                Arguments.of("/api/business/services?size=101", "GET", ""));
    }

    private Actor actor(
            String businessStatus,
            String role,
            boolean activeMembership,
            boolean platformAdmin,
            boolean selectBusiness) {
        UUID businessId = business(businessStatus);
        UUID userId = user();
        if (role != null) {
            membership(userId, businessId, role, activeMembership);
        }
        if (platformAdmin) {
            jdbc.sql("""
                            INSERT INTO platform_role(user_id,role,created_at)
                            VALUES (:user,'PLATFORM_ADMIN',:now)
                            """)
                    .param("user", userId)
                    .param("now", now)
                    .update();
        }
        Cookie session = session(userId, selectBusiness ? businessId : null);
        return new Actor(businessId, userId, session);
    }

    private Actor actorWithUnrelatedSelectedBusiness() {
        UUID membershipBusiness = business("ACTIVE");
        UUID selectedBusiness = business("ACTIVE");
        UUID userId = user();
        membership(userId, membershipBusiness, "BUSINESS_OWNER", true);
        return new Actor(selectedBusiness, userId, session(userId, selectedBusiness));
    }

    private UUID business(String status) {
        UUID id = UUID.randomUUID();
        String slug = "api-service-" + SEQUENCE.incrementAndGet();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Service Business','OTHER',:status,'Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,active,locked,
                            credential_version,password_changed_at,created_at,updated_at)
                        VALUES (
                            :id,:email,'Service User','unused',true,false,
                            1,:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", id + "@example.invalid")
                .param("now", now)
                .update();
        return id;
    }

    private void membership(UUID userId, UUID businessId, String role, boolean active) {
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:business,:user,:role,:active,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("business", businessId)
                .param("user", userId)
                .param("role", role)
                .param("active", active)
                .param("now", now)
                .update();
    }

    private Cookie session(UUID userId, UUID selectedBusinessId) {
        String rawToken = "service-session-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO user_session(
                            id,token_hash,user_id,active_business_id,credential_version,
                            created_at,last_activity_at,absolute_expires_at)
                        VALUES (:id,:hash,:user,:business,1,:now,:now,:expires)
                        """)
                .param("id", UUID.randomUUID())
                .param("hash", tokens.hash(rawToken))
                .param("user", userId)
                .param("business", selectedBusinessId)
                .param("now", now)
                .param("expires", now.plusHours(12))
                .update();
        return new Cookie("SPOTYOURSESSION", rawToken);
    }

    private UUID insertService(UUID businessId, String name, boolean active, long version) {
        UUID id = UUID.randomUUID();
        insertService(id, businessId, name, active, version);
        return id;
    }

    private void insertService(
            UUID id, UUID businessId, String name, boolean active, long version) {
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (
                            :id,:business,:name,'Description',30,20.00,
                            :active,:version,:now,:now)
                        """)
                .param("id", id)
                .param("business", businessId)
                .param("name", name)
                .param("active", active)
                .param("version", version)
                .param("now", now)
                .update();
    }

    private ResultActions create(
            Actor actor,
            String name,
            String description,
            Integer duration,
            String price,
            UUID clientBusinessId) throws Exception {
        return create(actor, name, description, duration, price, clientBusinessId, true);
    }

    private ResultActions create(
            Actor actor,
            String name,
            String description,
            Integer duration,
            String price,
            boolean withCsrf) throws Exception {
        return create(actor, name, description, duration, price, null, withCsrf);
    }

    private ResultActions create(
            Actor actor,
            String name,
            String description,
            Integer duration,
            String price,
            UUID clientBusinessId,
            boolean withCsrf) throws Exception {
        var request = post("/api/business/services")
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJson(name, description, duration, price, null, clientBusinessId));
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions update(Actor actor, UUID serviceId, String name, long version)
            throws Exception {
        return update(actor, serviceId, name, version, true);
    }

    private ResultActions update(
            Actor actor, UUID serviceId, String name, long version, boolean withCsrf)
            throws Exception {
        var request = put("/api/business/services/{serviceId}", serviceId)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJson(name, "Updated", 45, "30.00", version));
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions lifecycle(
            Actor actor,
            UUID serviceId,
            String operation,
            long version,
            boolean withCsrf) throws Exception {
        var request = post(
                        "/api/business/services/{serviceId}/{operation}",
                        serviceId,
                        operation)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + version + "}");
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private static String createJson(
            String name,
            String description,
            Integer duration,
            String price,
            Long expectedVersion) {
        return createJson(name, description, duration, price, expectedVersion, null);
    }

    private static String createJson(
            String name,
            String description,
            Integer duration,
            String price,
            Long expectedVersion,
            UUID businessId) {
        return """
                {
                  "name": %s,
                  "description": %s,
                  "durationMinutes": %s,
                  "price": %s%s%s
                }
                """.formatted(
                jsonString(name),
                jsonString(description),
                duration == null ? "null" : duration,
                price == null ? "null" : price,
                expectedVersion == null ? "" : ",\n  \"expectedVersion\": " + expectedVersion,
                businessId == null ? "" : ",\n  \"businessId\": \"" + businessId + "\"");
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void assertServiceState(UUID id, String name, boolean active, long version) {
        ServiceState state = jdbc.sql("""
                        SELECT name,active,version FROM service WHERE id=:id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new ServiceState(
                        resultSet.getString("name"),
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version")))
                .single();
        org.assertj.core.api.Assertions.assertThat(state)
                .isEqualTo(new ServiceState(name, active, version));
    }

    private void assertValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("exception"))));
    }

    private void assertActiveBusinessRequired(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_BUSINESS_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Изберете бизнес, за да продължите."));
    }

    private void assertAccessDenied(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value("Нямате достъп до тази операция."));
    }

    private void assertServiceNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Услугата не е намерена."));
    }

    private void assertNameConflict(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_NAME_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Вече съществува услуга с това име."));
    }

    private void assertConcurrent(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_CONCURRENT_UPDATE"));
    }

    private void assertInvalidLifecycle(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INVALID_LIFECYCLE"));
    }

    private void assertBusinessSuspended(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"))
                .andExpect(jsonPath("$.detail")
                        .value("Спрян бизнес може само да преглежда данните си."));
    }

    private record Actor(UUID businessId, UUID userId, Cookie session) {
    }

    private record ServiceState(String name, boolean active, long version) {
    }
}
