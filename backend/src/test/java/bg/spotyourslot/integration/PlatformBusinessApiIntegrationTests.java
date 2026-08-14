package bg.spotyourslot.integration;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.identity.domain.TokenCodec;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
        statements =
                "TRUNCATE user_session,password_reset,owner_invitation,membership,platform_role,app_user,business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PlatformBusinessApiIntegrationTests extends PostgresIntegrationTest {
    private static final String APPROVED_ORIGIN = "http://localhost:5173";
    private static final UUID MISSING_BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000404");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TokenCodec tokens;
    @Autowired Clock clock;

    private UUID adminUserId;
    private Cookie adminSession;
    private OffsetDateTime now;

    @BeforeEach
    void fixture() {
        now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        adminUserId = createUser("platform-admin@example.invalid");
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',:now)")
                .param("id", adminUserId)
                .param("now", now)
                .update();
        adminSession = createSession(adminUserId, "platform-admin-session");
    }

    @Test
    void unauthenticatedRequestUsesStructuredAuthenticationEntryPoint() throws Exception {
        mvc.perform(get("/api/platform/businesses"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void authenticatedUserWithoutPlatformAuthorityIsDenied() throws Exception {
        UUID userId = createUser("ordinary@example.invalid");
        Cookie session = createSession(userId, "ordinary-session");

        assertAccessDenied(mvc.perform(get("/api/platform/businesses").cookie(session)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUSINESS_OWNER", "MANAGER", "STAFF"})
    void businessMembershipRolesDoNotGrantPlatformAuthority(String role) throws Exception {
        UUID businessId = createBusinessRow("membership-business", "DRAFT");
        UUID userId = createUser(role.toLowerCase() + "@example.invalid");
        createMembership(userId, businessId, role, true);
        Cookie session = createSession(userId, role.toLowerCase() + "-session");

        assertAccessDenied(mvc.perform(get("/api/platform/businesses").cookie(session)));
    }

    @Test
    void clientSuppliedRoleAndBusinessIdCannotGrantPlatformAccess() throws Exception {
        UUID businessId = createBusinessRow("client-claimed-business", "DRAFT");
        UUID userId = createUser("claimant@example.invalid");
        Cookie session = createSession(userId, "claimant-session");

        assertAccessDenied(mvc.perform(get("/api/platform/businesses")
                .cookie(session)
                .param("role", "PLATFORM_ADMIN")
                .param("businessId", businessId.toString())
                .header("X-Role", "PLATFORM_ADMIN")
                .header("X-Business-Id", businessId)));
    }

    @Test
    void platformAdminCanCreateListGetAndUpdateBusiness() throws Exception {
        UUID businessId = createBusinessThroughApi("api-business");

        mvc.perform(get("/api/platform/businesses").cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.businesses[0].id").value(businessId.toString()));

        mvc.perform(get("/api/platform/businesses/{businessId}", businessId)
                        .cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(13)))
                .andExpect(jsonPath("$.id").value(businessId.toString()))
                .andExpect(jsonPath("$.slug").value("api-business"))
                .andExpect(jsonPath("$.businessType").value("OTHER"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.membership").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.session").doesNotExist());

        mvc.perform(put("/api/platform/businesses/{businessId}", businessId)
                        .with(csrf())
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("updated-api-business", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("updated-api-business"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void platformAdminCanActivateSuspendAndReactivateBusiness() throws Exception {
        UUID businessId = createBusinessThroughApi("lifecycle-business");
        createMembership(adminUserId, businessId, "BUSINESS_OWNER", true);

        performLifecycle(businessId, "activate", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));

        performLifecycle(businessId, "suspend", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.version").value(2));

        jdbc.sql("UPDATE membership SET active=false WHERE business_id=:business")
                .param("business", businessId)
                .update();
        performLifecycle(businessId, "reactivate", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void activationRequiresOwnerFromTheSameBusiness() throws Exception {
        UUID target = createBusinessThroughApi("target-business");
        UUID other = createBusinessThroughApi("other-business");
        UUID ownerId = createUser("other-owner@example.invalid");
        createMembership(ownerId, other, "BUSINESS_OWNER", true);

        performLifecycle(target, "activate", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_MISSING_ACTIVE_OWNER"));
        assertBusinessState(target, "DRAFT", 0);
    }

    @Test
    void inactiveOwnerDoesNotPermitActivation() throws Exception {
        UUID businessId = createBusinessThroughApi("inactive-owner-business");
        createMembership(adminUserId, businessId, "BUSINESS_OWNER", false);

        performLifecycle(businessId, "activate", 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_MISSING_ACTIVE_OWNER"));
        assertBusinessState(businessId, "DRAFT", 0);
    }

    @Test
    void duplicateSlugAndMissingBusinessUseStableErrors() throws Exception {
        createBusinessThroughApi("duplicate-business");

        mvc.perform(post("/api/platform/businesses")
                        .with(csrf())
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("duplicate-business")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SLUG_CONFLICT"));

        mvc.perform(get("/api/platform/businesses/{businessId}", MISSING_BUSINESS_ID)
                        .cookie(adminSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_FOUND"));
    }

    @Test
    void invalidLifecycleAndStaleVersionUseStableErrors() throws Exception {
        UUID businessId = createBusinessThroughApi("conflict-business");
        createMembership(adminUserId, businessId, "BUSINESS_OWNER", true);
        performLifecycle(businessId, "activate", 0).andExpect(status().isOk());

        performLifecycle(businessId, "activate", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_INVALID_LIFECYCLE"));

        mvc.perform(put("/api/platform/businesses/{businessId}", businessId)
                        .with(csrf())
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("stale-business", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONCURRENT_UPDATE"));
    }

    @Test
    void malformedAndInvalidInputsUseSafeValidationError() throws Exception {
        assertValidationError(mvc.perform(get("/api/platform/businesses/not-a-uuid")
                .cookie(adminSession)));
        assertValidationError(mvc.perform(post("/api/platform/businesses")
                .with(csrf())
                .cookie(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{")));
        assertValidationError(mvc.perform(post("/api/platform/businesses")
                .with(csrf())
                .cookie(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJsonWithType("invalid-type", "UNKNOWN"))));
        assertValidationError(mvc.perform(get("/api/platform/businesses")
                .cookie(adminSession)
                .param("page", "-1")));

        UUID businessId = createBusinessThroughApi("invalid-version-business");
        assertValidationError(mvc.perform(post(
                        "/api/platform/businesses/{businessId}/suspend", businessId)
                .with(csrf())
                .cookie(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":-1}")));
    }

    @Test
    void csrfFailuresUseStructuredAccessDeniedResponseForPostAndPut() throws Exception {
        assertAccessDenied(mvc.perform(post("/api/platform/businesses")
                .cookie(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJson("csrf-post-business"))));

        UUID businessId = createBusinessThroughApi("csrf-put-business");
        assertAccessDenied(mvc.perform(put("/api/platform/businesses/{businessId}", businessId)
                .cookie(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson("csrf-put-updated", 0))));
    }

    @Test
    void corsAllowsCredentialedExactOriginAndPutPreflightOnlyForThatOrigin()
            throws Exception {
        mvc.perform(get("/api/platform/businesses")
                        .cookie(adminSession)
                        .header("Origin", APPROVED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", APPROVED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mvc.perform(options("/api/platform/businesses/{businessId}", MISSING_BUSINESS_ID)
                        .header("Origin", APPROVED_ORIGIN)
                        .header("Access-Control-Request-Method", "PUT")
                        .header(
                                "Access-Control-Request-Headers",
                                "Content-Type, X-XSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", APPROVED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string(
                        "Access-Control-Allow-Methods", containsString("PUT")));

        mvc.perform(options("/api/platform/businesses/{businessId}", MISSING_BUSINESS_ID)
                        .header("Origin", "https://unapproved.invalid")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void problemAndBusinessResponsesDoNotExposeSensitiveInternals() throws Exception {
        UUID businessId = createBusinessThroughApi("privacy-business");

        mvc.perform(get("/api/platform/businesses/{businessId}", businessId)
                        .cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("membership"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("session"))))
                .andExpect(content().string(not(containsString("token"))))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("constraint"))))
                .andExpect(content().string(not(containsString("stackTrace"))));

        mvc.perform(get("/api/platform/businesses/{businessId}", MISSING_BUSINESS_ID)
                        .cookie(adminSession))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("constraint"))))
                .andExpect(content().string(not(containsString("exception"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    private UUID createBusinessThroughApi(String slug) throws Exception {
        mvc.perform(post("/api/platform/businesses")
                        .with(csrf())
                        .cookie(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(slug)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/platform/businesses/")))
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0));
        return jdbc.sql("SELECT id FROM business WHERE slug=:slug")
                .param("slug", slug)
                .query(UUID.class)
                .single();
    }

    private ResultActions performLifecycle(UUID businessId, String operation, long version)
            throws Exception {
        return mvc.perform(post(
                        "/api/platform/businesses/{businessId}/{operation}",
                        businessId,
                        operation)
                .with(csrf())
                .cookie(adminSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + version + "}"));
    }

    private void assertAccessDenied(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value("Нямате достъп до тази операция."))
                .andExpect(content().string(not(containsString("session"))))
                .andExpect(content().string(not(containsString("role"))))
                .andExpect(content().string(not(containsString("exception"))));
    }

    private void assertValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("exception"))));
    }

    private void assertBusinessState(UUID businessId, String status, long version) {
        var state = jdbc.sql("SELECT status,version FROM business WHERE id=:id")
                .param("id", businessId)
                .query((result, row) -> new BusinessState(
                        result.getString("status"), result.getLong("version")))
                .single();
        org.assertj.core.api.Assertions.assertThat(state)
                .isEqualTo(new BusinessState(status, version));
    }

    private UUID createUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,active,locked,
                            credential_version,password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Integration User','unused',true,false,1,:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", email)
                .param("now", now)
                .update();
        return id;
    }

    private Cookie createSession(UUID userId, String rawToken) {
        jdbc.sql("""
                        INSERT INTO user_session(
                            id,token_hash,user_id,credential_version,created_at,last_activity_at,
                            absolute_expires_at)
                        VALUES (:id,:hash,:user,1,:now,:now,:expires)
                        """)
                .param("id", UUID.randomUUID())
                .param("hash", tokens.hash(rawToken))
                .param("user", userId)
                .param("now", now)
                .param("expires", now.plusHours(12))
                .update();
        return new Cookie("SPOTYOURSESSION", rawToken);
    }

    private UUID createBusinessRow(String slug, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,:slug,'OTHER',:status,'Europe/Sofia',:now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }

    private void createMembership(UUID userId, UUID businessId, String role, boolean active) {
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

    private String createJson(String slug) {
        return createJsonWithType(slug, "OTHER");
    }

    private String createJsonWithType(String slug, String type) {
        return """
                {
                  "slug": "%s",
                  "displayName": "Integration Business",
                  "businessType": "%s",
                  "timezone": "Europe/Sofia",
                  "description": "Описание",
                  "address": "София",
                  "phone": "+359 2 000 0000",
                  "contactEmail": "contact@example.invalid"
                }
                """.formatted(slug, type);
    }

    private String updateJson(String slug, long expectedVersion) {
        return """
                {
                  "slug": "%s",
                  "displayName": "Updated Integration Business",
                  "businessType": "BEAUTY_STUDIO",
                  "timezone": "Europe/Sofia",
                  "expectedVersion": %d
                }
                """.formatted(slug, expectedVersion);
    }

    private record BusinessState(String status, long version) {
    }
}
