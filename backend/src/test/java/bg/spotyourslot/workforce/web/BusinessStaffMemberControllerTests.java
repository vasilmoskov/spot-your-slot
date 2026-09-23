package bg.spotyourslot.workforce.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.catalog.ServiceReferenceAccess.ServiceReferenceFailure;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.shared.web.ApiExceptionHandler;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.AssignedServiceSummary;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberAssignments;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberPage;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.AssignedServiceResponse;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.CreateStaffMemberRequest;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.ReplaceServiceAssignmentsRequest;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberAssignmentsResponse;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberLifecycleRequest;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberPageResponse;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberResponse;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.UpdateStaffMemberRequest;
import java.sql.SQLException;
import java.time.Instant;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class BusinessStaffMemberControllerTests {
    private static final UUID STAFF_MEMBER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000055");
    private static final UUID SERVICE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000066");
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000077");
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000088");
    private static final Instant CREATED = Instant.parse("2026-09-22T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-09-22T09:00:00Z");

    @Mock StaffMemberAdministration staffMembers;
    private AuthenticatedBusinessContext context;
    private BusinessStaffMemberController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var authentication = new TestAuthentication(USER_ID, BUSINESS_ID);
        context = authentication;
        SecurityContextHolder.getContext().setAuthentication(authentication);
        controller = new BusinessStaffMemberController(staffMembers);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentSecurityContextArgumentResolver())
                .setControllerAdvice(
                        new BusinessStaffMemberExceptionHandler(),
                        new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allEightRoutesUseSecurityContextAndMapApprovedContracts() throws Exception {
        StaffMemberDetails details = details();
        StaffMemberAssignments assignments = assignments();
        when(staffMembers.list(context, 0, 50))
                .thenReturn(new StaffMemberPage(List.of(details), 0, 50, 1));
        when(staffMembers.get(context, STAFF_MEMBER_ID)).thenReturn(details);
        when(staffMembers.create(context, new CreateStaffMemberCommand(
                        "  Анна  ", " TEAM@EXAMPLE.INVALID ", " +359 888 123 456 ")))
                .thenReturn(details);
        when(staffMembers.update(context, STAFF_MEMBER_ID, new UpdateStaffMemberCommand(
                        "  Анна Петрова  ", null, " +359 2 123 456 ", 7L)))
                .thenReturn(details);
        when(staffMembers.deactivate(
                        context, STAFF_MEMBER_ID, new StaffMemberVersionCommand(7L)))
                .thenReturn(details);
        when(staffMembers.reactivate(
                        context, STAFF_MEMBER_ID, new StaffMemberVersionCommand(7L)))
                .thenReturn(details);
        when(staffMembers.listServiceAssignments(context, STAFF_MEMBER_ID))
                .thenReturn(assignments);
        when(staffMembers.replaceServiceAssignments(
                        context,
                        STAFF_MEMBER_ID,
                        new ReplaceServiceAssignmentsCommand(List.of(SERVICE_ID), 7L)))
                .thenReturn(assignments);

        mvc.perform(get("/api/business/staff-members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffMembers[0].id").value(STAFF_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.staffMembers[0].displayName").value("Анна Иванова"))
                .andExpect(jsonPath("$.staffMembers[0].contactEmail")
                        .value("team@example.invalid"))
                .andExpect(jsonPath("$.staffMembers[0].contactPhone")
                        .value("+359 888 123 456"))
                .andExpect(jsonPath("$.staffMembers[0].active").value(true))
                .andExpect(jsonPath("$.staffMembers[0].version").value(7))
                .andExpect(jsonPath("$.staffMembers[0].businessId").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/business/staff-members/{staffMemberId}", STAFF_MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(STAFF_MEMBER_ID.toString()));
        mvc.perform(post("/api/business/staff-members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "  Анна  ",
                                  "contactEmail": " TEAM@EXAMPLE.INVALID ",
                                  "contactPhone": " +359 888 123 456 "
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location", "/api/business/staff-members/" + STAFF_MEMBER_ID))
                .andExpect(jsonPath("$.id").value(STAFF_MEMBER_ID.toString()));
        mvc.perform(put("/api/business/staff-members/{staffMemberId}", STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "  Анна Петрова  ",
                                  "contactEmail": null,
                                  "contactPhone": " +359 2 123 456 ",
                                  "expectedVersion": 7
                                }
                                """))
                .andExpect(status().isOk());
        mvc.perform(post(
                        "/api/business/staff-members/{staffMemberId}/deactivate",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":7}"))
                .andExpect(status().isOk());
        mvc.perform(post(
                        "/api/business/staff-members/{staffMemberId}/reactivate",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":7}"))
                .andExpect(status().isOk());
        mvc.perform(get(
                        "/api/business/staff-members/{staffMemberId}/service-assignments",
                        STAFF_MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffMemberId").value(STAFF_MEMBER_ID.toString()))
                .andExpect(jsonPath("$.services[0].id").value(SERVICE_ID.toString()))
                .andExpect(jsonPath("$.services[0].name").value("Подстригване"))
                .andExpect(jsonPath("$.services[0].active").value(true));
        mvc.perform(put(
                        "/api/business/staff-members/{staffMemberId}/service-assignments",
                        STAFF_MEMBER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceIds": ["%s"],
                                  "expectedVersion": 7
                                }
                                """.formatted(SERVICE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(7));

        verify(staffMembers).list(context, 0, 50);
        verify(staffMembers).get(context, STAFF_MEMBER_ID);
        verify(staffMembers).create(context, new CreateStaffMemberCommand(
                "  Анна  ", " TEAM@EXAMPLE.INVALID ", " +359 888 123 456 "));
        verify(staffMembers).update(context, STAFF_MEMBER_ID, new UpdateStaffMemberCommand(
                "  Анна Петрова  ", null, " +359 2 123 456 ", 7L));
        verify(staffMembers).deactivate(
                context, STAFF_MEMBER_ID, new StaffMemberVersionCommand(7L));
        verify(staffMembers).reactivate(
                context, STAFF_MEMBER_ID, new StaffMemberVersionCommand(7L));
        verify(staffMembers).listServiceAssignments(context, STAFF_MEMBER_ID);
        verify(staffMembers).replaceServiceAssignments(
                context,
                STAFF_MEMBER_ID,
                new ReplaceServiceAssignmentsCommand(List.of(SERVICE_ID), 7L));
    }

    @Test
    void explicitPaginationIsDelegatedWithoutReordering() throws Exception {
        StaffMemberDetails first = details();
        StaffMemberDetails second = new StaffMemberDetails(
                UUID.fromString("00000000-0000-0000-0000-000000000099"),
                "Борис",
                null,
                null,
                false,
                3,
                CREATED,
                UPDATED);
        when(staffMembers.list(context, 2, 100))
                .thenReturn(new StaffMemberPage(List.of(second, first), 2, 100, 202));

        mvc.perform(get("/api/business/staff-members")
                        .param("page", "2")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffMembers[0].id").value(second.id().toString()))
                .andExpect(jsonPath("$.staffMembers[1].id").value(first.id().toString()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(202));
        verify(staffMembers).list(context, 2, 100);
    }

    @Test
    void requestAndResponseCollectionsAreDefensivelyImmutable() {
        var sourceIds = new ArrayList<UUID>();
        sourceIds.add(SERVICE_ID);
        sourceIds.add(null);
        var request = new ReplaceServiceAssignmentsRequest(sourceIds, 2L);
        sourceIds.clear();

        assertThat(request.serviceIds()).containsExactly(SERVICE_ID, null);
        assertThatThrownByUnsupported(() -> request.serviceIds().add(UUID.randomUUID()));

        var sourceServices = new ArrayList<>(List.of(
                new AssignedServiceResponse(SERVICE_ID, "Подстригване", true)));
        var response = new StaffMemberAssignmentsResponse(
                STAFF_MEMBER_ID, 2, CREATED, UPDATED, sourceServices);
        sourceServices.clear();

        assertThat(response.services()).hasSize(1);
        assertThatThrownByUnsupported(() -> response.services().clear());
    }

    @Test
    void everyHttpRecordContainsOnlyTheApprovedFields() {
        assertComponents(CreateStaffMemberRequest.class,
                "displayName", "contactEmail", "contactPhone");
        assertComponents(UpdateStaffMemberRequest.class,
                "displayName", "contactEmail", "contactPhone", "expectedVersion");
        assertComponents(StaffMemberLifecycleRequest.class, "expectedVersion");
        assertComponents(ReplaceServiceAssignmentsRequest.class,
                "serviceIds", "expectedVersion");
        assertComponents(StaffMemberResponse.class,
                "id", "displayName", "contactEmail", "contactPhone", "active",
                "version", "createdAt", "updatedAt");
        assertComponents(StaffMemberPageResponse.class,
                "staffMembers", "page", "size", "totalElements");
        assertComponents(AssignedServiceResponse.class, "id", "name", "active");
        assertComponents(StaffMemberAssignmentsResponse.class,
                "staffMemberId", "version", "createdAt", "updatedAt", "services");
    }

    @Test
    void malformedUuidAndJsonUseSafeValidationProblem() throws Exception {
        mvc.perform(get("/api/business/staff-members/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."));
        mvc.perform(post("/api/business/staff-members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unexpectedWorkforceAndCatalogFailuresUseSanitizedGeneric500() throws Exception {
        when(staffMembers.get(context, STAFF_MEMBER_ID))
                .thenThrow(new UnexpectedFailure(
                        new SQLException("private SQL constraint tenant diagnostic")))
                .thenThrow(new ServiceReferenceFailure(
                        new SQLException("private Catalog UUID diagnostic")));

        assertGeneric500(mvc.perform(get(
                "/api/business/staff-members/{staffMemberId}", STAFF_MEMBER_ID)));
        assertGeneric500(mvc.perform(get(
                "/api/business/staff-members/{staffMemberId}", STAFF_MEMBER_ID)));
    }

    private void assertGeneric500(org.springframework.test.web.servlet.ResultActions result)
            throws Exception {
        result.andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Възникна неочаквана грешка."))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SQL"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(BUSINESS_ID.toString()))));
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

    private StaffMemberDetails details() {
        return new StaffMemberDetails(
                STAFF_MEMBER_ID,
                "Анна Иванова",
                "team@example.invalid",
                "+359 888 123 456",
                true,
                7,
                CREATED,
                UPDATED);
    }

    private StaffMemberAssignments assignments() {
        return new StaffMemberAssignments(
                STAFF_MEMBER_ID,
                7,
                CREATED,
                UPDATED,
                List.of(new AssignedServiceSummary(
                        SERVICE_ID, "Подстригване", true)));
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
