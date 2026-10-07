package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The limiter through HTTP (ADR-0026) with deliberately small configured budgets, so every budget
 * is reached in a handful of requests: 3 booking attempts per address and Business, 5 per address
 * across Businesses, 2 per contact, 4 reads per address and Business, and 6 per address across
 * Businesses (the capacity behavior is in {@code PublicBookingRateLimitCapacityApiIntegrationTests}).
 * The exact arithmetic of every budget, boundary, and
 * capacity rule is in {@code BookingRateLimiterTests}; this class proves the wiring, the 429
 * contract, the charging rules, and that a rejected request reaches no database or orchestration.
 */
@TestPropertySource(properties = {
    "spotyourslot.booking.rate-limit.booking-per-address-business=3",
    "spotyourslot.booking.rate-limit.booking-per-address=5",
    "spotyourslot.booking.rate-limit.booking-per-contact=2",
    "spotyourslot.booking.rate-limit.availability-per-address-business=4",
    "spotyourslot.booking.rate-limit.availability-per-address=6",
    "spotyourslot.booking.rate-limit.window-seconds=900"
})
class PublicBookingRateLimitApiIntegrationTests extends PublicBookingApiIntegrationTest {
    private static final String LIMITED = "Твърде много опити. Опитайте по-късно.";

    private MvcResult bookFrom(String remote, Req request) throws Exception {
        return postJsonFrom(bookingsUrl(request.slug), body(request).toString(), remote);
    }

    private MvcResult optionsFrom(String remote, String slug) throws Exception {
        return getJsonFrom(optionsUrl(slug, tenant.service()), remote);
    }

    // ---- the 429 contract ----

    @Test
    void theFourthBookingAttemptOfAnAddressForABusinessIsASanitized429WithRetryAfter() throws Exception {
        String remote = freshAddress();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(bookFrom(remote, req().start(String.format("%02d:00", attempt + 8))).getResponse().getStatus())
                    .isEqualTo(201);
        }

        MvcResult limited = bookFrom(remote, req().start("11:00"));

