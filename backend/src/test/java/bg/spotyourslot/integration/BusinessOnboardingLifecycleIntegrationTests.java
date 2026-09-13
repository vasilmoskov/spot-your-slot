package bg.spotyourslot.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@AutoConfigureMockMvc
@Sql(
        statements =
                "TRUNCATE user_session,password_reset,owner_invitation,membership,platform_role,app_user,business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessOnboardingLifecycleIntegrationTests extends PostgresIntegrationTest {
    private static final String ADMIN_EMAIL = "platform-admin@example.invalid";
    private static final String ADMIN_PASSWORD = "platform administrator password";
    private static final String OWNER_EMAIL = "new-owner@example.invalid";
    private static final String OWNER_PASSWORD = "new owner secure password";
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    Clock clock;

    @BeforeEach
    void fixture() {
        UUID adminId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,active,locked,
                            credential_version,password_changed_at,created_at,updated_at)
                        VALUES (
                            :id,:email,'Platform administrator',:passwordHash,true,false,
                            0,:now,:now,:now)
                        """)
                .param("id", adminId)
                .param("email", ADMIN_EMAIL)
                .param("passwordHash", passwords.encode(ADMIN_PASSWORD))
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',:now)")
                .param("id", adminId)
                .param("now", now)
                .update();
    }

    @Test
    void platformAdminCanOnboardAndActivateBusinessForNewInvitedOwner() throws Exception {
        Cookie administratorSession = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        String createdBusinessLocation = mvc.perform(post("/api/platform/businesses")
                        .with(csrf())
                        .cookie(administratorSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBusinessJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn()
                .getResponse()
                .getHeader("Location");
        UUID businessId = UUID.fromString(
                createdBusinessLocation.substring(createdBusinessLocation.lastIndexOf('/') + 1));

        mvc.perform(post("/api/platform/identity/businesses/{businessId}/owner-invitation", businessId)
                        .with(csrf())
                        .cookie(administratorSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER_EMAIL + "\"}"))
                .andExpect(status().isAccepted());

        String invitationToken = invitationTokenFromProtectedMailbox(administratorSession);
        mvc.perform(post("/api/auth/invitations/accept")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(acceptInvitationJson(invitationToken)))
                .andExpect(status().isNoContent());

        Cookie ownerSession = login(OWNER_EMAIL, OWNER_PASSWORD);
        mvc.perform(get("/api/auth/session").cookie(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformAdmin").value(false))
                .andExpect(jsonPath("$.businesses.length()").value(1))
                .andExpect(jsonPath("$.businesses[0].id").value(businessId.toString()))
                .andExpect(jsonPath("$.businesses[0].role").value("BUSINESS_OWNER"));

        mvc.perform(post("/api/auth/business")
                        .with(csrf())
                        .cookie(ownerSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"" + businessId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBusinessId").value(businessId.toString()))
                .andExpect(jsonPath("$.businesses.length()").value(1));

        mvc.perform(post("/api/platform/businesses/{businessId}/activate", businessId)
                        .with(csrf())
                        .cookie(administratorSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(businessId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    private Cookie login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("SPOTYOURSESSION");
    }

    private String invitationTokenFromProtectedMailbox(Cookie administratorSession) throws Exception {
        String messages = mvc.perform(get("/api/dev/mailbox").cookie(administratorSession))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Map<String, Object>> mailbox = JsonPath.parse(messages).read("$");
        String invitationUrl = mailbox.stream()
                .filter(message -> "OWNER_INVITATION".equals(message.get("kind")))
                .filter(message -> OWNER_EMAIL.equals(message.get("recipient")))
                .map(message -> (String) message.get("url"))
                // The development mailbox returns messages in delivery order.
                .reduce((earlier, latest) -> latest)
                .orElseThrow(() -> new AssertionError(
                        "The protected development mailbox did not contain an invitation link"));
        List<String> tokens = UriComponentsBuilder.fromUriString(invitationUrl)
                .build()
                .getQueryParams()
                .get("token");
        if (tokens == null || tokens.size() != 1 || tokens.getFirst() == null
                || tokens.getFirst().isBlank()) {
            throw new AssertionError("The invitation link must contain exactly one nonblank token");
        }
        return UriUtils.decode(tokens.getFirst(), StandardCharsets.UTF_8);
    }

    private String createBusinessJson() {
        return """
                {"slug":"onboarded-studio","displayName":"Onboarded Studio","businessType":"OTHER"}
                """;
    }

    private String acceptInvitationJson(String token) {
        return """
                {"token":"%s","displayName":"New Owner","password":"%s"}
                """.formatted(token, OWNER_PASSWORD);
    }
}
