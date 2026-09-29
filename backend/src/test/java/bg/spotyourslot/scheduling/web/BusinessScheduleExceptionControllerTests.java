package bg.spotyourslot.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionAdministrationDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionWindow;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.shared.web.ApiExceptionHandler;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.CurrentSecurityContextArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class BusinessScheduleExceptionControllerTests {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000b01");
    private static final UUID BUSINESS_ID = UUID.fromString("00000000-0000-0000-0000-000000000b02");
    private static final UUID EXCEPTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000b03");
    private static final UUID STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000b04");
    private static final Instant CREATED = Instant.parse("2026-09-29T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-09-29T09:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);
    private static final String BASE = "/api/business/schedule-exceptions";

    @Mock ScheduleExceptionAdministration exceptions;
    private AuthenticatedBusinessContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var authentication = new TestAuthentication(USER_ID, BUSINESS_ID);
        context = authentication;
        SecurityContextHolder.getContext().setAuthentication(authentication);
        mvc = MockMvcBuilders.standaloneSetup(new BusinessScheduleExceptionController(exceptions))
                .setCustomArgumentResolvers(new CurrentSecurityContextArgumentResolver())
                .setControllerAdvice(
                        new BusinessScheduleExceptionExceptionHandler(), new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReturns201WithLocationAndMapsTheApprovedRequestAndResponse() throws Exception {
        when(exceptions.create(context, new CreateScheduleExceptionCommand(
                        ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS,
                        STAFF_MEMBER_ID,
                        DATE,
                        DATE,
                        false,
                        List.of(new ExceptionPeriod(LocalTime.of(9, 0), LocalTime.of(12, 30))))))
                .thenReturn(result(ScheduleExceptionKind.ADDITIONAL_WORKING_PERIODS, false));

        mvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "ADDITIONAL_WORKING_PERIODS",
                                  "staffMemberId": "%s",
                                  "firstDate": "2026-12-24",
                                  "lastDate": "2026-12-24",
                                  "allDay": false,
                                  "periods": [{"startTime":"09:00","endTime":"12:30"}]
                                }
                                """.formatted(STAFF_MEMBER_ID)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE + "/" + EXCEPTION_ID))
                .andExpect(jsonPath("$.id").value(EXCEPTION_ID.toString()))
                .andExpect(jsonPath("$.kind").value("ADDITIONAL_WORKING_PERIODS"))
                .andExpect(jsonPath("$.staffMemberId").value(STAFF_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.firstDate").value("2026-12-24"))
                .andExpect(jsonPath("$.lastDate").value("2026-12-24"))
                .andExpect(jsonPath("$.allDay").value(false))
                .andExpect(jsonPath("$.periods[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.periods[0].endTime").value("12:30"))
                .andExpect(jsonPath("$.timezone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdAt").value("2026-09-29T08:00:00Z"))
                .andExpect(jsonPath("$.businessId").doesNotExist());
    }

    @Test
    void closureResponseSerializesAnExplicitNullStaffMember() throws Exception {
        when(exceptions.get(context, EXCEPTION_ID))
                .thenReturn(result(ScheduleExceptionKind.BUSINESS_CLOSURE, true));

        mvc.perform(get(BASE + "/{id}", EXCEPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffMemberId").value((Object) null))
                .andExpect(jsonPath("$.allDay").value(true))
                .andExpect(jsonPath("$.periods").isEmpty());
    }

    @Test
    void replaceCarriesOnlyEditableFieldsAndIgnoresKindAndStaffMemberInTheBody() throws Exception {
        when(exceptions.replace(
                        context,
                        EXCEPTION_ID,
                        new ReplaceScheduleExceptionCommand(
                                3L, DATE, DATE.plusDays(2), true, List.of())))
                .thenReturn(result(ScheduleExceptionKind.STAFF_TIME_OFF, true));

        mvc.perform(put(BASE + "/{id}", EXCEPTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 3,
                                  "kind": "BUSINESS_CLOSURE",
                                  "staffMemberId": "%s",
                                  "firstDate": "2026-12-24",
                                  "lastDate": "2026-12-26",
                                  "allDay": true,
                                  "periods": []
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("STAFF_TIME_OFF"));

        verify(exceptions).replace(
                context,
                EXCEPTION_ID,
                new ReplaceScheduleExceptionCommand(3L, DATE, DATE.plusDays(2), true, List.of()));
        assertNoKindOrStaffMemberComponent(
                BusinessScheduleExceptionHttpRecords.ReplaceScheduleExceptionRequest.class);
        assertNoKindOrStaffMemberComponent(ReplaceScheduleExceptionCommand.class);
    }

    @Test
    void deleteReturns204WithoutABodyAndTakesTheVersionFromTheQuery() throws Exception {
        mvc.perform(delete(BASE + "/{id}", EXCEPTION_ID).param("expectedVersion", "4"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(exceptions).delete(context, EXCEPTION_ID, 4L);
    }

    @Test
    void deleteWithoutOrWithMalformedVersionIsSafelyRejected() throws Exception {
        mvc.perform(delete(BASE + "/{id}", EXCEPTION_ID).param("expectedVersion", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(exceptions);
    }

    @Test
    void listMapsTheWindowAndReturnsTheTimezoneOnceOnTheWrapper() throws Exception {
        ScheduleExceptionDetails item = result(ScheduleExceptionKind.STAFF_TIME_OFF, true).exception();
        when(exceptions.list(context, DATE, DATE.plusDays(5)))
                .thenReturn(new ScheduleExceptionWindow(
                        DATE, DATE.plusDays(5), ZoneId.of("Europe/Sofia"), List.of(item)));

        mvc.perform(get(BASE).param("from", "2026-12-24").param("to", "2026-12-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-12-24"))
                .andExpect(jsonPath("$.to").value("2026-12-29"))
                .andExpect(jsonPath("$.timezone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.exceptions[0].id").value(EXCEPTION_ID.toString()))
                .andExpect(jsonPath("$.exceptions[0].timezone").doesNotExist())
                .andExpect(jsonPath("$.exceptions[0].businessId").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-1-1", "2026-12-24T00:00", "2026-02-30", "+12026-12-24", "20261224", " 2026-12-24",
        "2026-12-24 ", "abc", ""
    })
    void malformedListDatesAreRejectedBeforeTheService(String value) throws Exception {
        mvc.perform(get(BASE).param("from", value).param("to", "2026-12-29"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
        verifyNoInteractions(exceptions);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-1-24", "2026-12-24T00:00", "2026-02-30", "+12026-12-24", "20261224", " 2026-12-24",
        "24.12.2026", "2026-12-24Z"
    })
    void nonCanonicalDatesInABodyAreRejectedBeforeTheService(String value) throws Exception {
        postBody("""
                {"kind":"BUSINESS_CLOSURE","firstDate":"%s","lastDate":"2026-12-24",
                 "allDay":true,"periods":[]}
                """.formatted(value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        putBody("""
                {"expectedVersion":0,"firstDate":"2026-12-24","lastDate":"%s",
                 "allDay":true,"periods":[]}
                """.formatted(value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(exceptions);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "9:00", "09:00:00", "24:00", "09:60", " 09:00", "09:00 ", "0900", "9.00", "09:0"
    })
    void nonCanonicalTimesAreRejectedInEitherPositionBeforeTheService(String value)
            throws Exception {
        postBody("""
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":false,"periods":[{"startTime":"%s","endTime":"23:59"}]}
                """.formatted(value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        putBody("""
                {"expectedVersion":0,"firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":false,"periods":[{"startTime":"00:00","endTime":"%s"}]}
                """.formatted(value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(exceptions);
    }

    @Test
    void latestRepresentableTimeAndEarliestBoundariesAreAcceptedAsCanonical() throws Exception {
        when(exceptions.create(context, new CreateScheduleExceptionCommand(
                        ScheduleExceptionKind.BUSINESS_CLOSURE,
                        null,
                        DATE,
                        DATE,
                        false,
                        List.of(new ExceptionPeriod(LocalTime.of(0, 0), LocalTime.of(23, 59))))))
                .thenReturn(result(ScheduleExceptionKind.BUSINESS_CLOSURE, false));

        postBody("""
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":false,"periods":[{"startTime":"00:00","endTime":"23:59"}]}
                """)
                .andExpect(status().isCreated());
    }

    @Test
    void malformedJsonMissingBodyUnknownKindAndBadUuidsUseTheSafeValidationProblem()
            throws Exception {
        postBody("{").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        postBody("""
                {"kind":"HOLIDAY","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":true,"periods":[]}
                """).andExpect(status().isBadRequest());
        postBody("""
                {"kind":"STAFF_TIME_OFF","staffMemberId":"not-a-uuid","firstDate":"2026-12-24",
                 "lastDate":"2026-12-24","allDay":true,"periods":[]}
                """).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/{id}", "not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/{id}", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(exceptions);
    }

    @Test
    void unknownRequestPropertiesFollowTheEstablishedPermissivePolicy() throws Exception {
        when(exceptions.create(context, new CreateScheduleExceptionCommand(
                        ScheduleExceptionKind.BUSINESS_CLOSURE, null, DATE, DATE, true, List.of())))
                .thenReturn(result(ScheduleExceptionKind.BUSINESS_CLOSURE, true));

        postBody("""
                {"kind":"BUSINESS_CLOSURE","firstDate":"2026-12-24","lastDate":"2026-12-24",
                 "allDay":true,"periods":[],"businessId":"%s","surprise":1}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isCreated());
    }

    private static void assertNoKindOrStaffMemberComponent(Class<?> type) {
        assertThat(Arrays.stream(type.getRecordComponents())
                        .map(component -> component.getName()))
                .doesNotContain("kind", "staffMemberId", "businessId");
    }

    private ResultActions postBody(String body) throws Exception {
        return mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putBody(String body) throws Exception {
        return mvc.perform(put(BASE + "/{id}", EXCEPTION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static ScheduleExceptionAdministrationDetails result(
            ScheduleExceptionKind kind, boolean allDay) {
        boolean staff = kind.isStaffScoped();
        return new ScheduleExceptionAdministrationDetails(
                new ScheduleExceptionDetails(
                        EXCEPTION_ID,
                        kind,
                        staff ? STAFF_MEMBER_ID : null,
                        DATE,
                        DATE,
                        allDay,
                        allDay
                                ? List.of()
                                : List.of(new ExceptionPeriod(
                                        LocalTime.of(9, 0), LocalTime.of(12, 30))),
                        0,
                        CREATED,
                        UPDATED),
                ZoneId.of("Europe/Sofia"));
    }

    private record TestAuthentication(UUID userId, UUID businessId)
            implements Authentication, AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(businessId);
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getDetails() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return userId;
        }

        @Override
        public boolean isAuthenticated() {
            return true;
        }

        @Override
        public void setAuthenticated(boolean authenticated) {
            if (!authenticated) {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public String getName() {
            return userId.toString();
        }
    }
}
