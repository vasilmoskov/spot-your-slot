package bg.spotyourslot.scheduling.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessAccessDenied;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.BusinessSuspended;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ConcurrentUpdate;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InputField;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.OverlapConflict;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.ScheduleExceptionNotFound;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberInactive;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.StaffMemberNotFound;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException;
import bg.spotyourslot.shared.web.ApiExceptionHandler;
import bg.spotyourslot.workforce.StaffMemberReferenceAccess.StaffMemberReferenceFailure;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.CurrentSecurityContextArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class BusinessScheduleExceptionExceptionHandlerTests {
    private static final UUID EXCEPTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000c01");
    private static final String SECRET =
            "schedule_exception_closure_no_overlap 00000000-0000-0000-0000-00000000dead SELECT";

    @Mock ScheduleExceptionAdministration exceptions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new TestAuthentication());
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

    static Stream<Arguments> mappings() {
        return Stream.of(
                Arguments.of(new SelectedBusinessRequired(), 403, "ACTIVE_BUSINESS_REQUIRED",
                        "Изберете бизнес, за да продължите."),
                Arguments.of(new BusinessAccessDenied(), 403, "ACCESS_DENIED",
                        "Нямате достъп до тази операция."),
                Arguments.of(new BusinessSuspended(), 409, "BUSINESS_SUSPENDED",
                        "Спрян бизнес може само да преглежда данните си."),
                Arguments.of(new StaffMemberNotFound(), 404, "STAFF_MEMBER_NOT_FOUND",
                        "Членът на екипа не е намерен."),
                Arguments.of(new StaffMemberInactive(), 409, "STAFF_MEMBER_INACTIVE",
                        "Изключенията на неактивен член на екипа не могат да бъдат променяни."),
                Arguments.of(new ScheduleExceptionNotFound(), 404, "SCHEDULE_EXCEPTION_NOT_FOUND",
                        "Изключението от графика не е намерено."),
                Arguments.of(new OverlapConflict(), 409, "SCHEDULE_EXCEPTION_OVERLAP",
                        "Вече има изключение от същия вид за тези дати."),
                Arguments.of(new ConcurrentUpdate(), 409, "SCHEDULE_EXCEPTION_CONCURRENT_UPDATE",
                        "Изключението от графика е променено от друга операция. "
                                + "Обновете данните и опитайте отново."));
    }

    @ParameterizedTest
    @MethodSource("mappings")
    void everyApplicationFailureHasItsStableStatusCodeAndBulgarianDetail(
            RuntimeException failure, int status, String code, String detail) throws Exception {
        when(exceptions.get(any(), any())).thenThrow(failure);

        mvc.perform(get("/api/business/schedule-exceptions/{id}", EXCEPTION_ID))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.title").value("Заявката не може да бъде изпълнена."))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    static Stream<Arguments> namedFields() {
        return Stream.of(
                Arguments.of(InputField.KIND, "kind"),
                Arguments.of(InputField.STAFF_MEMBER_ID, "staffMemberId"),
                Arguments.of(InputField.FIRST_DATE, "firstDate"),
                Arguments.of(InputField.LAST_DATE, "lastDate"),
                Arguments.of(InputField.ALL_DAY, "allDay"),
                Arguments.of(InputField.PERIODS, "periods"));
    }

    @ParameterizedTest
    @MethodSource("namedFields")
    void knownBodyFieldsAreNamedInFieldErrors(InputField field, String key) throws Exception {
        when(exceptions.get(any(), any())).thenThrow(new InvalidInput(field));

        mvc.perform(get("/api/business/schedule-exceptions/{id}", EXCEPTION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."))
                .andExpect(jsonPath("$.fieldErrors." + key).isString())
                .andExpect(jsonPath("$.fieldErrors.length()").value(1));
    }

    @ParameterizedTest
    @EnumSource(
            value = InputField.class,
            names = {"COMMAND", "EXPECTED_VERSION", "WINDOW"})
    void identifierVersionWindowAndCommandFailuresStayGeneric(InputField field) throws Exception {
        when(exceptions.get(any(), any())).thenThrow(new InvalidInput(field));

        mvc.perform(get("/api/business/schedule-exceptions/{id}", EXCEPTION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    static Stream<RuntimeException> unexpected() {
        var cause = new DataAccessResourceFailureException(SECRET, new SQLException(SECRET, "XX000"));
        return Stream.of(
                new ScheduleExceptionPersistenceException.UnexpectedFailure(cause),
                new ScheduleExceptionPersistenceException.InvalidReference(cause),
                new StaffMemberReferenceFailure(cause),
                new IllegalStateException(SECRET));
    }

    @ParameterizedTest
    @MethodSource("unexpected")
    void unexpectedAndUnreachableFailuresAreSanitizedInternalErrors(RuntimeException failure)
            throws Exception {
        doThrow(failure).when(exceptions).delete(any(), any(), any());

        String body = mvc.perform(delete("/api/business/schedule-exceptions/{id}", EXCEPTION_ID)
                        .param("expectedVersion", "0"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Възникна неочаквана грешка."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("schedule_exception")
                .doesNotContain("SELECT")
                .doesNotContain("dead")
                .doesNotContain(SECRET)
                .doesNotContain("Exception");
    }

    @Test
    void sealedHierarchyHasNoUnmappedApplicationFailure() {
        var permitted = ScheduleExceptionApplicationException.class.getPermittedSubclasses();
        assertThat(permitted).hasSize(8);
    }

    private record TestAuthentication() implements Authentication, AuthenticatedBusinessContext {
        private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000c02");
        private static final UUID BUSINESS = UUID.fromString("00000000-0000-0000-0000-000000000c03");

        @Override
        public UUID userId() {
            return USER;
        }

        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(BUSINESS);
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
            return USER;
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
            return USER.toString();
        }
    }
}
