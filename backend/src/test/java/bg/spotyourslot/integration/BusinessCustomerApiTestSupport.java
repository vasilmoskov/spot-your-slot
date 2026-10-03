package bg.spotyourslot.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import bg.spotyourslot.identity.domain.TokenCodec;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared fixtures and request builders for the private Customer API integration tests. All data is
 * synthetic. Fixtures are written straight to the Testcontainers database, never to a development
 * database.
 */
final class BusinessCustomerApiTestSupport {
    static final String BASE = "/api/business/customers";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final TokenCodec tokens;
    private final Instant now;

    BusinessCustomerApiTestSupport(MockMvc mvc, JdbcClient jdbc, TokenCodec tokens, Instant now) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.now = now;
    }

    record Actor(UUID businessId, UUID userId, Cookie session) {
    }

    // ---- actors --------------------------------------------------------------------------------

    Actor owner(String businessStatus) {
        return actor(businessStatus, "BUSINESS_OWNER", true, false, true);
    }

    Actor actor(
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
            jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) "
                            + "VALUES (:user,'PLATFORM_ADMIN',:now)")
                    .param("user", userId)
                    .param("now", timestamp())
                    .update();
        }
        return new Actor(businessId, userId, session(userId, selectBusiness ? businessId : null));
    }

    Actor actorWithForeignMembership() {
        UUID selectedBusiness = business("ACTIVE");
        UUID membershipBusiness = business("ACTIVE");
        UUID userId = user();
        membership(userId, membershipBusiness, "BUSINESS_OWNER", true);
        return new Actor(selectedBusiness, userId, session(userId, selectedBusiness));
    }

    UUID business(String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,'Customer API Business','OTHER',:status,'Europe/Sofia',:now,:now)
                        """)
                .param("id", id)
                .param("slug", "api-customer-" + SEQUENCE.incrementAndGet())
                .param("status", status)
                .param("now", timestamp())
                .update();
        return id;
    }

    String slugOf(UUID businessId) {
        return jdbc.sql("SELECT slug FROM business WHERE id=:id")
                .param("id", businessId)
                .query(String.class)
                .single();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,active,locked,
                            credential_version,password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Customer API User','unused',true,false,1,:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", id + "@example.invalid")
                .param("now", timestamp())
                .update();
        return id;
    }

    private void membership(UUID userId, UUID businessId, String role, boolean active) {
        jdbc.sql("""
                        INSERT INTO membership(id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:business,:user,:role,:active,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("business", businessId)
                .param("user", userId)
                .param("role", role)
                .param("active", active)
                .param("now", timestamp())
                .update();
    }

    private Cookie session(UUID userId, UUID selectedBusinessId) {
        String rawToken = "customer-session-" + UUID.randomUUID();
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
                .param("now", timestamp())
                .param("expires", timestamp().plusHours(12))
                .update();
        return new Cookie("SPOTYOURSESSION", rawToken);
    }

    private OffsetDateTime timestamp() {
        return OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    // ---- stored Customers ----------------------------------------------------------------------

    UUID insertCustomer(UUID businessId, String name, String phone, String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer(
                            id,business_id,display_name,phone,email,version,created_at,updated_at)
                        VALUES (:id,:business,:name,:phone,:email,0,:now,:now)
                        """)
                .param("id", id)
                .param("business", businessId)
                .param("name", name)
                .param("phone", phone, java.sql.Types.VARCHAR)
                .param("email", email, java.sql.Types.VARCHAR)
                .param("now", timestamp())
                .update();
        return id;
    }

    StoredCustomer stored(UUID customerId) {
        return jdbc.sql("""
                        SELECT business_id,display_name,phone,email,version,created_at,updated_at
                        FROM customer WHERE id=:id
                        """)
                .param("id", customerId)
                .query((rows, index) -> new StoredCustomer(
                        rows.getObject("business_id", UUID.class),
                        rows.getString("display_name"),
                        rows.getString("phone"),
                        rows.getString("email"),
                        rows.getLong("version"),
                        rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rows.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    long customerCount(UUID businessId) {
        return jdbc.sql("SELECT count(*) FROM customer WHERE business_id=:id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    record StoredCustomer(
            UUID businessId,
            String displayName,
            String phone,
            String email,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    // ---- requests ------------------------------------------------------------------------------

    ResultActions list(Actor actor, String query) throws Exception {
        return mvc.perform(get(BASE + (query == null ? "" : "?" + query)).cookie(actor.session()));
    }

    ResultActions detail(Actor actor, UUID customerId) throws Exception {
        return mvc.perform(get(BASE + "/{id}", customerId).cookie(actor.session()));
    }

    ResultActions search(Actor actor, ObjectNode body, boolean withCsrf) throws Exception {
        var request = post(BASE + "/search")
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    ResultActions search(Actor actor, String term) throws Exception {
        return search(actor, searchBody(term), true);
    }

    ResultActions create(Actor actor, String name, String phone, String email, boolean withCsrf)
            throws Exception {
        return createJson(actor, body(name, phone, email, null), withCsrf);
    }

    ResultActions createJson(Actor actor, ObjectNode body, boolean withCsrf) throws Exception {
        var request = post(BASE)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    ResultActions update(
            Actor actor, UUID customerId, String name, String phone, String email, Long version)
            throws Exception {
        return updateJson(actor, customerId, body(name, phone, email, version), true);
    }

    ResultActions updateJson(Actor actor, UUID customerId, ObjectNode body, boolean withCsrf)
            throws Exception {
        var request = put(BASE + "/{id}", customerId)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString());
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    // ---- JSON ----------------------------------------------------------------------------------

    static ObjectNode body(String name, String phone, String email, Long expectedVersion) {
        ObjectNode body = JSON.createObjectNode();
        body.put("displayName", name);
        body.put("phone", phone);
        body.put("email", email);
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return body;
    }

    static ObjectNode searchBody(String term) {
        ObjectNode body = JSON.createObjectNode();
        body.put("search", term);
        return body;
    }

    static ObjectNode searchBody(String term, Integer page, Integer size, String sort, String direction) {
        ObjectNode body = searchBody(term);
        if (page != null) {
            body.put("page", page);
        }
        if (size != null) {
            body.put("size", size);
        }
        if (sort != null) {
            body.put("sort", sort);
        }
        if (direction != null) {
            body.put("direction", direction);
        }
        return body;
    }

    static ObjectNode emptyObject() {
        return JSON.createObjectNode();
    }

    static JsonNode parse(String json) {
        return JSON.readTree(json);
    }
}
