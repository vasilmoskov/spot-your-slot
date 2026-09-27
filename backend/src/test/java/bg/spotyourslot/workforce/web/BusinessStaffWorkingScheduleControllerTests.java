package bg.spotyourslot.workforce.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.shared.web.ApiExceptionHandler;
import bg.spotyourslot.workforce.StaffWorkingScheduleAdministration;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InputField;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleAdministrationDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingSchedulePersistenceException.UnexpectedFailure;
import bg.spotyourslot.workforce.web.BusinessStaffWorkingScheduleHttpRecords.ReplaceWorkingScheduleRequest;
import bg.spotyourslot.workforce.web.BusinessStaffWorkingScheduleHttpRecords.WorkingPeriodRequest;
import bg.spotyourslot.workforce.web.BusinessStaffWorkingScheduleHttpRecords.WorkingPeriodResponse;
import bg.spotyourslot.workforce.web.BusinessStaffWorkingScheduleHttpRecords.WorkingScheduleResponse;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class BusinessStaffWorkingScheduleControllerTests {
    private static final UUID STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000155");
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000177");
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000188");
    private static final Instant CREATED = Instant.parse("2026-09-22T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-09-22T09:00:00Z");

    @Mock StaffWorkingScheduleAdministration schedules;
    private AuthenticatedBusinessContext context;
    private BusinessStaffWorkingScheduleController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var authentication = new TestAuthentication(USER_ID, BUSINESS_ID);
        context = authentication;
        SecurityContextHolder.getContext().setAuthentication(authentication);
        controller = new BusinessStaffWorkingScheduleController(schedules);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentSecurityContextArgumentResolver())
                .setControllerAdvice(
                        new BusinessStaffWorkingScheduleExceptionHandler(),
                        new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bothRoutesUseSecurityContextAndMapApprovedContracts() throws Exception {
        StaffWorkingScheduleAdministrationDetails details = details();
        when(schedules.get(context, STAFF_MEMBER_ID)).thenReturn(details);
        when(schedules.replace(
                        context,
                        STAFF_MEMBER_ID,
                        new ReplaceWorkingPeriodsCommand(
                                List.of(new WorkingPeriod(
                                        DayOfWeek.MONDAY,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(17, 0))),
                                3L)))
                .thenReturn(details);

        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffMemberId").value(STAFF_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.timezone").value("Europe/Sofia"))
                .andExpect(jsonPath("$.periods[0].weekday").value("MONDAY"))
                .andExpect(jsonPath("$.periods[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.periods[0].endTime").value("17:00"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.businessId").doesNotExist());
        mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 3,
                                  "periods": [
                                    {"weekday":"MONDAY","startTime":"09:00","endTime":"17:00"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods[0].startTime").value("09:00"));

        verify(schedules).get(context, STAFF_MEMBER_ID);
        verify(schedules).replace(
                context,
                STAFF_MEMBER_ID,
                new ReplaceWorkingPeriodsCommand(
                        List.of(new WorkingPeriod(
                                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))),
                        3L));
    }

    @Test
    void nullExpectedVersionAndNullPeriodsReachTheDelegateUnaltered() throws Exception {
        when(schedules.replace(
                        context, STAFF_MEMBER_ID, new ReplaceWorkingPeriodsCommand(null, null)))
                .thenThrow(new InvalidInput(InputField.PERIODS));

        mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(schedules).replace(
                context, STAFF_MEMBER_ID, new ReplaceWorkingPeriodsCommand(null, null));
    }

    @Test
    void requestAndResponsePeriodsAreDefensivelyImmutable() {
        var sourcePeriods = new ArrayList<WorkingPeriodRequest>();
        sourcePeriods.add(new WorkingPeriodRequest(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        sourcePeriods.add(null);
        var request = new ReplaceWorkingScheduleRequest(2L, sourcePeriods);
        sourcePeriods.clear();

        assertThat(request.periods()).hasSize(2);
        assertThatThrownByUnsupported(() -> request.periods().add(null));

        var sourceResponses = new ArrayList<>(List.of(new WorkingPeriodResponse(
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        var response = new WorkingScheduleResponse(
                STAFF_MEMBER_ID,
                ZoneId.of("Europe/Sofia"),
                sourceResponses,
                2,
                CREATED,
                UPDATED);
        sourceResponses.clear();

        assertThat(response.periods()).hasSize(1);
        assertThatThrownByUnsupported(() -> response.periods().clear());
    }

    @Test
    void everyHttpRecordContainsOnlyTheApprovedFields() {
        assertComponents(WorkingPeriodRequest.class, "weekday", "startTime", "endTime");
        assertComponents(
                ReplaceWorkingScheduleRequest.class, "expectedVersion", "periods");
        assertComponents(WorkingPeriodResponse.class, "weekday", "startTime", "endTime");
        assertComponents(WorkingScheduleResponse.class,
                "staffMemberId", "timezone", "periods", "version", "createdAt", "updatedAt");
    }

    @Test
    void responseTimesSerializeAsCanonicalHhMmWithoutSeconds() throws Exception {
        when(schedules.get(context, STAFF_MEMBER_ID)).thenReturn(details());

        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.periods[0].endTime").value("17:00"))
                .andExpect(content().string(not(containsString("\"startTime\":\"09:00:00\""))))
                .andExpect(content().string(not(containsString("\"endTime\":\"17:00:00\""))));
    }

    @Test
    void nonCanonicalSubMinuteAndMalformedTimesAreRejectedAtTheJsonBoundary()
            throws Exception {
        assertValidationError(putRequest("""
                {"expectedVersion":0,"periods":[
                  {"weekday":"MONDAY","startTime":"09:00:00","endTime":"17:00"}
                ]}
                """));
        assertValidationError(putRequest("""
                {"expectedVersion":0,"periods":[
                  {"weekday":"MONDAY","startTime":"09:00:30","endTime":"17:00"}
                ]}
                """));
        assertValidationError(putRequest("""
                {"expectedVersion":0,"periods":[
                  {"weekday":"MONDAY","startTime":"not-a-time","endTime":"17:00"}
                ]}
                """));
        assertValidationError(putRequest("""
                {"expectedVersion":0,"periods":[
                  {"weekday":"NOT_A_DAY","startTime":"09:00","endTime":"17:00"}
                ]}
                """));
        assertValidationError(putRequest("""
                {"expectedVersion":0,"periods":[
                  {"weekday":"MONDAY","startTime":"24:00","endTime":"01:00"}
                ]}
                """));
        assertValidationError(putRequest("""
                {"expectedVersion":0,"periods":[
                  {"weekday":"MONDAY","startTime":"23:00","endTime":"24:00"}
                ]}
                """));
        assertValidationError(putRequest("""
                {"expectedVersion":0,"periods":[
                  {"weekday":"MONDAY","startTime":"9:00","endTime":"17:00"}
                ]}
                """));
    }

    @Test
    void unknownRequestPropertiesAreIgnoredByTheEstablishedJsonPolicy() throws Exception {
        when(schedules.replace(
                        context, STAFF_MEMBER_ID, new ReplaceWorkingPeriodsCommand(List.of(), 0L)))
                .thenReturn(details());

        mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0,"periods":[],"unexpectedField":"value"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void malformedUuidAndJsonUseSafeValidationProblem() throws Exception {
        mvc.perform(get(
                        "/api/business/staff-members/not-a-uuid/working-schedule"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."));
        mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unexpectedWorkforceFailureUsesSanitizedGeneric500() throws Exception {
        when(schedules.get(context, STAFF_MEMBER_ID))
                .thenThrow(new UnexpectedFailure(
                        new SQLException("private SQL constraint tenant diagnostic")));

        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Възникна неочаквана грешка."))
                .andExpect(content().string(not(containsString("private"))))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(content().string(not(containsString(BUSINESS_ID.toString()))));
    }

    private ResultActions putRequest(String body) throws Exception {
        return mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/working-schedule",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private void assertValidationError(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static void assertComponents(Class<?> type, String... expected) {
        Set<String> components = Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        assertThat(components).containsExactlyInAnyOrder(expected);
    }

    private static void assertThatThrownByUnsupported(Runnable operation) {
        org.assertj.core.api.Assertions.assertThatThrownBy(operation::run)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private StaffWorkingScheduleAdministrationDetails details() {
        return new StaffWorkingScheduleAdministrationDetails(
                STAFF_MEMBER_ID,
                ZoneId.of("Europe/Sofia"),
                List.of(new WorkingPeriod(
                        DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))),
                3,
                CREATED,
                UPDATED);
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
