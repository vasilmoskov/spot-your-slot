package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.identity.domain.TokenCodec;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@Sql(
        statements = "TRUNCATE business,app_user CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessScheduleExceptionApiIntegrationTests extends PostgresIntegrationTest {
    private static final String BASE = "/api/business/schedule-exceptions";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final UUID MISSING_ID = UUID.fromString("00000000-0000-0000-0000-000000000404");

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
    void ownerCompletesAllFourKindsThroughCreateGetListReplaceAndDelete() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);

        String closureBody = create(owner, closureJson("2026-12-24", "2026-12-26"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString(BASE + "/")))
                .andExpect(jsonPath("$", aMapWithSize(11)))
                .andExpect(jsonPath("$.kind").value("BUSINESS_CLOSURE"))
                .andExpect(jsonPath("$.staffMemberId").value((Object) null))
                .andExpect(jsonPath("$.firstDate").value("2026-12-24"))
                .andExpect(jsonPath("$.lastDate").value("2026-12-26"))
                .andExpect(jsonPath("$.allDay").value(true))
                .andExpect(jsonPath("$.periods").isEmpty())
                .andExpect(jsonPath("$.timezone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(closureBody)
                .doesNotContain("businessId")
                .doesNotContain("userId")
                .doesNotContain(owner.businessId().toString());
        UUID closureId = idOf(closureBody);

        UUID timeOffId = idOf(create(owner, staffJson(
                        "STAFF_TIME_OFF", staffMemberId, "2026-12-24", "2026-12-24", false,
                        """
                        {"startTime":"13:00","endTime":"14:30"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("STAFF_TIME_OFF"))
                .andExpect(jsonPath("$.staffMemberId").value(staffMemberId.toString()))
                .andExpect(jsonPath("$.periods[0].startTime").value("13:00"))
                .andExpect(jsonPath("$.periods[0].endTime").value("14:30"))
                .andReturn().getResponse().getContentAsString());
        UUID overrideId = idOf(create(owner, staffJson(
                        "WORKING_DAY_OVERRIDE", staffMemberId, "2026-12-24", "2026-12-24", false, ""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periods").isEmpty())
                .andExpect(jsonPath("$.allDay").value(false))
                .andReturn().getResponse().getContentAsString());
        UUID additionalId = idOf(create(owner, staffJson(
                        "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24", false,
                        """
                        {"startTime":"18:00","endTime":"20:00"},
                        {"startTime":"09:00","endTime":"10:00"},
                        {"startTime":"10:00","endTime":"11:00"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periods.length()").value(3))
                .andExpect(jsonPath("$.periods[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.periods[1].startTime").value("10:00"))
                .andExpect(jsonPath("$.periods[2].endTime").value("20:00"))
                .andReturn().getResponse().getContentAsString());

        for (UUID id : List.of(closureId, timeOffId, overrideId, additionalId)) {
            getOne(owner, id).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.timezone").value("Europe/Sofia"));
        }

        list(owner, "2026-12-24", "2026-12-24")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.from").value("2026-12-24"))
                .andExpect(jsonPath("$.to").value("2026-12-24"))
                .andExpect(jsonPath("$.timezone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.exceptions.length()").value(4))
                .andExpect(jsonPath("$.exceptions[0].kind").value("ADDITIONAL_WORKING_PERIODS"))
                .andExpect(jsonPath("$.exceptions[0].timezone").doesNotExist())
                .andExpect(jsonPath("$.exceptions[0].businessId").doesNotExist());
        list(owner, "2026-12-27", "2026-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions").isEmpty());

        replace(owner, closureId, replaceJson(0, "2026-12-24", "2026-12-28", true, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lastDate").value("2026-12-28"))
                .andExpect(jsonPath("$.kind").value("BUSINESS_CLOSURE"));
        replace(owner, overrideId, replaceJson(0, "2026-12-24", "2026-12-24", false,
                        """
                        {"startTime":"12:00","endTime":"16:00"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods.length()").value(1));
        replace(owner, overrideId, replaceJson(1, "2026-12-24", "2026-12-24", false, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.periods").isEmpty());

        for (UUID id : List.of(closureId, timeOffId, additionalId)) {
            long version = storedVersion(id);
            deleteOne(owner, id, version).andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            getOne(owner, id).andExpect(status().isNotFound());
        }
        assertThat(exceptionCount(owner.businessId())).isEqualTo(1);
        assertThat(periodCount(additionalId)).isZero();
    }

    @Test
    void unauthenticatedRequestsGet401OnEveryRouteAndMissingSelectionGets403()
            throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get(BASE).param("from", "2026-12-24").param("to", "2026-12-24"),
                get(BASE + "/{id}", MISSING_ID),
                post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(closureJson("2026-12-24", "2026-12-24")),
                put(BASE + "/{id}", MISSING_ID).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(replaceJson(0, "2026-12-24", "2026-12-24", true, "")),
                delete(BASE + "/{id}", MISSING_ID).with(csrf()).param("expectedVersion", "0"))) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                    .andExpect(content().string(not(containsString("session"))));
        }

        Actor noSelection = actor("ACTIVE", "BUSINESS_OWNER", true, false, false);
        assertActiveBusinessRequired(list(noSelection, "2026-12-24", "2026-12-24"));
        assertActiveBusinessRequired(getOne(noSelection, MISSING_ID));
        assertActiveBusinessRequired(create(noSelection, closureJson("2026-12-24", "2026-12-24")));
        assertActiveBusinessRequired(replace(
                noSelection, MISSING_ID, replaceJson(0, "2026-12-24", "2026-12-24", true, "")));
        assertActiveBusinessRequired(deleteOne(noSelection, MISSING_ID, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void retainedNonOwnerRolesAreDeniedOnEveryRoute(String role) throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID id = idOf(create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andReturn().getResponse().getContentAsString());
        UUID userId = user();
        membership(userId, owner.businessId(), role, true);
        Actor denied = new Actor(owner.businessId(), userId, session(userId, owner.businessId()));

        assertAccessDenied(list(denied, "2026-12-24", "2026-12-24"));
        assertAccessDenied(getOne(denied, id));
        assertAccessDenied(create(denied, closureJson("2026-12-30", "2026-12-30")));
        assertAccessDenied(replace(
                denied, id, replaceJson(0, "2026-12-24", "2026-12-25", true, "")));
        assertAccessDenied(deleteOne(denied, id, 0));
        assertThat(storedVersion(id)).isZero();
        assertThat(exceptionCount(owner.businessId())).isEqualTo(1);
    }

    @Test
    void inactiveMissingForeignMembershipAndPlatformAuthorityAloneAreRejectedAndOwnerWithPlatformRoleIsAllowed()
            throws Exception {
        Actor inactive = actor("ACTIVE", "BUSINESS_OWNER", false, false, true);
        Actor missing = actor("ACTIVE", null, false, false, true);
        Actor foreign = actorWithForeignMembership();
        Actor platformOnly = actor("ACTIVE", null, false, true, true);

        for (Actor actor : List.of(inactive, missing, foreign, platformOnly)) {
            assertActiveBusinessRequired(list(actor, "2026-12-24", "2026-12-24"));
            assertActiveBusinessRequired(getOne(actor, MISSING_ID));
            assertActiveBusinessRequired(create(actor, closureJson("2026-12-24", "2026-12-24")));
            assertActiveBusinessRequired(replace(
                    actor, MISSING_ID, replaceJson(0, "2026-12-24", "2026-12-24", true, "")));
            assertActiveBusinessRequired(deleteOne(actor, MISSING_ID, 0));
        }
        assertThat(exceptionCount(platformOnly.businessId())).isZero();

        Actor platformOwner = actor("ACTIVE", "BUSINESS_OWNER", true, true, true);
        create(platformOwner, closureJson("2026-12-24", "2026-12-24"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ACTIVE"})
    void draftAndActiveBusinessesAllowReadsAndMutations(String businessStatus) throws Exception {
        Actor owner = actor(businessStatus, "BUSINESS_OWNER", true, false, true);

        UUID id = idOf(create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        replace(owner, id, replaceJson(0, "2026-12-24", "2026-12-25", true, ""))
                .andExpect(status().isOk());
        deleteOne(owner, id, 1).andExpect(status().isNoContent());
        list(owner, "2026-12-24", "2026-12-24").andExpect(status().isOk());
    }

    @Test
    void suspendedBusinessAllowsReadsAndRejectsEveryMutationUnchanged() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID id = idOf(create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andReturn().getResponse().getContentAsString());
        jdbc.sql("UPDATE business SET status='SUSPENDED' WHERE id=:id")
                .param("id", owner.businessId())
                .update();

        list(owner, "2026-12-24", "2026-12-24").andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions.length()").value(1));
        getOne(owner, id).andExpect(status().isOk());
        assertBusinessSuspended(create(owner, closureJson("2026-12-30", "2026-12-30")));
        assertBusinessSuspended(replace(
                owner, id, replaceJson(0, "2026-12-24", "2026-12-25", true, "")));
        assertBusinessSuspended(deleteOne(owner, id, 0));
        assertThat(storedVersion(id)).isZero();
        assertThat(exceptionCount(owner.businessId())).isEqualTo(1);
    }

    @Test
    void inactiveStaffMemberIsReadableButItsExceptionsAreImmutable() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);
        UUID id = idOf(create(owner, staffJson(
                        "STAFF_TIME_OFF", staffMemberId, "2026-12-24", "2026-12-24", true, ""))
                .andReturn().getResponse().getContentAsString());
        jdbc.sql("UPDATE staff_member SET active=false WHERE id=:id")
                .param("id", staffMemberId)
                .update();

        getOne(owner, id).andExpect(status().isOk());
        list(owner, "2026-12-24", "2026-12-24").andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions.length()").value(1));
        assertStaffMemberInactive(create(owner, staffJson(
                "STAFF_TIME_OFF", staffMemberId, "2026-12-30", "2026-12-30", true, "")));
        assertStaffMemberInactive(replace(
                owner, id, replaceJson(0, "2026-12-24", "2026-12-25", true, "")));
        assertStaffMemberInactive(deleteOne(owner, id, 0));
        assertThat(storedVersion(id)).isZero();

        create(owner, closureJson("2026-12-24", "2026-12-24")).andExpect(status().isCreated());
    }

    @Test
    void missingAndCrossBusinessStaffMembersAndExceptionsShareSafeNotFound() throws Exception {
        Actor first = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        Actor second = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffInFirst = staffMember(first.businessId(), true);
        UUID inFirst = idOf(create(first, staffJson(
                        "STAFF_TIME_OFF", staffInFirst, "2026-12-24", "2026-12-24", true, ""))
                .andReturn().getResponse().getContentAsString());

        for (UUID staffId : List.of(staffInFirst, MISSING_ID)) {
            assertStaffMemberNotFound(create(second, staffJson(
                    "STAFF_TIME_OFF", staffId, "2026-12-24", "2026-12-24", true, "")));
        }
        for (UUID id : List.of(inFirst, MISSING_ID)) {
            assertExceptionNotFound(getOne(second, id));
            assertExceptionNotFound(replace(
                    second, id, replaceJson(0, "2026-12-24", "2026-12-25", true, "")));
            assertExceptionNotFound(deleteOne(second, id, 0));
        }
        list(second, "2026-12-24", "2026-12-24").andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions").isEmpty());
        assertThat(storedVersion(inFirst)).isZero();
        assertThat(exceptionCount(second.businessId())).isZero();
    }

    @Test
    void clientSuppliedBusinessIdentityIsIgnoredAndTheSessionBusinessOwnsTheRecord()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        Actor other = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);

        create(owner, """
                {"kind":"BUSINESS_CLOSURE","businessId":"%s","firstDate":"2026-12-24",
                 "lastDate":"2026-12-24","allDay":true,"periods":[]}
                """.formatted(other.businessId()))
                .andExpect(status().isCreated());

        assertThat(exceptionCount(owner.businessId())).isEqualTo(1);
        assertThat(exceptionCount(other.businessId())).isZero();
    }

    @Test
    void theScheduleRevisionAdvancesOncePerAcceptedRequestIsNeverExposedAndAMissingRowIsASafeError()
            throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        assertThat(revisionOf(owner.businessId())).isZero();

        String created = create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", aMapWithSize(11)))
                .andReturn().getResponse().getContentAsString();
        UUID id = idOf(created);
        assertThat(revisionOf(owner.businessId())).isEqualTo(1L);

        String replaced = replace(owner, id, replaceJson(0, "2026-12-24", "2026-12-25", true, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(11)))
                .andReturn().getResponse().getContentAsString();
        assertThat(revisionOf(owner.businessId())).isEqualTo(2L);
        assertThat(created).doesNotContainIgnoringCase("revision");
        assertThat(replaced).doesNotContainIgnoringCase("revision");

        // Rejected requests leave the revision where it was.
        assertConcurrentUpdate(replace(owner, id, replaceJson(0, "2026-12-24", "2026-12-24", true, "")));
        assertConcurrentUpdate(deleteOne(owner, id, 0));
        assertExceptionNotFound(deleteOne(owner, MISSING_ID, 0));
        assertValidationError(create(owner, closureJson("2026-12-26", "2026-12-24")));
        assertThat(revisionOf(owner.businessId())).isEqualTo(2L);

        deleteOne(owner, id, 1).andExpect(status().isNoContent());
        assertThat(revisionOf(owner.businessId())).isEqualTo(3L);

        // A Business without a revision row is a sanitized internal error that writes nothing.
        jdbc.sql("DELETE FROM business_schedule_revision WHERE business_id = :id")
                .param("id", owner.businessId())
                .update();
        create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("business_schedule_revision"))))
                .andExpect(content().string(not(containsString(owner.businessId().toString()))));
        assertThat(exceptionCount(owner.businessId())).isZero();
    }

    private long revisionOf(UUID businessId) {
        return jdbc.sql("SELECT revision FROM business_schedule_revision WHERE business_id = :id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    @Test
    void sameKindOverlapIsAContainedSafeConflictAndCrossKindOverlapIsAllowed() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);
        create(owner, closureJson("2026-12-24", "2026-12-26")).andExpect(status().isCreated());
        create(owner, staffJson(
                        "STAFF_TIME_OFF", staffMemberId, "2026-12-24", "2026-12-26", true, ""))
                .andExpect(status().isCreated());

        String body = create(owner, closureJson("2026-12-26", "2026-12-27"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_EXCEPTION_OVERLAP"))
                .andExpect(jsonPath("$.detail")
                        .value("Вече има изключение от същия вид за тези дати."))
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("schedule_exception")
                .doesNotContain("constraint")
                .doesNotContain("violates")
                .doesNotContain(owner.businessId().toString());
        assertThat(exceptionCount(owner.businessId())).isEqualTo(2);
    }

    @Test
    void replaceCannotChangeKindOrStaffMemberBecauseTheStoredValuesAreRetained() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);
        UUID otherStaffId = staffMember(owner.businessId(), true);
        UUID id = idOf(create(owner, staffJson(
                        "STAFF_TIME_OFF", staffMemberId, "2026-12-24", "2026-12-24", true, ""))
                .andReturn().getResponse().getContentAsString());

        replace(owner, id, """
                {"expectedVersion":0,"kind":"BUSINESS_CLOSURE","staffMemberId":"%s",
                 "firstDate":"2026-12-25","lastDate":"2026-12-25","allDay":true,"periods":[]}
                """.formatted(otherStaffId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("STAFF_TIME_OFF"))
                .andExpect(jsonPath("$.staffMemberId").value(staffMemberId.toString()))
                .andExpect(jsonPath("$.firstDate").value("2026-12-25"));

        assertThat(jdbc.sql("SELECT kind FROM schedule_exception WHERE id=:id")
                        .param("id", id).query(String.class).single())
                .isEqualTo("STAFF_TIME_OFF");
        assertThat(jdbc.sql("SELECT staff_member_id FROM schedule_exception WHERE id=:id")
                        .param("id", id).query(UUID.class).single())
                .isEqualTo(staffMemberId);
    }

    @Test
    void replacingAWorkingKindWithAFullDayShapeIsRejectedAgainstTheStoredKind() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);
        UUID id = idOf(create(owner, staffJson(
                        "WORKING_DAY_OVERRIDE", staffMemberId, "2026-12-24", "2026-12-24", false, ""))
                .andReturn().getResponse().getContentAsString());

        assertFieldError(replace(
                owner, id, replaceJson(0, "2026-12-24", "2026-12-24", true, "")), "allDay");
        assertThat(storedVersion(id)).isZero();
    }

    @Test
    void staleVersionsAreConcurrentUpdatesAndMissingIdsAreNotFound() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID id = idOf(create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andReturn().getResponse().getContentAsString());
        replace(owner, id, replaceJson(0, "2026-12-24", "2026-12-25", true, ""))
                .andExpect(status().isOk());

        assertConcurrentUpdate(replace(
                owner, id, replaceJson(0, "2026-12-24", "2026-12-24", true, "")));
        assertConcurrentUpdate(replace(
                owner, id, replaceJson(2, "2026-12-24", "2026-12-24", true, "")));
        assertConcurrentUpdate(deleteOne(owner, id, 0));
        assertConcurrentUpdate(deleteOne(owner, id, 2));
        assertThat(storedVersion(id)).isEqualTo(1);
        assertExceptionNotFound(getOne(owner, MISSING_ID));
        assertExceptionNotFound(deleteOne(owner, MISSING_ID, 0));
    }

    @Test
    void validationBoundariesAcceptTheExactLimitAndRejectTheFirstOutsideValue() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);

        create(owner, closureJson("2000-01-01", "2000-01-01")).andExpect(status().isCreated());
        create(owner, closureJson("2100-12-31", "2100-12-31")).andExpect(status().isCreated());
        assertFieldError(create(owner, closureJson("1999-12-31", "2000-01-01")), "firstDate");
        assertFieldError(create(owner, closureJson("2100-12-31", "2101-01-01")), "lastDate");
        assertFieldError(create(owner, closureJson("2101-01-01", "2101-01-01")), "firstDate");

        create(owner, staffJson(
                        "STAFF_TIME_OFF", staffMemberId, "2027-01-01", "2028-01-01", true, ""))
                .andExpect(status().isCreated());
        assertFieldError(create(owner, staffJson(
                        "STAFF_TIME_OFF", staffMemberId, "2030-01-01", "2031-01-02", true, "")),
                "lastDate");

        create(owner, staffJson(
                        "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24",
                        false, periods(24)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periods.length()").value(24));
        assertFieldError(create(owner, staffJson(
                        "WORKING_DAY_OVERRIDE", staffMemberId, "2026-12-24", "2026-12-24",
                        false, periods(25))),
                "periods");
    }

    @Test
    void listWindowBoundariesAndRequiredParameters() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);

        list(owner, "2026-01-01", "2026-04-03").andExpect(status().isOk());
        assertValidationError(list(owner, "2026-01-01", "2026-04-04"));
        assertValidationError(list(owner, "2026-01-02", "2026-01-01"));
        assertValidationError(list(owner, "1999-12-31", "2000-01-02"));
        assertValidationError(list(owner, "2100-12-31", "2101-01-01"));
        assertValidationError(mvc.perform(get(BASE).cookie(owner.session())));
        assertValidationError(mvc.perform(get(BASE).cookie(owner.session())
                .param("from", "2026-01-01")));
        assertValidationError(mvc.perform(get(BASE).cookie(owner.session())
                .param("to", "2026-01-01")));
        assertValidationError(list(owner, "2026-1-1", "2026-01-02"));
        assertValidationError(list(owner, "2026-01-01", "2026-13-01"));
    }

    @Test
    void invalidBodiesAreRejectedWithNamedFieldsAndWriteNothing() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);

        assertFieldError(create(owner, """
                {"firstDate":"2026-12-24","lastDate":"2026-12-24","allDay":true,"periods":[]}
                """), "kind");
        assertFieldError(create(owner, staffJson(
                "STAFF_TIME_OFF", null, "2026-12-24", "2026-12-24", true, "")), "staffMemberId");
        assertFieldError(create(owner, """
                {"kind":"BUSINESS_CLOSURE","staffMemberId":"%s","firstDate":"2026-12-24",
                 "lastDate":"2026-12-24","allDay":true,"periods":[]}
                """.formatted(staffMemberId)), "staffMemberId");
        assertFieldError(create(owner, """
                {"kind":"BUSINESS_CLOSURE","lastDate":"2026-12-24","allDay":true,"periods":[]}
                """), "firstDate");
        assertFieldError(create(owner, """
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","allDay":true,"periods":[]}
                """), "lastDate");
        assertFieldError(create(owner, closureJson("2026-12-25", "2026-12-24")), "lastDate");
        assertFieldError(create(owner, """
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "periods":[]}
                """), "allDay");
        assertFieldError(create(owner, staffJson(
                "WORKING_DAY_OVERRIDE", staffMemberId, "2026-12-24", "2026-12-24", true, "")),
                "allDay");
        assertFieldError(create(owner, """
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":true}
                """), "periods");
        assertFieldError(create(owner, """
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":true,"periods":[{"startTime":"09:00","endTime":"10:00"}]}
                """), "periods");
        assertFieldError(create(owner, """
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","lastDate":"2026-12-25",
                 "allDay":false,"periods":[{"startTime":"09:00","endTime":"10:00"}]}
                """), "lastDate");
        assertFieldError(create(owner, staffJson(
                "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24", false, "")),
                "periods");
        for (String periodsJson : List.of(
                """
                {"startTime":"09:00","endTime":"12:00"},{"startTime":"09:00","endTime":"12:00"}
                """,
                """
                {"startTime":"09:00","endTime":"12:00"},{"startTime":"11:59","endTime":"13:00"}
                """,
                """
                {"startTime":"12:00","endTime":"12:00"}
                """,
                """
                {"startTime":"13:00","endTime":"12:00"}
                """,
                """
                {"startTime":"09:00"}
                """,
                "null")) {
            assertFieldError(create(owner, staffJson(
                    "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24",
                    false, periodsJson)), "periods");
        }
        assertThat(exceptionCount(owner.businessId())).isZero();

        UUID id = idOf(create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andReturn().getResponse().getContentAsString());
        assertValidationError(replace(owner, id, """
                {"firstDate":"2026-12-24","lastDate":"2026-12-24","allDay":true,"periods":[]}
                """));
        assertValidationError(replace(owner, id, replaceJson(-1, "2026-12-24", "2026-12-24", true, "")));
        assertValidationError(mvc.perform(delete(BASE + "/{id}", id)
                .with(csrf()).cookie(owner.session())));
        assertValidationError(mvc.perform(delete(BASE + "/{id}", id)
                .with(csrf()).cookie(owner.session()).param("expectedVersion", "-1")));
        assertValidationError(mvc.perform(delete(BASE + "/{id}", id)
                .with(csrf()).cookie(owner.session()).param("expectedVersion", "x")));
        assertThat(storedVersion(id)).isZero();
    }

    @Test
    void malformedJsonAndNonCanonicalDatesAndTimesUseTheSafeValidationProblem() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);

        assertValidationError(create(owner, "{"));
        assertValidationError(create(owner, ""));
        assertValidationError(create(owner, """
                {"kind":"HOLIDAY","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":true,"periods":[]}
                """));
        assertValidationError(create(owner, """
                {"kind":"STAFF_TIME_OFF","staffMemberId":"not-a-uuid","firstDate":"2026-12-24",
                 "lastDate":"2026-12-24","allDay":true,"periods":[]}
                """));
        for (String date : List.of(
                "\"2026-1-24\"", "\"2026-12-24T00:00\"", "\"2026-02-30\"", "\"+12026-12-24\"",
                "\"20261224\"", "\" 2026-12-24\"", "\"24.12.2026\"", "20261224", "[2026,12,24]",
                "true", "{}")) {
            assertValidationError(create(owner, """
                    {"kind":"BUSINESS_CLOSURE","firstDate":%s,"lastDate":"2026-12-24",
                     "allDay":true,"periods":[]}
                    """.formatted(date)));
        }
        for (String time : List.of(
                "\"9:00\"", "\"09:00:00\"", "\"24:00\"", "\"09:60\"", "\" 09:00\"", "\"0900\"",
                "900", "[9,0]")) {
            assertValidationError(create(owner, staffJson(
                    "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24", false,
                    "{\"startTime\":%s,\"endTime\":\"23:59\"}".formatted(time))));
            assertValidationError(create(owner, staffJson(
                    "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24", false,
                    "{\"startTime\":\"00:00\",\"endTime\":%s}".formatted(time))));
        }
        create(owner, staffJson(
                        "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24", false,
                        """
                        {"startTime":"23:58","endTime":"23:59"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periods[0].startTime").value("23:58"))
                .andExpect(jsonPath("$.periods[0].endTime").value("23:59"));
        assertThat(exceptionCount(owner.businessId())).isEqualTo(1);
    }

    @Test
    void adjacentPeriodsAreAcceptedAndStaySeparate() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID staffMemberId = staffMember(owner.businessId(), true);

        UUID id = idOf(create(owner, staffJson(
                        "ADDITIONAL_WORKING_PERIODS", staffMemberId, "2026-12-24", "2026-12-24", false,
                        """
                        {"startTime":"09:00","endTime":"12:00"},
                        {"startTime":"12:00","endTime":"13:00"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periods.length()").value(2))
                .andReturn().getResponse().getContentAsString());

        assertThat(periodCount(id)).isEqualTo(2);
    }

    @Test
    void csrfProtectsPostPutAndDelete() throws Exception {
        Actor owner = actor("ACTIVE", "BUSINESS_OWNER", true, false, true);
        UUID id = idOf(create(owner, closureJson("2026-12-24", "2026-12-24"))
                .andReturn().getResponse().getContentAsString());

        assertAccessDenied(mvc.perform(post(BASE)
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(closureJson("2026-12-30", "2026-12-30"))));
        assertAccessDenied(mvc.perform(put(BASE + "/{id}", id)
                .cookie(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceJson(0, "2026-12-24", "2026-12-25", true, ""))));
        assertAccessDenied(mvc.perform(delete(BASE + "/{id}", id)
                .cookie(owner.session())
                .param("expectedVersion", "0")));

        assertThat(storedVersion(id)).isZero();
        assertThat(exceptionCount(owner.businessId())).isEqualTo(1);
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
            jdbc.sql("""
                            INSERT INTO platform_role(user_id,role,created_at)
                            VALUES (:user,'PLATFORM_ADMIN',:now)
                            """)
                    .param("user", userId)
                    .param("now", now)
                    .update();
        }
        return new Actor(businessId, userId, session(userId, selectBusiness ? businessId : null));
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
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (
                            :id,:slug,'Schedule Exception Business','OTHER',:status,'Europe/Sofia',
                            :now,:now)
                        """)
                .param("id", id)
                .param("slug", "api-schedule-exception-" + SEQUENCE.incrementAndGet())
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

    private Cookie session(UUID userId, UUID selectedBusinessId) {
        String rawToken = "schedule-exception-session-" + UUID.randomUUID();
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

    private UUID staffMember(UUID businessId, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,contact_email,contact_phone,
                            active,version,created_at,updated_at)
                        VALUES (:id,:business,'Schedule Staff',NULL,NULL,:active,0,:now,:now)
                        """)
                .param("id", id)
                .param("business", businessId)
                .param("active", active)
                .param("now", now)
                .update();
        return id;
    }

    private ResultActions list(Actor actor, String from, String to) throws Exception {
        return mvc.perform(get(BASE)
                .cookie(actor.session())
                .param("from", from)
                .param("to", to));
    }

    private ResultActions getOne(Actor actor, UUID id) throws Exception {
        return mvc.perform(get(BASE + "/{id}", id).cookie(actor.session()));
    }

    private ResultActions create(Actor actor, String body) throws Exception {
        return mvc.perform(post(BASE)
                .with(csrf())
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions replace(Actor actor, UUID id, String body) throws Exception {
        return mvc.perform(put(BASE + "/{id}", id)
                .with(csrf())
                .cookie(actor.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions deleteOne(Actor actor, UUID id, long expectedVersion) throws Exception {
        return mvc.perform(delete(BASE + "/{id}", id)
                .with(csrf())
                .cookie(actor.session())
                .param("expectedVersion", Long.toString(expectedVersion)));
    }

    private static String closureJson(String first, String last) {
        return """
                {"kind":"BUSINESS_CLOSURE","firstDate":"%s","lastDate":"%s",
                 "allDay":true,"periods":[]}
                """.formatted(first, last);
    }

    private static String staffJson(
            String kind, UUID staffMemberId, String first, String last, boolean allDay,
            String periodsJson) {
        String staff = staffMemberId == null ? "" : "\"staffMemberId\":\"" + staffMemberId + "\",";
        return """
                {"kind":"%s",%s"firstDate":"%s","lastDate":"%s","allDay":%s,"periods":[%s]}
                """.formatted(kind, staff, first, last, allDay, periodsJson);
    }

    private static String replaceJson(
            long expectedVersion, String first, String last, boolean allDay, String periodsJson) {
        return """
                {"expectedVersion":%d,"firstDate":"%s","lastDate":"%s","allDay":%s,
                 "periods":[%s]}
                """.formatted(expectedVersion, first, last, allDay, periodsJson);
    }

    private static String periods(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> "{\"startTime\":\"%02d:00\",\"endTime\":\"%02d:30\"}"
                        .formatted(index % 24, index % 24))
                .limit(24)
                .collect(Collectors.joining(",", "", count > 24
                        ? ",{\"startTime\":\"23:40\",\"endTime\":\"23:59\"}"
                        : ""));
    }

    private static UUID idOf(String responseBody) {
        int start = responseBody.indexOf("\"id\":\"") + 6;
        return UUID.fromString(responseBody.substring(start, start + 36));
    }

    private long storedVersion(UUID id) {
        return jdbc.sql("SELECT version FROM schedule_exception WHERE id=:id")
                .param("id", id).query(Long.class).single();
    }

    private long exceptionCount(UUID businessId) {
        return jdbc.sql("SELECT COUNT(*) FROM schedule_exception WHERE business_id=:id")
                .param("id", businessId).query(Long.class).single();
    }

    private long periodCount(UUID exceptionId) {
        return jdbc.sql("SELECT COUNT(*) FROM schedule_exception_period WHERE exception_id=:id")
                .param("id", exceptionId).query(Long.class).single();
    }

    private void assertValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."));
    }

    private void assertFieldError(ResultActions result, String field) throws Exception {
        assertValidationError(result);
        result.andExpect(jsonPath("$.fieldErrors." + field).isString())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1));
    }

    private void assertActiveBusinessRequired(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTIVE_BUSINESS_REQUIRED"))
                .andExpect(jsonPath("$.detail").value("Изберете бизнес, за да продължите."));
    }

    private void assertAccessDenied(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.detail").value("Нямате достъп до тази операция."));
    }

    private void assertStaffMemberNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STAFF_MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Членът на екипа не е намерен."));
    }

    private void assertExceptionNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_EXCEPTION_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Изключението от графика не е намерено."));
    }

    private void assertStaffMemberInactive(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_MEMBER_INACTIVE"));
    }

    private void assertConcurrentUpdate(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_EXCEPTION_CONCURRENT_UPDATE"));
    }

    private void assertBusinessSuspended(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_SUSPENDED"))
                .andExpect(jsonPath("$.detail")
                        .value("Спрян бизнес може само да преглежда данните си."));
    }

    private record Actor(UUID businessId, UUID userId, Cookie session) {
    }
}