        MockHttpServletResponse response = limited.getResponse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeaderValues("Set-Cookie")).isEmpty();
        JsonNode body = tree(limited);
        assertThat(keys(body)).containsExactlyInAnyOrder("title", "status", "detail", "instance", "code");
        assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("detail").asString()).isEqualTo(LIMITED);
        assertThat(body.get("instance").asString()).isEqualTo(FIXED_INSTANCE);
        assertThat(text(response)).doesNotContain(tenant.business().toString()).doesNotContain(slugOf(tenant.business()));
        assertThat(appointmentCount(tenant.business())).isEqualTo(3);
    }

    @Test
    void aRejectedRequestReachesNeitherTheOrchestrationNorTheDatabase() throws Exception {
        String remote = freshAddress();
        for (int attempt = 1; attempt <= 3; attempt++) {
            bookFrom(remote, req().start(String.format("%02d:00", attempt + 8)));
        }
        long customers = totalCustomers();
        long appointments = totalAppointments();
        clock.resetReads();
        TransactionLog.clear();

        MvcResult limited = onBookingThread("limited", () -> bookFrom(remote, req().start("11:00")));

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(TransactionLog.begins(threadOf("limited"))).isEmpty();
        assertThat(hooks.observations()).isEmpty();
        assertThat(totalCustomers()).isEqualTo(customers);
        assertThat(totalAppointments()).isEqualTo(appointments);
        // The limiter reads the clock once; booking and availability would read it as well.
        assertThat(clock.reads()).isLessThanOrEqualTo(2);
    }

    @Test
    void aRejectedReadReachesNeitherAvailabilityNorTheDatabase() throws Exception {
        String remote = freshAddress();
        String slug = slugOf(tenant.business());
        for (int read = 1; read <= 4; read++) {
            assertThat(optionsFrom(remote, slug).getResponse().getStatus()).isEqualTo(200);
        }
        clock.resetReads();

        MvcResult limited = optionsFrom(remote, slug);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("900");
        assertThat(tree(limited).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(clock.reads()).isLessThanOrEqualTo(2);
    }

    @Test
    void theWindowBoundaryAndRetryAfterFollowTheInjectedClock() throws Exception {
        String remote = freshAddress();
        for (int attempt = 1; attempt <= 3; attempt++) {
            bookFrom(remote, req().start(String.format("%02d:00", attempt + 8)));
        }

        clock.set(BookingHookConfiguration.NOW.plus(Duration.ofMinutes(5)));
        MvcResult midWindow = bookFrom(remote, req().start("11:00"));
        assertThat(midWindow.getResponse().getStatus()).isEqualTo(429);
        assertThat(midWindow.getResponse().getHeader("Retry-After")).isEqualTo("600");

        clock.set(BookingHookConfiguration.NOW.plus(Duration.ofMinutes(15)).minusMillis(1));
        MvcResult justBefore = bookFrom(remote, req().start("11:00"));
        assertThat(justBefore.getResponse().getStatus()).isEqualTo(429);
        assertThat(justBefore.getResponse().getHeader("Retry-After")).isEqualTo("1");

        clock.set(BookingHookConfiguration.NOW.plus(Duration.ofMinutes(15)));
        assertThat(bookFrom(remote, req().start("11:30")).getResponse().getStatus()).isEqualTo(201);
    }

    // ---- charging rules ----

    @Test
    void aReplayAnInvalidBodyAndARejectedBookingAreChargedLikeAnyOtherAttempt() throws Exception {
        String remote = freshAddress();
        Req request = req();
        assertThat(bookFrom(remote, request).getResponse().getStatus()).isEqualTo(201);
        // A replay (200), a malformed body (400), and a business conflict (409) each use one unit.
        assertThat(bookFrom(remote, request.copy()).getResponse().getStatus()).isEqualTo(200);
        assertThat(postJsonFrom(bookingsUrl(request.slug), "{", remote).getResponse().getStatus()).isEqualTo(400);

        MvcResult limited = bookFrom(remote, req().start("10:00"));

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void aRejectedBookingIsCharged() throws Exception {
        String remote = freshAddress();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(bookFrom(remote, req().service(UUID.randomUUID())).getResponse().getStatus())
                    .isEqualTo(409);
        }

        assertThat(bookFrom(remote, req()).getResponse().getStatus()).isEqualTo(429);
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void malformedBodiesAreChargedToTheAddressBudgetBeforeTheyAreParsed() throws Exception {
        String remote = freshAddress();
        String url = bookingsUrl(slugOf(tenant.business()));
        for (String body : List.of("{", "not json", "[]")) {
            assertThat(postJsonFrom(url, body, remote).getResponse().getStatus()).isEqualTo(400);
        }

        MvcResult limited = postJsonFrom(url, "{", remote);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(tree(limited).get("code").asString()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void theContactBudgetIsPerBusinessAndCanonicalContactAcrossAddressesAndWritings() throws Exception {
        String phone = "0888 321 001";
        assertThat(bookFrom(freshAddress(), req().phone(phone).start("09:00")).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(bookFrom(freshAddress(), req().phone("+359 888 321 001").start("09:30")).getResponse()
                .getStatus()).isEqualTo(201);

        MvcResult limited = bookFrom(freshAddress(), req().phone("+359888321001").start("10:00"));

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("900");
        assertThat(text(limited.getResponse())).doesNotContain("321001").doesNotContain("0888");
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
        // Another Business is another budget for the same guest.
        var other = openTenant();
        assertThat(bookFrom(freshAddress(), req().forTenant(other).phone(phone)).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    void aContactRejectionRetainsTheAddressChargeAndAnAddressRejectionNeverChargesTheContact() throws Exception {
        String remote = freshAddress();
        String phone = "0888 321 003";
        assertThat(bookFrom(remote, req().phone(phone).start("09:00")).getResponse().getStatus()).isEqualTo(201);
        assertThat(bookFrom(remote, req().phone(phone).start("09:30")).getResponse().getStatus()).isEqualTo(201);

        // The address decision admits (a third unit) and the contact decision then rejects.
        MvcResult contactLimited = bookFrom(remote, req().phone(phone).start("10:00"));
        assertThat(contactLimited.getResponse().getStatus()).isEqualTo(429);

        // The address budget is 3: that rejected request kept its address charge, so it is spent.
        MvcResult addressLimited = bookFrom(remote, req().phone("0888 321 004").start("10:00"));
        assertThat(addressLimited.getResponse().getStatus()).isEqualTo(429);
        // The address rejection charged no contact: that phone is still fully available elsewhere.
        assertThat(bookFrom(freshAddress(), req().phone("0888 321 004").start("10:00")).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(appointmentCount(tenant.business())).isEqualTo(3);
    }

    @Test
    void aRequestWithPhoneAndEmailChargesBothAndAlternatingOneCannotEvadeTheBudget() throws Exception {
        String phone = "0888 321 002";
        String email = "limited.guest@example.com";
        assertThat(bookFrom(freshAddress(), req().phone(phone).email(email).start("09:00")).getResponse()
                .getStatus()).isEqualTo(201);
        assertThat(bookFrom(freshAddress(), req().phone(phone).email(email).start("09:30")).getResponse()
                .getStatus()).isEqualTo(201);

        for (Req evasion : List.of(
                req().phone(phone).email("changed@example.com").start("10:00"),
                req().phone("0888 321 099").email(email).start("10:00"),
                req().phone(phone).email(null).start("10:00"),
                req().phone(null).email(email).start("10:00"))) {
            assertThat(bookFrom(freshAddress(), evasion).getResponse().getStatus()).isEqualTo(429);
        }
        assertThat(bookFrom(freshAddress(), req().phone("0888 321 099").email("changed@example.com")
                .start("10:00")).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void readsOfOneBusinessShareOneBudgetBetweenBookingOptionsAndAvailability() throws Exception {
        String remote = freshAddress();
        String slug = slugOf(tenant.business());
        for (int read = 1; read <= 2; read++) {
            assertThat(getJsonFrom(optionsUrl(slug, tenant.service()), remote).getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(getJsonFrom(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01", remote)
                    .getResponse().getStatus()).isEqualTo(200);
        }

        assertThat(getJsonFrom(optionsUrl(slug, tenant.service()), remote).getResponse().getStatus())
                .isEqualTo(429);
        assertThat(getJsonFrom(availabilityUrl(slug, tenant.service()) + "?date=2026-10-01", remote)
                .getResponse().getStatus()).isEqualTo(429);
        // A booking is another budget: reading never blocks booking.
        assertThat(bookFrom(remote, req()).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void readsOfDifferentBusinessesAreBoundedByTheAddressAggregate() throws Exception {
        String remote = freshAddress();
        String slug = slugOf(tenant.business());
        var other = openTenant();
        String otherSlug = slugOf(other.business());
        for (int read = 1; read <= 3; read++) {
            assertThat(optionsFrom(remote, slug).getResponse().getStatus()).isEqualTo(200);
            assertThat(getJsonFrom(optionsUrl(otherSlug, other.service()), remote).getResponse().getStatus())
                    .isEqualTo(200);
        }

        // Six reads in total are spent; no single Business budget (4) is exhausted.
        MvcResult limited = getJsonFrom(optionsUrl(slug, tenant.service()), remote);

        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(optionsFrom(freshAddress(), slug).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void sprayingUnknownAndMalformedSlugsIsBoundedByTheAggregateAndASharedBucket() throws Exception {
        String remote = freshAddress();
        List<Integer> statuses = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            statuses.add(optionsFrom(remote, "unknown-" + index).getResponse().getStatus());
        }
        assertThat(statuses).containsExactly(404, 404, 404, 404, 404, 404, 429, 429, 429, 429);

        String malformedRemote = freshAddress();
        List<Integer> malformed = new ArrayList<>();
        for (String slug : List.of("bad_1", "bad_2", "bad_3", "bad_4", "bad_5", "bad_6")) {
            malformed.add(optionsFrom(malformedRemote, slug).getResponse().getStatus());
        }
        // One shared bucket for every malformed slug: the fifth read is already refused.
        assertThat(malformed).containsExactly(404, 404, 404, 404, 429, 429);
    }

    // ---- the address source ----

    @Test
    void forwardedHeadersAreNeverTrustedForTheAddress() throws Exception {
        String remote = freshAddress();
        String slug = slugOf(tenant.business());
        String[][] spoofs = {
            {"X-Forwarded-For", "203.0.113.1"},
            {"Forwarded", "for=203.0.113.2"},
            {"X-Real-IP", "203.0.113.3"},
            {"X-Forwarded-For", "203.0.113.4, 203.0.113.5"}
        };
        for (String[] spoof : spoofs) {
            assertThat(mvc.perform(from(get(optionsUrl(slug, tenant.service())).header(spoof[0], spoof[1]),
                            remote)).andReturn().getResponse().getStatus()).isEqualTo(200);
        }

        // Four reads were charged to the one real remote address whatever the headers said.
        MockHttpServletResponse limited = mvc.perform(from(
                        get(optionsUrl(slug, tenant.service())).header("X-Forwarded-For", "198.18.0.99"), remote))
                .andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        // And a spoofed header never frees a fresh budget for another address either.
        MockHttpServletResponse otherAddress = mvc.perform(from(
                        get(optionsUrl(slug, tenant.service())).header("X-Forwarded-For", remote), freshAddress()))
                .andReturn().getResponse();
        assertThat(otherAddress.getStatus()).isEqualTo(200);
    }

    @Test
    void anIpv6SubscriberIsLimitedAsItsSixtyFourBitPrefix() throws Exception {
        String slug = slugOf(tenant.business());
        for (int read = 1; read <= 4; read++) {
            assertThat(optionsFrom("2001:db8:77:" + read + "::1", slug).getResponse().getStatus()).isEqualTo(200);
        }
        String sameSubscriber = "2001:db8:77:9";
        for (int read = 1; read <= 4; read++) {
            assertThat(optionsFrom(sameSubscriber + "::" + read, slug).getResponse().getStatus()).isEqualTo(200);
        }

        assertThat(optionsFrom(sameSubscriber + ":aaaa:bbbb:cccc:dddd", slug).getResponse().getStatus())
                .isEqualTo(429);
    }

    // ---- identity makes no difference ----

    @Test
    void theLimitAppliesIdenticallyToASignedInCallerAndSetsNoCookie() throws Exception {
        var cookie = validSession(businessOwner(tenant.business()), tenant.business());
        String remote = freshAddress();
        String slug = slugOf(tenant.business());
        for (int read = 1; read <= 4; read++) {
            mvc.perform(from(get(optionsUrl(slug, tenant.service())).cookie(cookie), remote));
        }

        MockHttpServletResponse limited = mvc.perform(from(
                        get(optionsUrl(slug, tenant.service())).cookie(cookie), remote))
                .andReturn().getResponse();
        MockHttpServletResponse anonymous = mvc.perform(from(get(optionsUrl(slug, tenant.service())), remote))
                .andReturn().getResponse();

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(text(limited)).isEqualTo(text(anonymous));
        assertThat(limited.getHeaderValues("Set-Cookie")).isEmpty();
    }

    @Test
    void theLimiterDoesNotGuardThePrivateOrOtherPublicRoutes() throws Exception {
        String remote = freshAddress();
        String slug = slugOf(tenant.business());
        for (int index = 0; index < 12; index++) {
            MockHttpServletResponse profile = mvc.perform(from(get(PREFIX + "/" + slug), remote))
                    .andReturn().getResponse();
            assertThat(profile.getStatus()).isEqualTo(200);
            MockHttpServletResponse denied = mvc.perform(from(
                            post("/api/business/services").contentType(MediaType.APPLICATION_JSON).content("{}"),
                            remote))
                    .andReturn().getResponse();
            assertThat(denied.getStatus()).isEqualTo(403);
        }
    }
}
