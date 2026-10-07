package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Session independence, the narrow CSRF exemption, and exact-origin CORS of the public booking
 * surface (ADR-0026) through the complete security filter chain over real PostgreSQL.
 */
class PublicBookingSessionSecurityApiIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final String SESSION_COOKIE = "SPOTYOURSESSION";

    /** One caller per identity kind; a null cookie is the anonymous caller. */
    private Map<String, Cookie> callers() {
        Map<String, Cookie> callers = new LinkedHashMap<>();
        callers.put("anonymous", null);
        callers.put("owner", validSession(businessOwner(tenant.business()), tenant.business()));
        callers.put("administrator", validSession(platformAdmin(), null));
        callers.put("invalid", invalidSession());
        callers.put("expired", expiredSession(businessOwner(tenant.business())));
        return callers;
    }

    private MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder builder, Cookie cookie) {
        return cookie == null ? builder : builder.cookie(cookie);
    }

    private MvcResult read(String url, Cookie cookie) throws Exception {
        return mvc.perform(from(withCookie(get(url), cookie), freshAddress())).andReturn();
    }

    private MvcResult send(String url, String body, Cookie cookie) throws Exception {
        return mvc.perform(from(withCookie(
                        post(url).contentType(MediaType.APPLICATION_JSON).content(body), cookie),
                freshAddress())).andReturn();
    }

    // ---- identical behavior for every identity ----

    @Test
    void theReadRoutesAreByteIdenticalForEveryIdentityKindAndSetNoCookie() throws Exception {
        String slug = slugOf(tenant.business());
        List<String> urls = List.of(
                optionsUrl(slug, tenant.service()),
                availabilityUrl(slug, tenant.service()) + "?date=2026-10-01",
                availabilityUrl(slug, tenant.service()) + "?date=2026-10-01&staffMemberId=" + tenant.staff(),
                optionsUrl(slug, UUID.randomUUID()),
                optionsUrl("unknown-studio", tenant.service()),
                availabilityUrl(slug, tenant.service()),
                PREFIX + "/" + slug);

        for (String url : urls) {
            Map<String, MockHttpServletResponse> responses = new LinkedHashMap<>();
            for (var caller : callers().entrySet()) {
                responses.put(caller.getKey(), read(url, caller.getValue()).getResponse());
            }
            MockHttpServletResponse anonymous = responses.get("anonymous");
            for (var entry : responses.entrySet()) {
                MockHttpServletResponse response = entry.getValue();
                assertThat(response.getStatus()).as("%s %s", entry.getKey(), url).isEqualTo(anonymous.getStatus());
                assertThat(text(response)).as("%s %s", entry.getKey(), url).isEqualTo(text(anonymous));
                assertThat(response.getContentType()).isEqualTo(anonymous.getContentType());
                assertThat(response.getHeaderValues("Set-Cookie")).as(entry.getKey()).isEmpty();
                assertThat(response.getHeader("Cache-Control")).contains("no-store");
            }
        }
    }

    @Test
    void theBookingRouteIsIdenticalForEveryIdentityKindAndUsesNoSessionData() throws Exception {
        // Each identity replays an attempt first made anonymously: the byte-identical 200 for all.
        int slot = 0;
        for (var caller : callers().entrySet()) {
            Req original = req().staff(tenant.staff()).start(
                    java.time.Instant.parse("2026-10-03T06:00:00Z").plusSeconds(1800L * slot++));
            MvcResult created = send(bookingsUrl(original.slug), body(original).toString(), null);
            assertThat(created.getResponse().getStatus()).isEqualTo(201);

            MvcResult replay = send(bookingsUrl(original.slug), body(original.copy()).toString(), caller.getValue());
            assertThat(replay.getResponse().getStatus()).as(caller.getKey()).isEqualTo(200);
            assertThat(text(replay.getResponse())).as(caller.getKey()).isEqualTo(text(created.getResponse()));
            assertThat(replay.getResponse().getHeaderValues("Set-Cookie")).as(caller.getKey()).isEmpty();
        }

        // A new booking per identity: the same success shape, and the guest's own data is booked.
        slot = 0;
        for (var caller : callers().entrySet()) {
            Req fresh = req().start(java.time.Instant.parse("2026-10-02T06:00:00Z").plusSeconds(1800L * slot++))
                    .name("Гост " + caller.getKey()).phone(freshPhone());
            MvcResult booked = send(bookingsUrl(fresh.slug), body(fresh).toString(), caller.getValue());
            assertThat(booked.getResponse().getStatus()).as(caller.getKey()).isEqualTo(201);
            assertThat(keys(tree(booked))).as(caller.getKey())
                    .containsExactly("reference", "status", "service", "staff", "start", "end", "timezone");
            assertThat(booked.getResponse().getHeaderValues("Set-Cookie")).as(caller.getKey()).isEmpty();
            // Only what the guest submitted is stored: never the signed-in user's name or e-mail.
            Map<String, Object> customer = jdbc.sql("""
                            SELECT c.display_name, c.phone, c.email
                            FROM appointment a JOIN customer c ON c.id = a.customer_id
                            WHERE a.public_reference = :reference
                            """)
                    .param("reference", tree(booked).get("reference").asString())
                    .query().singleRow();
            assertThat(customer).containsEntry("display_name", "Гост " + caller.getKey());
            assertThat(customer.get("email")).isNull();
        }
        assertThat(jdbc.sql("SELECT count(DISTINCT customer_id) FROM appointment").query(Long.class).single())
                .isEqualTo(10L);
    }

    @Test
    void theBookingRejectionsAreIdenticalForEveryIdentityKind() throws Exception {
        Req taken = req();
        send(bookingsUrl(taken.slug), body(taken).toString(), null);

        List<String> bodies = List.of(
                body(req()).toString(),
                body(req().name(" ")).toString(),
                body(req().service(UUID.randomUUID())).toString(),
                "{");
        for (String payload : bodies) {
            Map<String, String> seen = new LinkedHashMap<>();
            for (var caller : callers().entrySet()) {
                MvcResult result = send(bookingsUrl(taken.slug), payload, caller.getValue());
                seen.put(caller.getKey(), result.getResponse().getStatus() + " " + text(result.getResponse()));
            }
            assertThat(seen.values()).as(payload).containsOnly(seen.get("anonymous"));
        }
    }

    // ---- sessions are never created, refreshed, cleared, or revoked ----

    @Test
    void publicRequestsLeaveEverySessionRowByteForByteUnchanged() throws Exception {
        String slug = slugOf(tenant.business());
        Map<String, Cookie> callers = callers();
        List<String> before = sessionRows();
        assertThat(before).hasSize(3);

        for (Cookie cookie : callers.values()) {
            read(optionsUrl(slug, tenant.service()), cookie);
            read(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01", cookie);
            read(PREFIX + "/" + slug, cookie);
            read(availabilityUrl(slug, UUID.randomUUID()) + "?date=bad", cookie);
            send(bookingsUrl(slug), body(req().phone(freshPhone()).start("09:00")).toString(), cookie);
            send(bookingsUrl(slug), "{", cookie);
            send(bookingsUrl("unknown-studio"), body(req()).toString(), cookie);
        }

        assertThat(sessionRows()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM user_session WHERE revoked_at IS NOT NULL")
                        .query(Long.class).single())
                .isZero();
    }

    @Test
    void anExpiredSessionIsNeitherRevokedNorAnswerableOnAPublicRouteButIsOnAPrivateOne() throws Exception {
        UUID owner = businessOwner(tenant.business());
        Cookie expired = expiredSession(owner);

        read(optionsUrl(slugOf(tenant.business()), tenant.service()), expired);
        assertThat(jdbc.sql("SELECT count(*) FROM user_session WHERE revoked_at IS NOT NULL")
                        .query(Long.class).single()).isZero();

        // Control: the same cookie on a private route is revoked, so this test can see a revocation.
        MockHttpServletResponse privateResponse = mvc.perform(
                        from(get("/api/business/services").cookie(expired), freshAddress()))
                .andReturn().getResponse();
        assertThat(privateResponse.getStatus()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM user_session WHERE revoked_at IS NOT NULL")
                        .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aValidSessionIsNotRefreshedByAPublicRouteButIsByAPrivateOne() throws Exception {
        UUID owner = businessOwner(tenant.business());
        Cookie session = validSession(owner, tenant.business());
        jdbc.sql("UPDATE user_session SET last_activity_at = last_activity_at - interval '30 minutes'").update();
        String before = jdbc.sql("SELECT last_activity_at::text FROM user_session").query(String.class).single();

        read(optionsUrl(slugOf(tenant.business()), tenant.service()), session);
        send(bookingsUrl(slugOf(tenant.business())), body(req()).toString(), session);

        assertThat(jdbc.sql("SELECT last_activity_at::text FROM user_session").query(String.class).single())
                .isEqualTo(before);

        // Control: a private request does touch the session.
        mvc.perform(from(get("/api/business/services").cookie(session), freshAddress())).andReturn();
        assertThat(jdbc.sql("SELECT last_activity_at::text FROM user_session").query(String.class).single())
                .isNotEqualTo(before);
    }

    @Test
    void thePublicResponsesNeverDependOnTheSelectedBusinessOfASession() throws Exception {
        var other = openTenant();
        Cookie ownerOfOther = validSession(businessOwner(other.business()), other.business());
        String slug = slugOf(tenant.business());

        MvcResult asOtherOwner = read(optionsUrl(slug, tenant.service()), ownerOfOther);
        MvcResult anonymous = read(optionsUrl(slug, tenant.service()), null);
        MvcResult foreignService = read(optionsUrl(slug, other.service()), ownerOfOther);

        assertThat(text(asOtherOwner.getResponse())).isEqualTo(text(anonymous.getResponse()));
        // The owner's own Business does not make its Service visible under another slug.
        assertThat(foreignService.getResponse().getStatus()).isEqualTo(409);
        MvcResult booking = send(bookingsUrl(slug), body(req().service(other.service())).toString(), ownerOfOther);
        assertThat(booking.getResponse().getStatus()).isEqualTo(409);
        assertThat(appointmentCount(other.business())).isZero();
    }

    // ---- the narrow CSRF exemption ----

    @Test
    void thePublicBookingPostNeedsNoCsrfTokenWithOrWithoutASession() throws Exception {
        Cookie owner = validSession(businessOwner(tenant.business()), tenant.business());
        Req anonymous = req().start("09:00");
        Req signedIn = req().start("09:30");

        MvcResult first = send(bookingsUrl(anonymous.slug), body(anonymous).toString(), null);
        MvcResult second = send(bookingsUrl(signedIn.slug), body(signedIn).toString(), owner);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeaderValues("Set-Cookie")).isEmpty();
        assertThat(second.getResponse().getHeaderValues("Set-Cookie")).isEmpty();
    }

    @Test
    void noOtherPublicRouteOrVerbIsExemptFromCsrf() throws Exception {
        String slug = slugOf(tenant.business());
        UUID service = tenant.service();
        String payload = body(req()).toString();
        List<MockHttpServletRequestBuilder> requests = List.of(
                post(bookingsUrl(slug) + "/"),
                post(bookingsUrl(slug) + "/extra"),
                post(PREFIX + "/" + slug + "/x/bookings"),
                post(PREFIX + "/" + slug),
                post(optionsUrl(slug, service)),
                post(availabilityUrl(slug, service)),
                put(bookingsUrl(slug)),
                delete(bookingsUrl(slug)),
                post(PREFIX + "/" + slug + "/appointments"),
                post(PREFIX + "/" + slug + "/bookings/ABCDEFGHJK/cancel"),
                post("/api/public/other"));

        for (MockHttpServletRequestBuilder request : requests) {
            MockHttpServletResponse withoutToken = mvc.perform(from(
                            request.contentType(MediaType.APPLICATION_JSON).content(payload), freshAddress()))
                    .andReturn().getResponse();
            // Still protected by CSRF: refused as a forbidden request, not routed to a handler.
            assertThat(withoutToken.getStatus()).as(request.toString()).isEqualTo(403);
            assertThat(withoutToken.getContentAsString()).as(request.toString()).contains("ACCESS_DENIED");
        }
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void theExemptionNeverBecomesALoginOrAnAuthenticatedWriteRoute() throws Exception {
        Cookie owner = validSession(businessOwner(tenant.business()), tenant.business());
        List<String> privateWrites = List.of(
                "/api/auth/login", "/api/auth/logout", "/api/business/services",
                "/api/business/staff-members", "/api/business/customers");

        for (String route : privateWrites) {
            MockHttpServletResponse anonymous = mvc.perform(from(
                            post(route).contentType(MediaType.APPLICATION_JSON).content("{}"), freshAddress()))
                    .andReturn().getResponse();
            MockHttpServletResponse signedIn = mvc.perform(from(
                            post(route).cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{}"),
                            freshAddress()))
                    .andReturn().getResponse();
            assertThat(anonymous.getStatus()).as(route).isEqualTo(403);
            assertThat(signedIn.getStatus()).as(route).isEqualTo(403);
        }
    }

    @Test
    void privateMutationsStillRequireAValidCsrfTokenAndASession() throws Exception {
        UUID owner = businessOwner(tenant.business());
        Cookie session = validSession(owner, tenant.business());
        String create = "{\"name\":\"Нова услуга\",\"description\":null,\"durationMinutes\":30,\"price\":12.50}";

        MockHttpServletResponse withoutToken = mvc.perform(
                        post("/api/business/services").cookie(session)
                                .contentType(MediaType.APPLICATION_JSON).content(create))
                .andReturn().getResponse();
        MockHttpServletResponse invalidToken = mvc.perform(
                        post("/api/business/services").cookie(session).with(csrf().useInvalidToken())
                                .contentType(MediaType.APPLICATION_JSON).content(create))
                .andReturn().getResponse();
        MockHttpServletResponse withToken = mvc.perform(
                        post("/api/business/services").cookie(session).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content(create))
                .andReturn().getResponse();
        MockHttpServletResponse noSession = mvc.perform(
                        post("/api/business/services").with(csrf())
                                .contentType(MediaType.APPLICATION_JSON).content(create))
                .andReturn().getResponse();

        assertThat(withoutToken.getStatus()).isEqualTo(403);
        assertThat(invalidToken.getStatus()).isEqualTo(403);
        assertThat(withToken.getStatus()).isEqualTo(201);
        assertThat(noSession.getStatus()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM service WHERE name = 'Нова услуга'")
                        .query(Long.class).single()).isEqualTo(1L);
    }

    // ---- exact-origin CORS ----

    @Test
    void theApprovedOriginMayReadAndBookWithTheUnchangedCredentialedPolicy() throws Exception {
        String slug = slugOf(tenant.business());
        Req request = req();

        MockHttpServletResponse read = mvc.perform(from(
                        get(optionsUrl(slug, tenant.service())).header("Origin", APPROVED_ORIGIN), freshAddress()))
                .andReturn().getResponse();
        MockHttpServletResponse booking = mvc.perform(from(
                        post(bookingsUrl(slug)).header("Origin", APPROVED_ORIGIN)
                                .contentType(MediaType.APPLICATION_JSON).content(body(request).toString()),
                        freshAddress()))
                .andReturn().getResponse();

        for (MockHttpServletResponse response : List.of(read, booking)) {
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(APPROVED_ORIGIN);
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
        }
        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(booking.getStatus()).isEqualTo(201);
    }

    @Test
    void anyOtherOriginIsRefusedForReadsAndBookingsAndNothingIsBooked() throws Exception {
        String slug = slugOf(tenant.business());
        for (String origin : List.of("https://unapproved.invalid", "http://localhost:5174",
                "http://localhost:5173.evil.invalid", "https://localhost:5173", "null")) {
            MockHttpServletResponse read = mvc.perform(from(
                            get(optionsUrl(slug, tenant.service())).header("Origin", origin), freshAddress()))
                    .andReturn().getResponse();
            MockHttpServletResponse booking = mvc.perform(from(
                            post(bookingsUrl(slug)).header("Origin", origin)
                                    .contentType(MediaType.APPLICATION_JSON).content(body(req()).toString()),
                            freshAddress()))
                    .andReturn().getResponse();

            for (MockHttpServletResponse response : List.of(read, booking)) {
                assertThat(response.getStatus()).as(origin).isEqualTo(403);
                assertThat(response.getHeader("Access-Control-Allow-Origin")).as(origin).isNull();
                assertThat(response.getHeader("Access-Control-Allow-Credentials")).as(origin).isNull();
            }
        }
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void thePreflightIsAnsweredOnlyForTheApprovedOriginMethodAndHeaders() throws Exception {
        String url = bookingsUrl(slugOf(tenant.business()));

        MockHttpServletResponse approved = preflight(url, APPROVED_ORIGIN, "POST", "content-type");
        MockHttpServletResponse wrongOrigin = preflight(url, "https://unapproved.invalid", "POST", "content-type");
        MockHttpServletResponse wrongHeader = preflight(url, APPROVED_ORIGIN, "POST", "x-forwarded-for");
        MockHttpServletResponse customHeader = preflight(url, APPROVED_ORIGIN, "POST", "content-type,x-extra");
        MockHttpServletResponse wrongMethod = preflight(url, APPROVED_ORIGIN, "PATCH", "content-type");

        assertThat(approved.getStatus()).isEqualTo(200);
        assertThat(approved.getHeader("Access-Control-Allow-Origin")).isEqualTo(APPROVED_ORIGIN);
        assertThat(approved.getHeader("Access-Control-Allow-Methods")).contains("POST");
        assertThat(approved.getHeader("Access-Control-Allow-Headers")).isEqualToIgnoringCase("content-type");
        assertThat(approved.getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
        Map<String, MockHttpServletResponse> refusals = new LinkedHashMap<>();
        refusals.put("origin", wrongOrigin);
        refusals.put("header", wrongHeader);
        refusals.put("method", wrongMethod);
        for (var refused : refusals.entrySet()) {
            assertThat(refused.getValue().getStatus()).as(refused.getKey()).isEqualTo(403);
            assertThat(refused.getValue().getHeader("Access-Control-Allow-Origin")).as(refused.getKey()).isNull();
        }
        // Spring answers a mixed request with only the allowed header, so a browser blocks the real
        // request that carries the other one; no header outside the allowlist is ever granted.
        assertThat(customHeader.getHeader("Access-Control-Allow-Headers")).isEqualToIgnoringCase("content-type");
        assertThat(approved.getHeaderValues("Set-Cookie")).isEmpty();
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void theCookieNameIsNeverSetByAnyPublicResponse() throws Exception {
        String slug = slugOf(tenant.business());
        List<MockHttpServletResponse> responses = new ArrayList<>();
        responses.add(read(optionsUrl(slug, tenant.service()), null).getResponse());
        responses.add(send(bookingsUrl(slug), body(req()).toString(), null).getResponse());
        responses.add(send(bookingsUrl(slug), "{", null).getResponse());
        responses.add(read(PREFIX + "/" + slug + "/nothing", null).getResponse());
        responses.add(preflight(bookingsUrl(slug), APPROVED_ORIGIN, "POST", "content-type"));

        for (MockHttpServletResponse response : responses) {
            assertThat(response.getHeaderValues("Set-Cookie")).isEmpty();
            assertThat(response.getCookies()).extracting(Cookie::getName)
                    .doesNotContain(SESSION_COOKIE, "XSRF-TOKEN", "JSESSIONID");
        }
    }

    private MockHttpServletResponse preflight(String url, String origin, String method, String headers)
            throws Exception {
        return mvc.perform(from(options(url)
                                .header("Origin", origin)
                                .header("Access-Control-Request-Method", method)
                                .header("Access-Control-Request-Headers", headers),
                        freshAddress()))
                .andReturn().getResponse();
    }
}
