package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import bg.spotyourslot.identity.domain.TokenCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The public Business profile contract (ADR-0017) against real PostgreSQL through the complete
 * servlet and security filter chain.
 */
@AutoConfigureMockMvc
@Sql(
        statements =
                "TRUNCATE user_session,password_reset,owner_invitation,membership,platform_role,app_user,business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PublicProfileApiIntegrationTests extends PostgresIntegrationTest {
    private static final String ROUTE = "/api/public/businesses/{slug}";
    private static final String APPROVED_ORIGIN = "http://localhost:5173";
    private static final List<String> PROFILE_KEYS = List.of(
            "slug", "displayName", "businessType", "description", "phone", "address", "services");
    private static final List<String> ADDRESS_KEYS =
            List.of("city", "postalCode", "street", "streetNumber", "details");
    private static final List<String> SERVICE_KEYS =
            List.of("id", "name", "description", "durationMinutes", "price");
    private static final String UNAVAILABLE_BODY = """
            {"detail":"Страницата не е налична.","instance":"/api/public/businesses",
             "status":404,"title":"Заявката не може да бъде изпълнена.",
             "code":"BUSINESS_PAGE_UNAVAILABLE"}
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired TokenCodec tokens;
    @Autowired Clock clock;
    @Autowired ObjectMapper json;

    private OffsetDateTime now;

    @BeforeEach
    void fixture() {
        now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // ---- success ----

    @Test
    void anonymousRequestResolvesTheActiveBusinessWithExactlyTheApprovedFields() throws Exception {
        UUID business = insertBusiness("studio-a", "ACTIVE", "Студио А");
        UUID serviceId =
                insertService(business, "Подстригване", "Дамско подстригване", 45, "25.00", true);

        MvcResult result = mvc.perform(get(ROUTE, "studio-a")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_JSON_VALUE);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactlyElementsOf(PROFILE_KEYS);
        assertThat(body.get("slug").asString()).isEqualTo("studio-a");
        assertThat(body.get("displayName").asString()).isEqualTo("Студио А");
        assertThat(body.get("businessType").asString()).isEqualTo("HAIR_SALON");
        assertThat(body.get("description").asString()).isEqualTo("Описание на студиото");
        assertThat(body.get("phone").asString()).isEqualTo("+359 88 000 0000");
        assertThat(fieldNames(body.get("address"))).containsExactlyElementsOf(ADDRESS_KEYS);
        assertThat(body.get("address").get("city").asString()).isEqualTo("София");
        assertThat(body.get("address").get("details").isNull()).isTrue();
        assertThat(body.get("services")).hasSize(1);
        JsonNode service = body.get("services").get(0);
        assertThat(fieldNames(service)).containsExactlyElementsOf(SERVICE_KEYS);
        assertThat(service.get("id").asString()).isEqualTo(serviceId.toString());
        assertThat(service.get("name").asString()).isEqualTo("Подстригване");
        assertThat(service.get("description").asString()).isEqualTo("Дамско подстригване");
        assertThat(service.get("durationMinutes").asInt()).isEqualTo(45);
        assertThat(result.getResponse().getContentAsString()).contains("\"price\":25.00");
    }

    @Test
    void theSlugIsCanonicalizedAndTheResponseCarriesTheCanonicalForm() throws Exception {
        insertBusiness("studio-a", "ACTIVE", "Студио А");

        JsonNode body = ok("STUDIO-A");

        assertThat(body.get("slug").asString()).isEqualTo("studio-a");
    }

    @Test
    void onlyActiveServicesOfTheResolvedBusinessAppearInTheDeterministicOrder() throws Exception {
        UUID a = insertBusiness("studio-a", "ACTIVE", "Студио А");
        UUID b = insertBusiness("studio-b", "ACTIVE", "Студио Б");
        insertService(a, "Charlie", null, 30, "30.00", true);
        insertService(a, "alpha", null, 30, "10.00", true);
        insertService(a, "Bravo", null, 30, "20.00", true);
        insertService(a, "Inactive-A", null, 30, "5.00", false);
        insertService(b, "Other-Business-Service", null, 30, "99.00", true);

        String raw = mvc.perform(get(ROUTE, "studio-a")).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode body = json.readTree(raw);

        assertThat(names(body)).containsExactly("alpha", "Bravo", "Charlie");
        assertThat(raw).doesNotContain("Inactive-A").doesNotContain("Other-Business-Service");
        assertThat(names(ok("studio-b"))).containsExactly("Other-Business-Service");
    }

    @Test
    void missingOptionalValuesSerializeAsNullAndAnEmptyAddressIsNull() throws Exception {
        UUID business = insertBareBusiness("bare-studio", "ACTIVE");
        insertService(business, "Bare", null, 15, "0.00", true);

        JsonNode body = ok("bare-studio");

        assertThat(fieldNames(body)).containsExactlyElementsOf(PROFILE_KEYS);
        assertThat(body.get("description").isNull()).isTrue();
        assertThat(body.get("phone").isNull()).isTrue();
        assertThat(body.get("address").isNull()).isTrue();
        assertThat(body.get("services").get(0).get("description").isNull()).isTrue();
        assertThat(raw("bare-studio")).contains("\"price\":0.00");
    }

    @Test
    void anActiveBusinessWithoutActiveServicesReturnsAProfileWithAnEmptyList() throws Exception {
        UUID business = insertBusiness("empty-catalog", "ACTIVE", "Празен каталог");
        insertService(business, "Off", null, 30, "10.00", false);

        JsonNode body = ok("empty-catalog");

        assertThat(body.get("displayName").asString()).isEqualTo("Празен каталог");
        assertThat(body.get("services").isArray()).isTrue();
        assertThat(body.get("services")).isEmpty();
    }

    @Test
    void repeatedRequestsResolveTheSameBusinessContext() throws Exception {
        UUID business = insertBusiness("studio-a", "ACTIVE", "Студио А");
        insertService(business, "Alpha", null, 30, "10.00", true);

        String first = raw("studio-a");
        String second = raw("studio-a");
        String third = raw("Studio-A");

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    void anAuthenticatedCallerReceivesTheIdenticalRepresentationAndNoCookie() throws Exception {
        UUID business = insertBusiness("studio-a", "ACTIVE", "Студио А");
        insertService(business, "Alpha", null, 30, "10.00", true);
        UUID adminId = insertUser("admin@example.invalid");
        jdbc.sql("INSERT INTO platform_role(user_id,role,created_at) VALUES (:id,'PLATFORM_ADMIN',:now)")
                .param("id", adminId)
                .param("now", now)
                .update();
        Cookie admin = session(adminId, "admin-session");
        UUID ownerId = insertUser("owner@example.invalid");
        jdbc.sql("""
                        INSERT INTO membership(id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:business,:user,'BUSINESS_OWNER',true,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("business", business)
                .param("user", ownerId)
                .param("now", now)
                .update();
        Cookie owner = session(ownerId, "owner-session");

        MockHttpServletResponse anonymous = mvc.perform(get(ROUTE, "studio-a")).andReturn().getResponse();
        MockHttpServletResponse asAdmin = mvc.perform(get(ROUTE, "studio-a").cookie(admin))
                .andReturn().getResponse();
        MockHttpServletResponse asOwner = mvc.perform(get(ROUTE, "studio-a").cookie(owner))
                .andReturn().getResponse();

        for (MockHttpServletResponse response : List.of(anonymous, asAdmin, asOwner)) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo(anonymous.getContentAsString());
            assertThat(response.getHeaderValues("Set-Cookie")).isEmpty();
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
        }
    }

    // ---- privacy ----

    @Test
    void privateValuesThatExistInTheDatabaseAreNeverSerialized() throws Exception {
        UUID business = insertBusiness("sentinel-studio", "ACTIVE", "Публично име");
        jdbc.sql("""
                        UPDATE business
                        SET contact_email = 'sentinel-admin-contact@example.invalid',
                            timezone = 'Pacific/Auckland',
                            version = 7,
                            created_at = TIMESTAMPTZ '2001-02-03 04:05:06+00',
                            updated_at = TIMESTAMPTZ '2002-03-04 05:06:07+00'
                        WHERE id = :id
                        """)
                .param("id", business)
                .update();
        UUID service = insertService(business, "Публична услуга", null, 30, "10.00", true);
        jdbc.sql("UPDATE service SET version = 9, created_at = TIMESTAMPTZ '2003-04-05 06:07:08+00' WHERE id = :id")
                .param("id", service)
                .update();
        AvailabilityFixtures fixtures = new AvailabilityFixtures(jdbc);
        UUID staff = fixtures.staffMember(business, true);
        jdbc.sql("""
                        UPDATE staff_member
                        SET display_name = 'SENTINEL-STAFF-NAME',
                            contact_email = 'sentinel-staff@example.invalid',
                            contact_phone = '+359889999999'
                        WHERE id = :id
                        """)
                .param("id", staff)
                .update();
        fixtures.assign(business, staff, service);
        fixtures.everyDay(business, staff, "09:00", "17:00");
        UUID ownerId = insertUser("sentinel-owner@example.invalid");
        UUID membership = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO membership(id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:business,:user,'BUSINESS_OWNER',true,:now,:now)
                        """)
                .param("id", membership)
                .param("business", business)
                .param("user", ownerId)
                .param("now", now)
                .update();

        String raw = raw("sentinel-studio");

        assertThat(raw)
                .doesNotContain(business.toString())
                .doesNotContain(staff.toString())
                .doesNotContain(ownerId.toString())
                .doesNotContain(membership.toString())
                .doesNotContain("sentinel-admin-contact")
                .doesNotContain("sentinel-staff")
                .doesNotContain("sentinel-owner")
                .doesNotContain("SENTINEL-STAFF-NAME")
                .doesNotContain("+359889999999")
                .doesNotContain("Pacific/Auckland")
                .doesNotContain("2001-02-03")
                .doesNotContain("2002-03-04")
                .doesNotContain("2003-04-05")
                .doesNotContainIgnoringCase("timezone")
                .doesNotContainIgnoringCase("status")
                .doesNotContainIgnoringCase("version")
                .doesNotContainIgnoringCase("createdAt")
                .doesNotContainIgnoringCase("updatedAt")
                .doesNotContainIgnoringCase("membership")
                .doesNotContainIgnoringCase("staff")
                .doesNotContainIgnoringCase("schedule")
                .doesNotContainIgnoringCase("contactEmail")
                .doesNotContainIgnoringCase("currency");
        JsonNode body = json.readTree(raw);
        assertThat(fieldNames(body)).containsExactlyElementsOf(PROFILE_KEYS);
        assertThat(fieldNames(body.get("services").get(0))).containsExactlyElementsOf(SERVICE_KEYS);
        // The Service reference is the one approved identifier (ADR-0026); no other id appears.
        assertThat(body.get("services").get(0).get("id").asString()).isEqualTo(service.toString());
        assertThat(raw.split("\"id\"", -1)).hasSize(2);
    }

    // ---- lifecycle collapse ----

    @Test
    void everyUnavailableCaseIsIndistinguishable() throws Exception {
        insertBusiness("draft-studio", "DRAFT", "Скрит проект");
        insertBusiness("paused-studio", "SUSPENDED", "Скрито спряно");
        UUID active = insertBusiness("live-studio", "ACTIVE", "Публично");
        insertService(active, "S", null, 10, "1.00", true);
        insertBareBusiness("booking", "ACTIVE");
        insertBareBusiness("api", "ACTIVE");

        List<String> slugs = new ArrayList<>(List.of(
                "unknown-studio", "draft-studio", "DRAFT-STUDIO", "paused-studio",
                "bad_slug", "-leading", "trailing-", "double--dash", "a.b", "a'or'1'='1",
                "ключ", "x".repeat(101),
                "login", "logout", "admin", "platform", "book",
                "booking", "api"));
        String expected = compact(UNAVAILABLE_BODY);

        for (String slug : slugs) {
            MockHttpServletResponse response = mvc.perform(get(ROUTE, slug)).andReturn().getResponse();
            String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(response.getStatus()).as(slug).isEqualTo(404);
            assertThat(response.getContentType()).as(slug).startsWith("application/problem+json");
            assertThat(json.readTree(body)).as(slug).isEqualTo(json.readTree(expected));
            assertThat(fieldNames(json.readTree(body))).as(slug)
                    .containsExactlyInAnyOrder("detail", "instance", "status", "title", "code");
            assertThat(body).as(slug)
                    .doesNotContain("Скрит")
                    .doesNotContain("DRAFT")
                    .doesNotContain("SUSPENDED")
                    .doesNotContain("ACTIVE")
                    .doesNotContain("SQL")
                    .doesNotContain("constraint")
                    .doesNotContain("Exception");
            // The fixed "instance" is a path prefix that legitimately contains reserved words.
            assertThat(body.replace("/api/public/businesses", "")).as(slug)
                    .doesNotContain(slug.toLowerCase());
            assertThat(response.getHeaderValues("Set-Cookie")).as(slug).isEmpty();
            assertThat(response.getHeader("Cache-Control")).as(slug).contains("no-store");
        }
    }

    @Test
    void aPreviouslyUsedOrChangedSlugDoesNotResolve() throws Exception {
        UUID draft = insertBusiness("old-name", "DRAFT", "Проект");
        jdbc.sql("UPDATE business SET slug = 'new-name' WHERE id = :id").param("id", draft).update();
        jdbc.sql("UPDATE business SET status = 'ACTIVE' WHERE id = :id").param("id", draft).update();

        assertUnavailable("old-name");
        assertThat(ok("new-name").get("slug").asString()).isEqualTo("new-name");
    }

    @Test
    void suspendingAnActiveBusinessMakesItUnavailableAtOnce() throws Exception {
        UUID business = insertBusiness("studio-a", "ACTIVE", "Студио А");
        ok("studio-a");

        jdbc.sql("UPDATE business SET status = 'SUSPENDED' WHERE id = :id").param("id", business).update();

        assertUnavailable("studio-a");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/public/businesses/studio-a%2Fx",
        "/api/public/businesses/studio-a;x=1",
        "/api/public/businesses/studio-a%5Cx",
        "/api/public/businesses/studio-a%00",
        "/api/public/businesses/%2e%2e/x",
        "/api/public/businesses/studio%2Da"
    })
    void pathsTheServletFirewallRejectsGetABareEmptyBadRequestBeforeAnyController(String path)
            throws Exception {
        insertBusiness("studio-a", "ACTIVE", "Студио А");

        MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void aTrailingSlashOrSuffixNeverResolvesAndLeaksNothing() throws Exception {
        insertBusiness("studio-a", "ACTIVE", "Студио А");

        MockHttpServletResponse trailing = mvc.perform(get("/api/public/businesses/studio-a/"))
                .andReturn().getResponse();

        assertThat(trailing.getStatus()).isEqualTo(401);
        assertThat(trailing.getContentAsString()).doesNotContain("Студио А");
        assertUnavailable("studio-a.json");
    }

    // ---- tenant isolation ----

    @Test
    void similarSlugsNeverResolveToTheWrongBusiness() throws Exception {
        UUID a = insertBusiness("studio", "ACTIVE", "Точно");
        UUID longer = insertBusiness("studio-2", "ACTIVE", "Дълъг");
        insertService(a, "Услуга-А", null, 10, "1.00", true);
        insertService(longer, "Услуга-Б", null, 10, "2.00", true);

        assertThat(ok("studio").get("displayName").asString()).isEqualTo("Точно");
        assertThat(names(ok("studio"))).containsExactly("Услуга-А");
        assertThat(ok("studio-2").get("displayName").asString()).isEqualTo("Дълъг");
        assertThat(names(ok("studio-2"))).containsExactly("Услуга-Б");
        assertUnavailable("studi");
        assertUnavailable("studio-");
        assertUnavailable("studio-22");
    }

    @Test
    void theSlugUniquenessInvariantPreventsTwoBusinessesSharingASlug() {
        insertBusiness("studio-a", "ACTIVE", "Студио А");

        assertThatThrownBy(() -> insertBusiness("studio-a", "DRAFT", "Дубликат"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM business WHERE slug = 'studio-a'")
                        .query(Long.class).single())
                .isEqualTo(1);
    }

    // ---- security boundary ----

    @Test
    void neighbouringPrivateRoutesRemainProtected() throws Exception {
        insertBusiness("studio-a", "ACTIVE", "Студио А");
        List<String> protectedRoutes = List.of(
                "/api/platform/businesses",
                "/api/platform/businesses/" + UUID.randomUUID(),
                "/api/platform/identity/businesses/" + UUID.randomUUID() + "/owner-invitation",
                "/api/business/services",
                "/api/business/staff-members",
                "/api/business/schedule-exceptions",
                "/api/auth/session",
                "/api/dev/mailbox",
                "/api/public",
                "/api/public/businesses",
                "/api/public/businesses/",
                "/api/public/businesses/studio-a/services",
                "/api/public/businesses/studio-a/extra/deeper",
                "/api/public/other");

        for (String route : protectedRoutes) {
            MockHttpServletResponse response = mvc.perform(get(route)).andReturn().getResponse();
            assertThat(response.getStatus()).as(route).isEqualTo(401);
            assertThat(response.getContentAsString()).as(route).contains("AUTH_REQUIRED");
        }
    }

    @ParameterizedTest
    @MethodSource("unsafeRequests")
    void nonReadRequestsToThePublicRouteAreNeverAccepted(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        insertBusiness("studio-a", "ACTIVE", "Студио А");

        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).isIn(401, 403);
        assertThat(response.getContentAsString()).doesNotContain("Студио А").doesNotContain("500");
    }

    static Stream<Arguments> unsafeRequests() {
        return Stream.of(
                Arguments.of(post(ROUTE, "studio-a").with(csrf())),
                Arguments.of(put(ROUTE, "studio-a").with(csrf())),
                Arguments.of(patch(ROUTE, "studio-a").with(csrf())),
                Arguments.of(delete(ROUTE, "studio-a").with(csrf())),
                Arguments.of(post(ROUTE, "studio-a")),
                Arguments.of(delete(ROUTE, "studio-a")),
                Arguments.of(head(ROUTE, "studio-a")));
    }

    @Test
    void anAuthenticatedNonReadRequestIsRejectedAndChangesNothing() throws Exception {
        UUID business = insertBusiness("studio-a", "ACTIVE", "Студио А");
        UUID userId = insertUser("user@example.invalid");
        Cookie session = session(userId, "user-session");

        for (var request : List.of(
                post(ROUTE, "studio-a"), put(ROUTE, "studio-a"), delete(ROUTE, "studio-a"))) {
            MockHttpServletResponse response = mvc.perform(
                            request.with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"displayName\":\"Changed\"}"))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("ACCESS_DENIED");
        }
        assertThat(jdbc.sql("SELECT display_name FROM business WHERE id = :id")
                        .param("id", business).query(String.class).single())
                .isEqualTo("Студио А");
    }

    @Test
    void corsPolicyForThePublicRouteIsTheUnchangedExactOriginPolicy() throws Exception {
        insertBusiness("studio-a", "ACTIVE", "Студио А");

        MockHttpServletResponse approved = mvc.perform(
                        get(ROUTE, "studio-a").header("Origin", APPROVED_ORIGIN))
                .andReturn().getResponse();
        MockHttpServletResponse other = mvc.perform(
                        get(ROUTE, "studio-a").header("Origin", "https://unapproved.invalid"))
                .andReturn().getResponse();

        assertThat(approved.getStatus()).isEqualTo(200);
        assertThat(approved.getHeader("Access-Control-Allow-Origin")).isEqualTo(APPROVED_ORIGIN);
        assertThat(other.getStatus()).isEqualTo(403);
        assertThat(other.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void viewingTheProfileCreatesNoDomainOrSessionData() throws Exception {
        UUID business = insertBusiness("studio-a", "ACTIVE", "Студио А");
        insertService(business, "Alpha", null, 30, "10.00", true);
        insertBusiness("draft-studio", "DRAFT", "Проект");
        List<Long> before = counts();

        ok("studio-a");
        ok("studio-a");
        assertUnavailable("draft-studio");
        assertUnavailable("unknown-studio");
        assertUnavailable("bad_slug");
        mvc.perform(get(ROUTE, "studio-a").header("Origin", APPROVED_ORIGIN));

        assertThat(counts()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"studio-a", "STUDIO-A", "unknown"})
    void theEndpointNeverDependsOnAnInvalidSessionCookie(String slug) throws Exception {
        insertBusiness("studio-a", "ACTIVE", "Студио А");
        Cookie stale = new Cookie("SPOTYOURSESSION", "not-a-real-session");

        MockHttpServletResponse anonymous = mvc.perform(get(ROUTE, slug)).andReturn().getResponse();
        MockHttpServletResponse withStaleCookie = mvc.perform(get(ROUTE, slug).cookie(stale))
                .andReturn().getResponse();

        assertThat(withStaleCookie.getStatus()).isEqualTo(anonymous.getStatus());
        assertThat(withStaleCookie.getContentAsString()).isEqualTo(anonymous.getContentAsString());
    }

    // ---- helpers ----

    private JsonNode ok(String slug) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(ROUTE, slug)).andReturn().getResponse();
        assertThat(response.getStatus()).as(slug).isEqualTo(200);
        return json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String raw(String slug) throws Exception {
        return mvc.perform(get(ROUTE, slug)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void assertUnavailable(String slug) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(ROUTE, slug)).andReturn().getResponse();
        assertThat(response.getStatus()).as(slug).isEqualTo(404);
        assertThat(json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(json.readTree(compact(UNAVAILABLE_BODY)));
    }

    private static String compact(String value) {
        return value.replaceAll("\\s*\\n\\s*", "");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        names.addAll(node.propertyNames());
        return names;
    }

    private static List<String> names(JsonNode profile) {
        List<String> names = new ArrayList<>();
        profile.get("services").forEach(service -> names.add(service.get("name").asString()));
        return names;
    }

    private List<Long> counts() {
        List<Long> counts = new ArrayList<>();
        for (String table : List.of(
                "business", "service", "staff_member", "membership", "app_user",
                "user_session", "owner_invitation", "password_reset")) {
            counts.add(jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }

    private UUID insertBusiness(String slug, String status, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,description,
                            city,postal_code,street,street_number,phone,contact_email,
                            created_at,updated_at)
                        VALUES (:id,:slug,:name,'HAIR_SALON',:status,'Europe/Sofia',
                                'Описание на студиото','София','1000','Примерна улица','1',
                                '+359 88 000 0000','private@example.invalid',:now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("name", displayName)
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertBareBusiness(String slug, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,'Минимален бизнес','OTHER',:status,'Europe/Sofia',:now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("status", status)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertService(
            UUID business, String name, String description, int minutes, String price, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (:id,:business,:name,:description,:minutes,CAST(:price AS numeric),
                                :active,0,:now,:now)
                        """)
                .param("id", id)
                .param("business", business)
                .param("name", name)
                .param("description", description)
                .param("minutes", minutes)
                .param("price", price)
                .param("active", active)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertUser(String email) {
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

    private Cookie session(UUID userId, String rawToken) {
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
}
