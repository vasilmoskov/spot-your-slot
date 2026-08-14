package bg.spotyourslot.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.business.BusinessApplicationException.BusinessNotFound;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.BusinessPage;
import bg.spotyourslot.business.BusinessRecords.BusinessSummary;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessType;
import bg.spotyourslot.platform.application.PlatformBusinessService;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.CreateBusinessRequest;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.LifecycleRequest;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.UpdateBusinessRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PlatformBusinessControllerTests {
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000055");
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Mock PlatformBusinessService service;

    private PlatformBusinessController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new PlatformBusinessController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(
                        new PlatformBusinessExceptionHandler(),
                        new bg.spotyourslot.shared.web.ApiExceptionHandler())
                .build();
    }

    @Test
    void listUsesDefaultPaginationAndMapsApprovedSummaryFields() throws Exception {
        var summary = summary();
        when(service.list(0, 50)).thenReturn(new BusinessPage(List.of(summary), 0, 50, 1));

        mvc.perform(get("/api/platform/businesses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.businesses[0].id").value(BUSINESS_ID.toString()))
                .andExpect(jsonPath("$.businesses[0].businessType").value("BEAUTY_STUDIO"))
                .andExpect(jsonPath("$.businesses[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.businesses[0].description").doesNotExist());
        verify(service).list(0, 50);
    }

    @Test
    void listDelegatesExplicitPagination() {
        when(service.list(2, 25)).thenReturn(new BusinessPage(List.of(), 2, 25, 0));

        var response = controller.list(2, 25);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(25);
        verify(service).list(2, 25);
    }

    @Test
    void getMapsEveryApprovedDetailField() throws Exception {
        BusinessDetails details = details();
        when(service.get(BUSINESS_ID)).thenReturn(details);

        mvc.perform(get("/api/platform/businesses/{businessId}", BUSINESS_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(details.id().toString()))
                .andExpect(jsonPath("$.slug").value(details.slug()))
                .andExpect(jsonPath("$.displayName").value(details.displayName()))
                .andExpect(jsonPath("$.businessType").value("BEAUTY_STUDIO"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.timezone").value(details.timezone()))
                .andExpect(jsonPath("$.description").value(details.description()))
                .andExpect(jsonPath("$.address").value(details.address()))
                .andExpect(jsonPath("$.phone").value(details.phone()))
                .andExpect(jsonPath("$.contactEmail").value(details.contactEmail()))
                .andExpect(jsonPath("$.version").value(details.version()))
                .andExpect(jsonPath("$.createdAt").value(details.createdAt().toString()))
                .andExpect(jsonPath("$.updatedAt").value(details.updatedAt().toString()));
    }

    @Test
    void createMapsOnlyEditableFieldsAndReturnsCreatedLocation() throws Exception {
        when(service.create(any())).thenReturn(details());

        mvc.perform(post("/api/platform/businesses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "new-business",
                                  "displayName": "New Business",
                                  "businessType": "OTHER",
                                  "description": "Description",
                                  "address": "Address",
                                  "phone": "+359 2 000 0000",
                                  "contactEmail": "contact@example.invalid"
                                }
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(BUSINESS_ID.toString()))
                .andExpect(header().string(
                        "Location", "/api/platform/businesses/" + BUSINESS_ID));

        var captor = ArgumentCaptor.forClass(CreateBusinessCommand.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("new-business");
        assertThat(captor.getValue().businessType()).isEqualTo(BusinessType.OTHER);
        assertThat(captor.getValue().timezone()).isNull();
    }

    @Test
    void updateMapsEditableFieldsAndExpectedVersion() throws Exception {
        when(service.update(org.mockito.Mockito.eq(BUSINESS_ID), any()))
                .thenReturn(details());

        mvc.perform(put("/api/platform/businesses/{businessId}", BUSINESS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "updated-business",
                                  "displayName": "Updated Business",
                                  "businessType": "MASSAGE_STUDIO",
                                  "timezone": "Europe/London",
                                  "expectedVersion": 7
                                }
                                """))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UpdateBusinessCommand.class);
        verify(service).update(org.mockito.Mockito.eq(BUSINESS_ID), captor.capture());
        assertThat(captor.getValue().slug()).isEqualTo("updated-business");
        assertThat(captor.getValue().businessType()).isEqualTo(BusinessType.MASSAGE_STUDIO);
        assertThat(captor.getValue().expectedVersion()).isEqualTo(7);
    }

    @Test
    void activateMapsExpectedVersion() throws Exception {
        when(service.activateDraft(BUSINESS_ID, 3)).thenReturn(details());

        mvc.perform(post("/api/platform/businesses/{businessId}/activate", BUSINESS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3}"))
                .andExpect(status().isOk());

        verify(service).activateDraft(BUSINESS_ID, 3);
    }

    @Test
    void suspendMapsExpectedVersion() throws Exception {
        when(service.suspendActive(BUSINESS_ID, 4)).thenReturn(details());

        mvc.perform(post("/api/platform/businesses/{businessId}/suspend", BUSINESS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}"))
                .andExpect(status().isOk());

        verify(service).suspendActive(BUSINESS_ID, 4);
    }

    @Test
    void reactivateMapsExpectedVersion() throws Exception {
        when(service.reactivateSuspended(BUSINESS_ID, 5)).thenReturn(details());

        mvc.perform(post("/api/platform/businesses/{businessId}/reactivate", BUSINESS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5}"))
                .andExpect(status().isOk());

        verify(service).reactivateSuspended(BUSINESS_ID, 5);
    }

    @Test
    void unknownBusinessTypeReturnsSafeValidationProblem() throws Exception {
        mvc.perform(post("/api/platform/businesses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slug": "platform-business",
                                  "displayName": "Platform Business",
                                  "businessType": "UNKNOWN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."));
    }

    @Test
    void businessNotFoundUsesPlatformAdviceBeforeGenericFallback() throws Exception {
        when(service.get(BUSINESS_ID)).thenThrow(new BusinessNotFound());

        mvc.perform(get("/api/platform/businesses/{businessId}", BUSINESS_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUSINESS_NOT_FOUND"));
    }

    @Test
    void businessConflictUsesPlatformAdviceBeforeGenericFallback() throws Exception {
        when(service.suspendActive(BUSINESS_ID, 4)).thenThrow(new ConcurrentUpdate());

        mvc.perform(post("/api/platform/businesses/{businessId}/suspend", BUSINESS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONCURRENT_UPDATE"));
    }

    @Test
    void unexpectedFailureFallsThroughToSafeGenericFallback() throws Exception {
        when(service.get(BUSINESS_ID)).thenThrow(new IllegalStateException("internal-secret"));

        mvc.perform(get("/api/platform/businesses/{businessId}", BUSINESS_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Възникна неочаквана грешка."));
    }

    @Test
    void requestContractsExcludeServerManagedFields() {
        assertThat(componentNames(CreateBusinessRequest.class))
                .doesNotContain("status", "version", "expectedVersion", "createdAt", "updatedAt");
        assertThat(componentNames(UpdateBusinessRequest.class))
                .contains("expectedVersion")
                .doesNotContain("status", "version", "createdAt", "updatedAt");
        assertThat(componentNames(LifecycleRequest.class))
                .containsExactly("expectedVersion");
    }

    private Set<String> componentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
    }

    private BusinessSummary summary() {
        return new BusinessSummary(
                BUSINESS_ID,
                "platform-business",
                "Platform Business",
                BusinessType.BEAUTY_STUDIO,
                BusinessStatus.DRAFT,
                "Europe/Sofia",
                0,
                NOW,
                NOW);
    }

    private BusinessDetails details() {
        return new BusinessDetails(
                BUSINESS_ID,
                "platform-business",
                "Platform Business",
                BusinessType.BEAUTY_STUDIO,
                BusinessStatus.DRAFT,
                "Europe/Sofia",
                "Description",
                "Address",
                "+359 2 000 0000",
                "contact@example.invalid",
                0,
                NOW,
                NOW);
    }
}
