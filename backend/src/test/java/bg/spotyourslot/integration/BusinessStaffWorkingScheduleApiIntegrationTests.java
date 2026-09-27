package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.identity.domain.TokenCodec;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
class BusinessStaffWorkingScheduleApiIntegrationTests extends PostgresIntegrationTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final UUID MISSING_STAFF_MEMBER_ID =
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
    void ownerCompletesEmptyThenPopulatedRoundTripWithCanonicalTimesAndOrdering()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        getSchedule(owner, staffMemberId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.staffMemberId").value(staffMemberId.toString()))
                .andExpect(jsonPath("$.timezone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.periods").isEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.businessId").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());

        String putBody = putSchedule(owner, staffMemberId, 0, """
                        {"weekday":"MONDAY","startTime":"09:00","endTime":"12:00"},
                        {"weekday":"MONDAY","startTime":"13:00","endTime":"17:30"},
                        {"weekday":"TUESDAY","startTime":"23:58","endTime":"23:59"}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.periods.length()").value(3))
                .andExpect(jsonPath("$.periods[0].weekday").value("MONDAY"))
                .andExpect(jsonPath("$.periods[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.periods[0].endTime").value("12:00"))
                .andExpect(jsonPath("$.periods[1].weekday").value("MONDAY"))
                .andExpect(jsonPath("$.periods[1].startTime").value("13:00"))
                .andExpect(jsonPath("$.periods[2].weekday").value("TUESDAY"))
                .andExpect(jsonPath("$.periods[2].startTime").value("23:58"))
                .andExpect(jsonPath("$.periods[2].endTime").value("23:59"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(putBody)
                .doesNotContain("businessId")
                .doesNotContain("userId")
                .doesNotContain(owner.businessId().toString());

        getSchedule(owner, staffMemberId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.periods.length()").value(3));

        putSchedule(owner, staffMemberId, 1, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.periods").isEmpty());
        getSchedule(owner, staffMemberId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods").isEmpty());
        assertPeriodCount(staffMemberId, 0);
    }

    @Test
    void unauthenticatedAndMissingSelectionUseEstablishedSafeProblems() throws Exception {
        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        MISSING_STAFF_MEMBER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(content().string(not(containsString("session"))));

        Actor noSelection = actor("ACTIVE", "BUSINESS_OWNER", true, false, false);
        assertActiveBusinessRequired(getSchedule(noSelection, MISSING_STAFF_MEMBER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void retainedNonOwnerRolesAreDeniedForReadsAndMutations(String role) throws Exception {
        Actor denied = actor("ACTIVE", role, true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(denied.businessId(), true, 0);

        assertAccessDenied(getSchedule(denied, staffMemberId));
        assertAccessDenied(putSchedule(denied, staffMemberId, 0, ""));
    }

    @Test
    void inactiveMissingForeignMembershipAndPlatformAuthorityAloneAreDenied()
            throws Exception {
        Actor inactive = actor("ACTIVE", "BUSINESS_OWNER", false, false, true);
        Actor missing = actor("ACTIVE", null, false, false, true);
        Actor foreign = actorWithForeignMembership();
        Actor platformOnly = actor("ACTIVE", null, false, true, true);
        UUID staffMemberId = insertStaffWithSchedule(inactive.businessId(), true, 0);

        assertActiveBusinessRequired(getSchedule(inactive, staffMemberId));
        assertActiveBusinessRequired(getSchedule(missing, staffMemberId));
        assertActiveBusinessRequired(getSchedule(foreign, staffMemberId));
        assertActiveBusinessRequired(getSchedule(platformOnly, staffMemberId));

        Actor platformOwner = actor("ACTIVE", "BUSINESS_OWNER", true, true, true);
        UUID allowedStaffMemberId = insertStaffWithSchedule(platformOwner.businessId(), true, 0);
        getSchedule(platformOwner, allowedStaffMemberId).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesAllowReadsAndMutations(String businessStatus) throws Exception {
        Actor owner = actor(businessStatus, "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        getSchedule(owner, staffMemberId).andExpect(status().isOk());
        putSchedule(owner, staffMemberId, 0, """
                        {"weekday":"WEDNESDAY","startTime":"09:00","endTime":"12:00"}
                        """)
                .andExpect(status().isOk());
    }

    @Test
    void suspendedBusinessAllowsReadsAndRejectsPut() throws Exception {
        Actor owner = actor("SUSPENDED", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 2);

        getSchedule(owner, staffMemberId).andExpect(status().isOk());
        assertBusinessSuspended(putSchedule(owner, staffMemberId, 2, ""));
        getSchedule(owner, staffMemberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void inactiveStaffMemberAllowsReadAndRejectsPut() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), false, 5);

        getSchedule(owner, staffMemberId).andExpect(status().isOk());
        assertStaffMemberInactive(putSchedule(owner, staffMemberId, 5, ""));
    }

    @Test
    void missingAndCrossBusinessStaffMembersShareSafeNotFound() throws Exception {
        Actor first = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        Actor second = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID foreign = insertStaffWithSchedule(first.businessId(), true, 0);

        for (UUID id : List.of(foreign, MISSING_STAFF_MEMBER_ID)) {
            assertStaffMemberNotFound(getSchedule(second, id));
            assertStaffMemberNotFound(putSchedule(second, id, 0, ""));
        }
    }

    @Test
    void staleVersionIsRejectedAsConcurrentUpdate() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 3);

        assertConcurrentUpdate(putSchedule(owner, staffMemberId, 2, ""));
        assertConcurrentUpdate(putSchedule(owner, staffMemberId, 4, ""));
        getSchedule(owner, staffMemberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void malformedAndInvalidRequestVariantsUseSafeValidationProblem() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"09:00:00","endTime":"17:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"09:00:30","endTime":"17:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"09:00","endTime":"24:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"24:00","endTime":"01:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"23:00","endTime":"24:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"9:00","endTime":"17:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":" 09:00","endTime":"17:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"09:00 ","endTime":"17:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"09:00","endTime":"09:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"17:00","endTime":"09:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"09:00","endTime":"12:00"},
                {"weekday":"MONDAY","startTime":"09:00","endTime":"12:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"09:00","endTime":"12:00"},
                {"weekday":"MONDAY","startTime":"11:00","endTime":"14:00"}
                """));
        assertValidationError(putSchedule(owner, staffMemberId, -1, ""));
        assertValidationError(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"periods\":null}")));
        assertValidationError(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"periods\":[null]}")));
        assertValidationError(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{")));
        assertValidationError(getSchedule(owner, "not-a-uuid"));

        String tooManyPeriods = IntStream.range(0, 101)
                .mapToObj(index -> {
                    int startMinute = index * 2;
                    int endMinute = startMinute + 1;
                    return "{\"weekday\":\"MONDAY\",\"startTime\":\"%02d:%02d\",\"endTime\":\"%02d:%02d\"}"
                            .formatted(
                                    startMinute / 60, startMinute % 60,
                                    endMinute / 60, endMinute % 60);
                })
                .collect(Collectors.joining(","));
        assertValidationError(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"periods\":[" + tooManyPeriods + "]}")));

        assertPeriodCount(staffMemberId, 0);
    }

    @Test
    void twentyFourZeroLexicalValueIsRejectedInEitherPositionWhileTwentyThreeFiftyEightNineRoundTrips()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"24:00","endTime":"01:00"}
                """));
        assertPeriodCount(staffMemberId, 0);

        assertValidationError(putSchedule(owner, staffMemberId, 0, """
                {"weekday":"MONDAY","startTime":"23:00","endTime":"24:00"}
                """));
        assertPeriodCount(staffMemberId, 0);

        putSchedule(owner, staffMemberId, 0, """
                        {"weekday":"TUESDAY","startTime":"23:58","endTime":"23:59"}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods[0].startTime").value("23:58"))
                .andExpect(jsonPath("$.periods[0].endTime").value("23:59"));
        getSchedule(owner, staffMemberId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods[0].startTime").value("23:58"))
                .andExpect(jsonPath("$.periods[0].endTime").value("23:59"));
    }

    @Test
    void missingAndNullRequiredFieldsAndMissingBodyUseSafeValidationProblem()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        assertValidationError(rawPut(owner, staffMemberId, """
                {"periods":[]}
                """));
        assertValidationError(rawPut(owner, staffMemberId, """
                {"expectedVersion":null,"periods":[]}
                """));
        assertValidationError(rawPut(owner, staffMemberId, """
                {"expectedVersion":0}
                """));
        assertValidationError(rawPut(owner, staffMemberId, """
                {"expectedVersion":0,"periods":null}
                """));
        assertValidationError(rawPut(owner, staffMemberId, "null"));
        assertValidationError(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .with(csrf())
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)));

        assertPeriodCount(staffMemberId, 0);
    }

    @Test
    void inactiveMissingForeignAndPlatformOnlyMembershipsAreDeniedForPutTheSameWayAsGet()
            throws Exception {
        Actor inactive = actor("ACTIVE", "BUSINESS_OWNER", false, false, true);
        Actor missing = actor("ACTIVE", null, false, false, true);
        Actor foreign = actorWithForeignMembership();
        Actor platformOnly = actor("ACTIVE", null, false, true, true);

        UUID inactiveStaffMemberId = insertStaffWithSchedule(inactive.businessId(), true, 0);
        UUID missingStaffMemberId = insertStaffWithSchedule(missing.businessId(), true, 0);
        UUID foreignStaffMemberId = insertStaffWithSchedule(foreign.businessId(), true, 0);
        UUID platformOnlyStaffMemberId =
                insertStaffWithSchedule(platformOnly.businessId(), true, 0);

        // Established behavior (matches GET): the authenticated-context resolution
        // treats a selection backed by an inactive, missing, or foreign Membership
        // as no selection at all, surfacing ACTIVE_BUSINESS_REQUIRED rather than
        // ACCESS_DENIED. Platform-admin authority alone does not substitute for an
        // owner Membership either, so it resolves the same way.
        assertActiveBusinessRequired(putSchedule(inactive, inactiveStaffMemberId, 0, ""));
        assertActiveBusinessRequired(putSchedule(missing, missingStaffMemberId, 0, ""));
        assertActiveBusinessRequired(putSchedule(foreign, foreignStaffMemberId, 0, ""));
        assertActiveBusinessRequired(putSchedule(platformOnly, platformOnlyStaffMemberId, 0, ""));

        assertPeriodCount(inactiveStaffMemberId, 0);
        assertPeriodCount(missingStaffMemberId, 0);
        assertPeriodCount(foreignStaffMemberId, 0);
        assertPeriodCount(platformOnlyStaffMemberId, 0);
        assertScheduleVersion(inactiveStaffMemberId, 0);
        assertScheduleVersion(missingStaffMemberId, 0);
        assertScheduleVersion(foreignStaffMemberId, 0);
        assertScheduleVersion(platformOnlyStaffMemberId, 0);
    }

    @Test
    void adjacentPeriodsAreAcceptedAndCanonicalTimesRoundTripWithoutSeconds()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        String body = putSchedule(owner, staffMemberId, 0, """
                        {"weekday":"MONDAY","startTime":"09:00","endTime":"12:00"},
                        {"weekday":"MONDAY","startTime":"12:00","endTime":"17:00"}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("00:00:00").doesNotContain("09:00:00");
    }

    @Test
    void unknownRequestPropertyIsIgnoredByTheEstablishedJsonPolicy() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                        .with(csrf())
                        .cookie(owner.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"periods":[],"businessId":"%s"}
                                """.formatted(owner.businessId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void csrfProtectsThePutEndpoint() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = insertStaffWithSchedule(owner.businessId(), true, 0);

        assertAccessDenied(mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceJson(0, ""))));
        assertPeriodCount(staffMemberId, 0);
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
        String slug = "api-schedule-" + SEQUENCE.incrementAndGet();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Schedule Business','OTHER',:status,'Europe/Sofia',
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
                            :id,:email,'Schedule User','unused',true,false,
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
        String rawToken = "schedule-session-" + UUID.randomUUID();
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

    private UUID insertStaffWithSchedule(
            UUID businessId, boolean active, long scheduleVersion) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,contact_email,contact_phone,
                            active,version,created_at,updated_at)
                        VALUES (
                            :id,:business,'Schedule Staff',NULL,NULL,:active,0,:now,:now)
                        """)
                .param("id", id)
                .param("business", businessId)
                .param("active", active)
                .param("now", now)
                .update();
        jdbc.sql("""
                        INSERT INTO staff_working_schedule(
                            business_id,staff_member_id,version,created_at,updated_at)
                        VALUES (:business,:staffMember,:version,:now,:now)
                        """)
                .param("business", businessId)
                .param("staffMember", id)
                .param("version", scheduleVersion)
                .param("now", now)
                .update();
        return id;
    }

    private ResultActions getSchedule(Actor actor, UUID staffMemberId) throws Exception {
        return getSchedule(actor, staffMemberId.toString());
    }

    private ResultActions getSchedule(Actor actor, String staffMemberId) throws Exception {
        return mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .cookie(actor.session()));
    }

    private ResultActions putSchedule(
            Actor actor, UUID staffMemberId, long expectedVersion, String periodsJson)
            throws Exception {
        return mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .with(csrf())
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceJson(expectedVersion, periodsJson)));
    }

    private ResultActions rawPut(Actor actor, UUID staffMemberId, String body) throws Exception {
        return mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        staffMemberId)
                .with(csrf())
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String replaceJson(long expectedVersion, String periodsJson) {
        String periods = periodsJson.isBlank() ? "" : periodsJson;
        return """
                {
                  "expectedVersion": %d,
                  "periods": [%s]
                }
                """.formatted(expectedVersion, periods);
    }

    private void assertPeriodCount(UUID staffMemberId, int expected) {
        long count = jdbc.sql("""
                        SELECT COUNT(*) FROM staff_working_period
                        WHERE staff_member_id = :id
                        """)
                .param("id", staffMemberId)
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(expected);
    }

    private void assertScheduleVersion(UUID staffMemberId, long expected) {
        long version = jdbc.sql("""
                        SELECT version FROM staff_working_schedule
                        WHERE staff_member_id = :id
                        """)
                .param("id", staffMemberId)
                .query(Long.class)
                .single();
        assertThat(version).isEqualTo(expected);
    }

    private void assertValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."));
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

    private void assertStaffMemberInactive(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_MEMBER_INACTIVE"));
    }

    private void assertConcurrentUpdate(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKING_SCHEDULE_CONCURRENT_UPDATE"));
    }

    private void assertBusinessSuspended(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"))
                .andExpect(jsonPath("$.detail").value(
                        "Спрян бизнес може само да преглежда данните си."));
    }

    private record Actor(UUID businessId, UUID userId, Cookie session) {
    }
}
