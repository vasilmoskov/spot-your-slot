package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
        statements = "TRUNCATE business,app_user CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessStaffMemberApiIntegrationTests extends PostgresIntegrationTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final UUID MISSING_STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000404");
    private static final UUID MISSING_SERVICE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000405");

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
    void activeOwnerCompletesProfileLifecycleAndInactiveAssignmentJourney()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID untrustedBusinessId = business("ACTIVE");
        UUID serviceId = insertService(owner.businessId(), "Подстригване", true);

        String creationBody = create(
                        owner,
                        "  Анна\u00A0 Иванова  ",
                        " TEAM@EXAMPLE.INVALID ",
                        " +359 (2) 123-45-67 ",
                        untrustedBusinessId,
                        true)
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", containsString("/api/business/staff-members/")))
                .andExpect(jsonPath("$", aMapWithSize(8)))
                .andExpect(jsonPath("$.displayName").value("Анна Иванова"))
                .andExpect(jsonPath("$.contactEmail").value("team@example.invalid"))
                .andExpect(jsonPath("$.contactPhone").value("+359 (2) 123-45-67"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.businessId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.membershipId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID staffMemberId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(creationBody, "$.id"));
        assertThat(jdbc.sql("SELECT business_id FROM staff_member WHERE id=:id")
                .param("id", staffMemberId)
                .query(UUID.class)
                .single()).isEqualTo(owner.businessId());
        assertMutationPrivacy(creationBody, owner);

        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}", staffMemberId)
                        .cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(staffMemberId.toString()))
                .andExpect(jsonPath("$.businessId").doesNotExist());
        mvc.perform(get("/api/business/staff-members").cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.staffMembers[0].id").value(staffMemberId.toString()))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1));

        String updatedBody = update(
                        owner,
                        staffMemberId,
                        "Анна Петрова",
                        null,
                        "+359 888 123 456",
                        0,
                        true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Анна Петрова"))
                .andExpect(jsonPath("$.contactEmail").isEmpty())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertMutationPrivacy(updatedBody, owner);

        lifecycle(owner, staffMemberId, "deactivate", 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(2));
        update(owner, staffMemberId, "Неактивна Анна", null, null, 2, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(3));

        String assignmentsBody = replaceAssignments(
                        owner, staffMemberId, List.of(serviceId), 3, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(5)))
                .andExpect(jsonPath("$.staffMemberId").value(staffMemberId.toString()))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.services[0].id").value(serviceId.toString()))
                .andExpect(jsonPath("$.services[0].name").value("Подстригване"))
                .andExpect(jsonPath("$.services[0].active").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertMutationPrivacy(assignmentsBody, owner);
        assertAuthoritativeAssignmentTimes(staffMemberId, assignmentsBody);

        lifecycle(owner, staffMemberId, "reactivate", 4, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(5));
        assertStaffState(staffMemberId, "Неактивна Анна", true, 5);
    }

    @Test
    void unauthenticatedAndMissingSelectionUseEstablishedSafeProblems()
            throws Exception {
        mvc.perform(get("/api/business/staff-members"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(content().string(not(containsString("session"))));

        Actor noSelection = actor("ACTIVE", "BUSINESS_OWNER", true, false, false);
        assertActiveBusinessRequired(mvc.perform(get("/api/business/staff-members")
                .cookie(noSelection.session())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void retainedNonOwnerRolesAreDeniedForReadsAndMutations(String role)
            throws Exception {
        Actor denied = actor("ACTIVE", role, true, false, true);

        assertAccessDenied(mvc.perform(get("/api/business/staff-members")
                .cookie(denied.session())));
        assertAccessDenied(create(denied, "Denied", null, null, null, true));
    }

    @Test
    void inactiveMissingForeignMembershipAndPlatformAuthorityAloneAreDenied()
            throws Exception {
        Actor inactive = actor("ACTIVE", "BUSINESS_OWNER", false, false, true);
        Actor missing = actor("ACTIVE", null, false, false, true);
        Actor foreign = actorWithForeignMembership();
        Actor platformOnly = actor("ACTIVE", null, false, true, true);

        assertActiveBusinessRequired(mvc.perform(get("/api/business/staff-members")
                .cookie(inactive.session())));
        assertActiveBusinessRequired(mvc.perform(get("/api/business/staff-members")
                .cookie(missing.session())));
        assertActiveBusinessRequired(mvc.perform(get("/api/business/staff-members")
                .cookie(foreign.session())));
        assertActiveBusinessRequired(mvc.perform(get("/api/business/staff-members")
                .cookie(platformOnly.session())));

        Actor platformOwner = actor("ACTIVE", "BUSINESS_OWNER", true, true, true);
        create(platformOwner, "Allowed owner", null, null, null, true)
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesAllowReadsAndAllMutationStyles(String businessStatus)
            throws Exception {
        Actor owner = actor(businessStatus, "BUSINESS_OWNER", true, false, true);
        UUID serviceId = insertService(owner.businessId(), "Услуга", true);
        UUID staffMemberId = createdId(create(
                owner, businessStatus + " член", null, null, null, true));

        mvc.perform(get("/api/business/staff-members").cookie(owner.session()))
                .andExpect(status().isOk());
        update(owner, staffMemberId, "Обновен", null, null, 0, true)
                .andExpect(status().isOk());
        replaceAssignments(owner, staffMemberId, List.of(serviceId), 1, true)
                .andExpect(status().isOk());
        lifecycle(owner, staffMemberId, "deactivate", 2, true)
                .andExpect(status().isOk());
        lifecycle(owner, staffMemberId, "reactivate", 3, true)
                .andExpect(status().isOk());
    }

    @Test
    void suspendedBusinessAllowsReadsAndRejectsEveryMutation() throws Exception {
        Actor owner = actor("SUSPENDED", "BUSINESS_OWNER", true, false, true);
        UUID activeStaff = insertStaff(owner.businessId(), "Активен", true, 2);
        UUID inactiveStaff = insertStaff(owner.businessId(), "Неактивен", false, 3);
        UUID serviceId = insertService(owner.businessId(), "Видима услуга", true);
        insertAssignment(owner.businessId(), activeStaff, serviceId);

        mvc.perform(get("/api/business/staff-members").cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}", activeStaff)
                        .cookie(owner.session()))
                .andExpect(status().isOk());
        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/service-assignments",
                        activeStaff)
                        .cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].id").value(serviceId.toString()));

        assertBusinessSuspended(create(owner, "Denied", null, null, null, true));
        assertBusinessSuspended(update(
                owner, activeStaff, "Denied", null, null, 2, true));
        assertBusinessSuspended(lifecycle(
                owner, activeStaff, "deactivate", 2, true));
        assertBusinessSuspended(lifecycle(
                owner, inactiveStaff, "reactivate", 3, true));
        assertBusinessSuspended(replaceAssignments(
                owner, activeStaff, List.of(), 2, true));
        assertStaffState(activeStaff, "Активен", true, 2);
        assertStaffState(inactiveStaff, "Неактивен", false, 3);
        assertAssignmentIds(activeStaff, List.of(serviceId));
    }

    @Test
    void listingUsesDefaultsAndBoundedDeterministicExplicitPagination()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID alphaFirst = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID alphaSecond = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID beta = UUID.fromString("00000000-0000-0000-0000-000000000003");
        insertStaff(alphaSecond, owner.businessId(), "Alpha second", true, 0);
        insertStaff(alphaFirst, owner.businessId(), "Alpha first", false, 1);
        insertStaff(beta, owner.businessId(), "Beta", true, 2);

        mvc.perform(get("/api/business/staff-members").cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/business/staff-members")
                        .cookie(owner.session())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffMembers[0].id").value(alphaFirst.toString()))
                .andExpect(jsonPath("$.staffMembers[0].active").value(false))
                .andExpect(jsonPath("$.staffMembers[1].id").value(alphaSecond.toString()))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/business/staff-members")
                        .cookie(owner.session())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffMembers[0].id").value(beta.toString()));

        assertValidationError(mvc.perform(get("/api/business/staff-members")
                .cookie(owner.session()).param("page", "-1")));
        assertValidationError(mvc.perform(get("/api/business/staff-members")
                .cookie(owner.session()).param("size", "0")));
        assertValidationError(mvc.perform(get("/api/business/staff-members")
                .cookie(owner.session()).param("size", "101")));
    }

    @Test
    void missingAndCrossBusinessStaffMembersAreIndistinguishableAndImmutable()
            throws Exception {
        Actor first = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        Actor second = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID foreign = insertStaff(first.businessId(), "Чужд", true, 4);
        UUID foreignService = insertService(first.businessId(), "Чужда услуга", true);
        insertAssignment(first.businessId(), foreign, foreignService);

        for (UUID id : List.of(foreign, MISSING_STAFF_MEMBER_ID)) {
            assertStaffMemberNotFound(mvc.perform(get(
                            "/api/business/staff-members/{staffMemberId}", id)
                    .cookie(second.session())));
            assertStaffMemberNotFound(mvc.perform(get(
                            "/api/business/staff-members/{staffMemberId}/service-assignments",
                            id)
                    .cookie(second.session())));
        }
        assertStaffMemberNotFound(update(
                second, foreign, "Intrusion", null, null, 4, true));
        assertStaffMemberNotFound(lifecycle(
                second, foreign, "deactivate", 4, true));
        assertStaffMemberNotFound(replaceAssignments(
                second, foreign, List.of(), 4, true));
        assertStaffState(foreign, "Чужд", true, 4);
        assertAssignmentIds(foreign, List.of(foreignService));
    }

    @Test
    void staleVersionsInvalidLifecycleAndInvalidInputsUseExactSafeProblems()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID active = insertStaff(owner.businessId(), "Активен", true, 2);
        UUID inactive = insertStaff(owner.businessId(), "Неактивен", false, 4);
        UUID serviceId = insertService(owner.businessId(), "Услуга", true);

        assertConcurrent(update(owner, active, "Stale", null, null, 1, true));
        assertConcurrent(lifecycle(owner, active, "deactivate", 1, true));
        assertConcurrent(replaceAssignments(
                owner, active, List.of(serviceId), 1, true));
        assertInvalidLifecycle(lifecycle(owner, active, "reactivate", 2, true));
        assertInvalidLifecycle(lifecycle(owner, inactive, "deactivate", 4, true));

        assertValidationError(create(owner, " ", "private@example.invalid", null, null, true));
        assertValidationError(create(owner, "Valid", "invalid", null, null, true));
        assertValidationError(create(owner, "Valid", null, "12", null, true));
        assertValidationError(update(owner, active, "Valid", null, null, -1, true));
        assertValidationError(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/service-assignments",
                        active)
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceIds\":null,\"expectedVersion\":2}")));
        assertValidationError(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/service-assignments",
                        active)
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceIds\":[null],\"expectedVersion\":2}")));
        assertValidationError(replaceAssignments(
                owner, active, List.of(serviceId, serviceId), 2, true));
        assertValidationError(mvc.perform(get(
                        "/api/business/staff-members/not-a-uuid")
                .cookie(owner.session())));
        assertValidationError(mvc.perform(post("/api/business/staff-members")
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{")));
        assertStaffState(active, "Активен", true, 2);
    }

    @Test
    void csrfProtectsEveryPostAndPutEndpoint() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID active = insertStaff(owner.businessId(), "Активен", true, 0);
        UUID inactive = insertStaff(owner.businessId(), "Неактивен", false, 0);
        UUID serviceId = insertService(owner.businessId(), "Услуга", true);

        assertAccessDenied(create(owner, "No csrf", null, null, null, false));
        assertAccessDenied(update(
                owner, active, "No csrf", null, null, 0, false));
        assertAccessDenied(lifecycle(
                owner, active, "deactivate", 0, false));
        assertAccessDenied(lifecycle(
                owner, inactive, "reactivate", 0, false));
        assertAccessDenied(replaceAssignments(
                owner, active, List.of(serviceId), 0, false));
    }

    @Test
    void assignmentsSupportZeroOneMultipleOrderedAndInactiveServiceRules()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaff(owner.businessId(), "Назначения", false, 0);
        UUID beta = insertService(owner.businessId(), "Б услуга", true);
        UUID alpha = insertService(owner.businessId(), "А услуга", true);
        UUID inactiveNew = insertService(owner.businessId(), "В неактивна", false);

        replaceAssignments(owner, staffMemberId, List.of(), 0, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.services").isEmpty());
        replaceAssignments(owner, staffMemberId, List.of(beta), 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.services[0].id").value(beta.toString()));
        replaceAssignments(owner, staffMemberId, List.of(beta, alpha), 2, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.services[0].id").value(alpha.toString()))
                .andExpect(jsonPath("$.services[1].id").value(beta.toString()));

        setServiceActive(beta, false);
        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/service-assignments",
                        staffMemberId)
                        .cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[1].id").value(beta.toString()))
                .andExpect(jsonPath("$.services[1].active").value(false));
        replaceAssignments(owner, staffMemberId, List.of(beta, alpha), 3, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        replaceAssignments(owner, staffMemberId, List.of(alpha), 4, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.services.length()").value(1));

        assertServiceInactive(replaceAssignments(
                owner, staffMemberId, List.of(alpha, beta), 5, true));
        assertServiceInactive(replaceAssignments(
                owner, staffMemberId, List.of(alpha, inactiveNew), 5, true));
        assertAssignmentIds(staffMemberId, List.of(alpha));
        assertStaffState(staffMemberId, "Назначения", false, 5);
    }

    @Test
    void missingAndCrossBusinessServicesShareSafeNotFoundAndRollBack()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        Actor other = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaff(owner.businessId(), "Protected", true, 0);
        UUID retained = insertService(owner.businessId(), "Retained", true);
        UUID foreign = insertService(other.businessId(), "Foreign", true);
        replaceAssignments(owner, staffMemberId, List.of(retained), 0, true)
                .andExpect(status().isOk());

        assertServiceNotFound(replaceAssignments(
                owner, staffMemberId, List.of(retained, foreign), 1, true));
        assertServiceNotFound(replaceAssignments(
                owner, staffMemberId, List.of(retained, MISSING_SERVICE_ID), 1, true));
        assertAssignmentIds(staffMemberId, List.of(retained));
        assertStaffState(staffMemberId, "Protected", true, 1);
    }

    @Test
    void unexpectedPostgresqlFailureUsesSanitizedGeneric500() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        jdbc.sql("""
                        CREATE FUNCTION phase5_reject_staff_member() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            RAISE EXCEPTION 'private phase5 SQL tenant diagnostic'
                                USING ERRCODE = 'XX000';
                        END
                        $$
                        """)
                .update();
        jdbc.sql("""
                        CREATE TRIGGER phase5_reject_staff_member_trigger
                        BEFORE INSERT ON staff_member
                        FOR EACH ROW EXECUTE FUNCTION phase5_reject_staff_member()
                        """)
                .update();

        try {
            create(owner, "Failure", "private@example.invalid", null, null, true)
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.detail")
                            .value("Възникна неочаквана грешка."))
                    .andExpect(content().string(not(containsString("private"))))
                    .andExpect(content().string(not(containsString("XX000"))))
                    .andExpect(content().string(not(containsString("SQL"))))
                    .andExpect(content().string(not(containsString("constraint"))))
                    .andExpect(content().string(not(containsString("exception"))))
                    .andExpect(content().string(not(containsString("stackTrace"))))
                    .andExpect(content().string(not(containsString(owner.businessId().toString()))))
                    .andExpect(content().string(not(containsString(owner.userId().toString()))));
        } finally {
            jdbc.sql("DROP TRIGGER phase5_reject_staff_member_trigger ON staff_member")
                    .update();
            jdbc.sql("DROP FUNCTION phase5_reject_staff_member()").update();
        }
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
            grantPlatformAdmin(userId);
        }
        Cookie session = session(userId, selectBusiness ? businessId : null);
        return new Actor(businessId, userId, session);
    }

    private Actor actorWithForeignMembership() {
        UUID selectedBusiness = business("ACTIVE");
        UUID membershipBusiness = business("ACTIVE");
        UUID userId = user();
        membership(userId, membershipBusiness, "BUSINESS_OWNER", true);
        return new Actor(selectedBusiness, userId, session(userId, selectedBusiness));
    }

    private UUID business(String statusValue) {
        UUID id = UUID.randomUUID();
        String slug = "api-staff-" + SEQUENCE.incrementAndGet();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Staff Business','OTHER',:status,'Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("status", statusValue)
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
                            :id,:email,'Staff User','unused',true,false,
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

    private void grantPlatformAdmin(UUID userId) {
        jdbc.sql("""
                        INSERT INTO platform_role(user_id,role,created_at)
                        VALUES (:user,'PLATFORM_ADMIN',:now)
                        """)
                .param("user", userId)
                .param("now", now)
                .update();
    }

    private Cookie session(UUID userId, UUID selectedBusinessId) {
        String rawToken = "staff-session-" + UUID.randomUUID();
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

    private UUID insertStaff(UUID businessId, String name, boolean active, long version) {
        UUID id = UUID.randomUUID();
        insertStaff(id, businessId, name, active, version);
        return id;
    }

    private void insertStaff(
            UUID id, UUID businessId, String name, boolean active, long version) {
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,contact_email,contact_phone,
                            active,version,created_at,updated_at)
                        VALUES (
                            :id,:business,:name,NULL,NULL,:active,:version,:now,:now)
                        """)
                .param("id", id)
                .param("business", businessId)
                .param("name", name)
                .param("active", active)
                .param("version", version)
                .param("now", now)
                .update();
    }

    private UUID insertService(UUID businessId, String name, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (
                            :id,:business,:name,NULL,30,:price,:active,0,:now,:now)
                        """)
                .param("id", id)
                .param("business", businessId)
                .param("name", name)
                .param("price", new BigDecimal("20.00"))
                .param("active", active)
                .param("now", now)
                .update();
        return id;
    }

    private void setServiceActive(UUID serviceId, boolean active) {
        jdbc.sql("""
                        UPDATE service
                        SET active=:active,version=version+1,updated_at=:now
                        WHERE id=:id
                        """)
                .param("active", active)
                .param("now", now)
                .param("id", serviceId)
                .update();
    }

    private void insertAssignment(UUID businessId, UUID staffMemberId, UUID serviceId) {
        jdbc.sql("""
                        INSERT INTO staff_member_service(
                            business_id,staff_member_id,service_id)
                        VALUES (:business,:staffMember,:service)
                        """)
                .param("business", businessId)
                .param("staffMember", staffMemberId)
                .param("service", serviceId)
                .update();
    }

    private ResultActions create(
            Actor actor,
            String displayName,
            String contactEmail,
            String contactPhone,
            UUID clientBusinessId,
            boolean withCsrf) throws Exception {
        var request = post("/api/business/staff-members")
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJson(
                        displayName, contactEmail, contactPhone, clientBusinessId));
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions update(
            Actor actor,
            UUID staffMemberId,
            String displayName,
            String contactEmail,
            String contactPhone,
            long expectedVersion,
            boolean withCsrf) throws Exception {
        var request = put(
                        "/api/business/staff-members/{staffMemberId}", staffMemberId)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateJson(
                        displayName, contactEmail, contactPhone, expectedVersion));
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions lifecycle(
            Actor actor,
            UUID staffMemberId,
            String operation,
            long expectedVersion,
            boolean withCsrf) throws Exception {
        var request = post(
                        "/api/business/staff-members/{staffMemberId}/{operation}",
                        staffMemberId,
                        operation)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":" + expectedVersion + "}");
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private ResultActions replaceAssignments(
            Actor actor,
            UUID staffMemberId,
            List<UUID> serviceIds,
            long expectedVersion,
            boolean withCsrf) throws Exception {
        String ids = serviceIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        var request = put(
                        "/api/business/staff-members/{staffMemberId}/service-assignments",
                        staffMemberId)
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceIds\":[" + ids + "],\"expectedVersion\":"
                        + expectedVersion + "}");
        if (withCsrf) {
            request.with(csrf());
        }
        return mvc.perform(request);
    }

    private UUID createdId(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
    }

    private static String createJson(
            String displayName,
            String contactEmail,
            String contactPhone,
            UUID businessId) {
        return """
                {
                  "displayName": %s,
                  "contactEmail": %s,
                  "contactPhone": %s%s
                }
                """.formatted(
                jsonString(displayName),
                jsonString(contactEmail),
                jsonString(contactPhone),
                businessId == null
                        ? ""
                        : ",\n  \"businessId\": \"" + businessId + "\"");
    }

    private static String updateJson(
            String displayName,
            String contactEmail,
            String contactPhone,
            long expectedVersion) {
        return """
                {
                  "displayName": %s,
                  "contactEmail": %s,
                  "contactPhone": %s,
                  "expectedVersion": %d
                }
                """.formatted(
                jsonString(displayName),
                jsonString(contactEmail),
                jsonString(contactPhone),
                expectedVersion);
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void assertAuthoritativeAssignmentTimes(UUID staffMemberId, String body) {
        StaffTimes database = jdbc.sql("""
                        SELECT created_at,updated_at FROM staff_member WHERE id=:id
                        """)
                .param("id", staffMemberId)
                .query((resultSet, rowNumber) -> new StaffTimes(
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
        assertThat(Instant.parse(com.jayway.jsonpath.JsonPath.read(body, "$.createdAt")))
                .isEqualTo(database.createdAt());
        assertThat(Instant.parse(com.jayway.jsonpath.JsonPath.read(body, "$.updatedAt")))
                .isEqualTo(database.updatedAt());
    }

    private void assertMutationPrivacy(String body, Actor actor) {
        assertThat(body)
                .doesNotContain("businessId")
                .doesNotContain("userId")
                .doesNotContain("membershipId")
                .doesNotContain("normalized")
                .doesNotContain("role")
                .doesNotContain("session")
                .doesNotContain(actor.businessId().toString())
                .doesNotContain(actor.userId().toString());
    }

    private void assertStaffState(UUID id, String name, boolean active, long version) {
        StaffState state = jdbc.sql("""
                        SELECT display_name,active,version FROM staff_member WHERE id=:id
                        """)
                .param("id", id)
                .query((resultSet, rowNumber) -> new StaffState(
                        resultSet.getString("display_name"),
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version")))
                .single();
        assertThat(state).isEqualTo(new StaffState(name, active, version));
    }

    private void assertAssignmentIds(UUID staffMemberId, List<UUID> expected) {
        assertThat(jdbc.sql("""
                        SELECT service_id FROM staff_member_service
                        WHERE staff_member_id=:staffMemberId ORDER BY service_id
                        """)
                .param("staffMemberId", staffMemberId)
                .query(UUID.class)
                .list()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private void assertValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."))
                .andExpect(content().string(not(containsString("private@example.invalid"))))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString("exception"))));
    }

    private void assertActiveBusinessRequired(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_BUSINESS_REQUIRED"))
                .andExpect(jsonPath("$.detail")
                        .value("Изберете бизнес, за да продължите."));
    }

    private void assertAccessDenied(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail")
                        .value("Нямате достъп до тази операция."));
    }

    private void assertStaffMemberNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAFF_MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.detail")
                        .value("Членът на екипа не е намерен."));
    }

    private void assertServiceNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Услугата не е намерена."));
    }

    private void assertConcurrent(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_MEMBER_CONCURRENT_UPDATE"))
                .andExpect(jsonPath("$.detail").value(
                        "Данните за члена на екипа са променени. "
                                + "Обновете данните и опитайте отново."));
    }

    private void assertInvalidLifecycle(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_MEMBER_INVALID_LIFECYCLE"))
                .andExpect(jsonPath("$.detail").value(
                        "Промяната на състоянието на члена на екипа не е разрешена."));
    }

    private void assertServiceInactive(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_INACTIVE"))
                .andExpect(jsonPath("$.detail").value(
                        "Неактивна услуга не може да бъде добавена към член на екипа."));
    }

    private void assertBusinessSuspended(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"))
                .andExpect(jsonPath("$.detail").value(
                        "Спрян бизнес може само да преглежда данните си."));
    }

    private record Actor(UUID businessId, UUID userId, Cookie session) {
    }

    private record StaffState(String displayName, boolean active, long version) {
    }

    private record StaffTimes(Instant createdAt, Instant updatedAt) {
    }
}
