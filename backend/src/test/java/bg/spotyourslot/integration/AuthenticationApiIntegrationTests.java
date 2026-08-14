package bg.spotyourslot.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Sql(
        statements =
                "TRUNCATE user_session,password_reset,owner_invitation,membership,platform_role,app_user,business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuthenticationApiIntegrationTests extends PostgresIntegrationTest {
    private static final String EMAIL = "owner@example.invalid";
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;

    private UUID userId;
    private UUID businessA;
    private UUID businessB;

    @BeforeEach
    void fixture() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        userId = createUser(EMAIL, PASSWORD, true, false, now);
        businessA = createBusiness("business-a", now);
        businessB = createBusiness("business-b", now);
        createMembership(userId, businessA, "MANAGER", true, now);
    }

    @Test
    void rejectsUnsafeLoginWithoutCsrf() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSucceedsAndInvalidLoginIsGeneric() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, "wrong password value")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"))
                .andExpect(content().string(not(containsString(EMAIL))));

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("SPOTYOURSESSION", true))
                .andExpect(cookie().sameSite("SPOTYOURSESSION", "Lax"))
                .andExpect(jsonPath("$.businesses.length()").value(1))
                .andExpect(jsonPath("$.businesses[0].role").value("MANAGER"));
    }

    @Test
    void activeAndLockedUsersReceiveTheSameGenericRejection() throws Exception {
        jdbc.sql("UPDATE app_user SET active=false WHERE id=:id").param("id", userId).update();
        assertGenericLoginRejection();
        jdbc.sql("UPDATE app_user SET active=true,locked=true WHERE id=:id")
                .param("id", userId)
                .update();
        assertGenericLoginRejection();
    }

    @Test
    void currentSessionLogoutAndDatabaseRevocationWork() throws Exception {
        Cookie session = login();
        mvc.perform(get("/api/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(session))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("SPOTYOURSESSION", 0));
        mvc.perform(get("/api/auth/session").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void absoluteAndIdleExpiredSessionsAreRejectedThroughTheFilter() throws Exception {
        Cookie absolute = login();
        jdbc.sql("UPDATE user_session SET absolute_expires_at=now() WHERE token_hash IS NOT NULL")
                .update();
        mvc.perform(get("/api/auth/session").cookie(absolute)).andExpect(status().isUnauthorized());

        jdbc.sql("DELETE FROM user_session").update();
        Cookie idle = login();
        jdbc.sql("UPDATE user_session SET last_activity_at=now() - interval '2 hours'").update();
        mvc.perform(get("/api/auth/session").cookie(idle)).andExpect(status().isUnauthorized());
    }

    @Test
    void staleBusinessSelectionIsClearedWithoutLosingAuthentication() throws Exception {
        createMembership(userId, businessB, "STAFF", true, OffsetDateTime.now(ZoneOffset.UTC));
        Cookie session = login();
        selectBusiness(session, businessA).andExpect(status().isOk());
        jdbc.sql("UPDATE membership SET active=false WHERE user_id=:user AND business_id=:business")
                .param("user", userId)
                .param("business", businessA)
                .update();

        mvc.perform(get("/api/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBusinessId").doesNotExist())
                .andExpect(jsonPath("$.businesses.length()").value(1))
                .andExpect(jsonPath("$.businesses[0].id").value(businessB.toString()));
        selectBusiness(session, businessB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBusinessId").value(businessB.toString()));
    }

    @Test
    void businessSelectionEnforcesMembershipAndBusinessIsolation() throws Exception {
        Cookie session = login();
        selectBusiness(session, businessA).andExpect(status().isOk());
        selectBusiness(session, businessB)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void userWithMultipleMembershipsReceivesEveryRoleWithoutCrossTenantLeakage()
            throws Exception {
        createMembership(userId, businessB, "STAFF", true, OffsetDateTime.now(ZoneOffset.UTC));
        Cookie session = login();
        mvc.perform(get("/api/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businesses.length()").value(2))
                .andExpect(jsonPath("$.businesses[0].id").exists())
                .andExpect(jsonPath("$.businesses[1].id").exists());
    }

    @Test
    void platformAdminDoesNotInheritBusinessAccess() throws Exception {
        jdbc.sql("DELETE FROM membership").update();
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',now())")
                .param("id", userId)
                .update();
        Cookie session = login();
        selectBusiness(session, businessA).andExpect(status().isForbidden());
    }

    @Test
    void developmentMailboxHasPlatformOnlyAccess() throws Exception {
        mvc.perform(get("/api/dev/mailbox")).andExpect(status().isUnauthorized());
        Cookie ordinarySession = login();
        mvc.perform(get("/api/dev/mailbox").cookie(ordinarySession)).andExpect(status().isForbidden());
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',now())")
                .param("id", userId)
                .update();
        Cookie adminSession = login();
        mvc.perform(get("/api/dev/mailbox").cookie(adminSession)).andExpect(status().isOk());
    }

    @Test
    void forgotPasswordResponseIsIdenticalForKnownAndUnknownAccounts() throws Exception {
        String known = mvc.perform(post("/api/auth/password/forgot")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"owner@example.invalid\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknown = mvc.perform(post("/api/auth/password/forgot")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.invalid\"}"))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(known).isEqualTo(unknown);
    }

    @Test
    void publicAuthenticationEndpointReturnsSafe429() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/login")
                            .with(csrf())
                            .with(request -> {
                                request.setRemoteAddr("192.0.2.44");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("limited@example.invalid", "wrong password value")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.44");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("limited@example.invalid", "wrong password value")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void corsAllowsExactOriginOnly() throws Exception {
        mvc.perform(options("/api/auth/session")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/auth/session")
                        .header("Origin", "https://evil.invalid")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    private void assertGenericLoginRejection() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr("test-client-" + userId);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("SPOTYOURSESSION");
    }

    private org.springframework.test.web.servlet.ResultActions selectBusiness(
            Cookie session, UUID businessId) throws Exception {
        return mvc.perform(post("/api/auth/business")
                .with(csrf())
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessId\":\"" + businessId + "\"}"));
    }

    private String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private UUID createUser(
            String email,
            String password,
            boolean active,
            boolean locked,
            OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,active,locked,
                            password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Test User',:hash,:active,:locked,:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", email)
                .param("hash", encoder.encode(password))
                .param("active", active)
                .param("locked", locked)
                .param("now", now)
                .update();
        return id;
    }

    private UUID createBusiness(String slug, OffsetDateTime now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,:slug,'OTHER','ACTIVE','Europe/Sofia',:now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("now", now)
                .update();
        return id;
    }

    private void createMembership(
            UUID user, UUID business, String role, boolean active, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:business,:user,:role,:active,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("business", business)
                .param("user", user)
                .param("role", role)
                .param("active", active)
                .param("now", now)
                .update();
    }
}
