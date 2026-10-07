package bg.spotyourslot.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import bg.spotyourslot.identity.domain.TokenCodec;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared base of the public guest booking HTTP tests (ADR-0026): the complete servlet and security
 * filter chain over real PostgreSQL, the controllable clock of the booking tests, and a distinct
 * remote address per test so that the shared in-process limiter never couples two tests.
 */
@AutoConfigureMockMvc
abstract class PublicBookingApiIntegrationTest extends BookingIntegrationTest {
    protected static final String PREFIX = "/api/public/businesses";
    protected static final String APPROVED_ORIGIN = "http://localhost:5173";
    protected static final String FIXED_INSTANCE = "/api/public/businesses";
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    @Autowired protected MockMvc mvc;
    @Autowired protected TokenCodec tokens;
    @Autowired protected ObjectMapper json;

    /**
     * A remote address no other request in this JVM has used. Every request defaults to a fresh one,
     * so the shared in-process limiter never couples two requests or two tests; the rate-limit tests
     * choose a fixed address on purpose.
     */
    protected static String freshAddress() {
        int number = ADDRESSES.incrementAndGet();
        return "10." + ((number >> 16) & 255) + "." + ((number >> 8) & 255) + "." + (number & 255);
    }

    // ---- routes ----

    protected String optionsUrl(String slug, UUID service) {
        return PREFIX + "/" + slug + "/services/" + service + "/booking-options";
    }

    protected String availabilityUrl(String slug, UUID service) {
        return PREFIX + "/" + slug + "/services/" + service + "/availability";
    }

    protected String bookingsUrl(String slug) {
        return PREFIX + "/" + slug + "/bookings";
    }

    // ---- requests ----

    protected MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String remote) {
        return builder.with(request -> {
            request.setRemoteAddr(remote);
            return request;
        });
    }

    protected MvcResult getJson(String url) throws Exception {
        return getJsonFrom(url, freshAddress());
    }

    protected MvcResult getJsonFrom(String url, String remote) throws Exception {
        return mvc.perform(from(get(url), remote)).andReturn();
    }

    protected MvcResult postJson(String url, String body) throws Exception {
        return postJsonFrom(url, body, freshAddress());
    }

    protected MvcResult postJsonFrom(String url, String body, String remote) throws Exception {
        return mvc.perform(from(post(url).contentType(MediaType.APPLICATION_JSON).content(body), remote))
                .andReturn();
    }

    protected MvcResult book(Req request) throws Exception {
        return postJson(bookingsUrl(request.slug), body(request).toString());
    }

    protected JsonNode tree(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The public booking request body for a builder, exactly as the frontend would send it. */
    protected ObjectNode body(Req request) {
        ObjectNode body = json.createObjectNode();
        body.put("attemptId", request.attemptId);
        body.put("serviceId", request.service.toString());
        if (request.staff == null) {
            body.putNull("staffMemberId");
        } else {
            body.put("staffMemberId", request.staff.toString());
        }
        body.put("start", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(request.start.atZone(SOFIA)));
        ObjectNode customer = body.putObject("customer");
        customer.put("displayName", request.name);
        if (request.phone == null) {
            customer.putNull("phone");
        } else {
            customer.put("phone", request.phone);
        }
        if (request.email == null) {
            customer.putNull("email");
        } else {
            customer.put("email", request.email);
        }
        if (request.note == null) {
            body.putNull("note");
        } else {
            body.put("note", request.note);
        }
        return body;
    }

    protected static List<String> keys(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    // ---- identities (only to prove that they make no difference) ----

    protected UUID user(String emailPrefix) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,active,locked,
                            credential_version,password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Integration User','unused',true,false,1,:now,:now,:now)
                        """)
                .param("id", id)
                .param("email", emailPrefix + "-" + id + "@example.invalid")
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        return id;
    }

    protected UUID platformAdmin() {
        UUID id = user("admin");
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',:now)")
                .param("id", id)
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        return id;
    }

    protected UUID businessOwner(UUID business) {
        UUID id = user("owner");
        jdbc.sql("""
                        INSERT INTO membership(id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:business,:user,'BUSINESS_OWNER',true,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("business", business)
                .param("user", id)
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        return id;
    }

    /** A valid session row, fresh at the controllable clock, selecting the Business when given. */
    protected Cookie validSession(UUID userId, UUID selectedBusiness) {
        OffsetDateTime now = utc(BookingHookConfiguration.NOW);
        return session(userId, selectedBusiness, now, now.plusHours(12), "valid");
    }

    /** A session whose idle timeout has long passed at the controllable clock. */
    protected Cookie expiredSession(UUID userId) {
        OffsetDateTime now = utc(BookingHookConfiguration.NOW);
        return session(userId, null, now.minusHours(3), now.plusHours(9), "expired");
    }

    private Cookie session(
            UUID userId,
            UUID selectedBusiness,
            OffsetDateTime lastActivity,
            OffsetDateTime absoluteExpiry,
            String label) {
        String raw = label + "-session-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO user_session(
                            id,token_hash,user_id,active_business_id,credential_version,created_at,
                            last_activity_at,absolute_expires_at)
                        VALUES (:id,:hash,:user,:business,1,:created,:activity,:expires)
                        """)
                .param("id", UUID.randomUUID())
                .param("hash", tokens.hash(raw))
                .param("user", userId)
                .param("business", selectedBusiness)
                .param("created", lastActivity.minusMinutes(1))
                .param("activity", lastActivity)
                .param("expires", absoluteExpiry)
                .update();
        return new Cookie("SPOTYOURSESSION", raw);
    }

    protected static Cookie invalidSession() {
        return new Cookie("SPOTYOURSESSION", "not-a-real-session-token");
    }

    /** Every column of every session row, to prove that a public request changed nothing. */
    protected List<String> sessionRows() {
        return jdbc.sql("SELECT s::text FROM user_session s ORDER BY s.id").query(String.class).list();
    }

    protected static String text(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Runs a request on a thread named {@code booking-<label>} so hooks and the transaction log see it. */
    protected MvcResult onBookingThread(String label, ThrowingSupplier<MvcResult> action) throws Exception {
        Thread thread = Thread.currentThread();
        String original = thread.getName();
        thread.setName(threadOf(label));
        try {
            return action.get();
        } finally {
            thread.setName(original);
        }
    }

    @FunctionalInterface
    protected interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
