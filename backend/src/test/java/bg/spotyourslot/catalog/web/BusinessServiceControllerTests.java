package bg.spotyourslot.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.ServicePage;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.CreateServiceRequest;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.ServiceLifecycleRequest;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.ServicePageResponse;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.ServiceResponse;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.UpdateServiceRequest;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.mockito.ArgumentCaptor;
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
class BusinessServiceControllerTests {
    private static final UUID SERVICE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000055");
    private static final UUID USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000077");
    private static final UUID BUSINESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000088");
    private static final Instant CREATED = Instant.parse("2026-09-17T08:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-09-17T09:00:00Z");

    @Mock ServiceAdministration services;
    private BusinessServiceController controller;
    private AuthenticatedBusinessContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var authentication = new TestAuthentication(USER_ID, BUSINESS_ID);
        context = authentication;
        SecurityContextHolder.getContext().setAuthentication(authentication);
        controller = new BusinessServiceController(services);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentSecurityContextArgumentResolver())
                .setControllerAdvice(
                        new BusinessServiceExceptionHandler(),
                        new bg.spotyourslot.shared.web.ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listUsesDefaultsAndMapsTheApprovedPage() throws Exception {
        when(services.list(context, 0, 50))
                .thenReturn(new ServicePage(List.of(details()), 0, 50, 1));

        mvc.perform(get("/api/business/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].id").value(SERVICE_ID.toString()))
                .andExpect(jsonPath("$.services[0].name").value("Haircut"))
                .andExpect(jsonPath("$.services[0].price").value(25.50))
                .andExpect(jsonPath("$.services[0].businessId").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1));
        verify(services).list(context, 0, 50);
    }

    @Test
    void mapsDetailAndEveryMutationResult() {
        ServiceDetails details = details();
        var create = new CreateServiceRequest(
                "  Haircut  ", "  Description  ", 30, new BigDecimal("25.50"));
        var update = new UpdateServiceRequest(
                "  Haircut and beard  ", "  Updated  ", 45, new BigDecimal("30.00"), 7L);
        var lifecycle = new ServiceLifecycleRequest(8L);
        when(services.get(context, SERVICE_ID)).thenReturn(details);
        when(services.create(context, new CreateServiceCommand(
                        create.name(), create.description(), create.durationMinutes(), create.price())))
                .thenReturn(details);
        when(services.update(context, SERVICE_ID, new UpdateServiceCommand(
                        update.name(),
                        update.description(),
                        update.durationMinutes(),
                        update.price(),
                        update.expectedVersion())))
                .thenReturn(details);
        when(services.deactivate(context, SERVICE_ID, new ServiceVersionCommand(8L)))
                .thenReturn(details);
        when(services.reactivate(context, SERVICE_ID, new ServiceVersionCommand(8L)))
                .thenReturn(details);

        ServiceResponse detail = controller.get(context, SERVICE_ID);
        var created = controller.create(context, create);
        ServiceResponse updated = controller.update(context, SERVICE_ID, update);
        ServiceResponse deactivated = controller.deactivate(context, SERVICE_ID, lifecycle);
        ServiceResponse reactivated = controller.reactivate(context, SERVICE_ID, lifecycle);

        assertThat(detail).isEqualTo(created.getBody())
                .isEqualTo(updated)
                .isEqualTo(deactivated)
                .isEqualTo(reactivated);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation())
                .hasToString("/api/business/services/" + SERVICE_ID);
    }

    @Test
    void passesRawCanonicalizableAndExactNumericValuesToTheApplication() {
        var request = new CreateServiceRequest(
                "  Haircut\u00A0name  ",
                "  Description  ",
                30,
                new BigDecimal("25.50"));
        when(services.create(context, new CreateServiceCommand(
                        request.name(),
                        request.description(),
                        request.durationMinutes(),
                        request.price())))
                .thenReturn(details());

        controller.create(context, request);

        var command = ArgumentCaptor.forClass(CreateServiceCommand.class);
        verify(services).create(org.mockito.ArgumentMatchers.same(context), command.capture());
        assertThat(command.getValue().name()).isEqualTo("  Haircut\u00A0name  ");
        assertThat(command.getValue().description()).isEqualTo("  Description  ");
        assertThat(command.getValue().price()).isSameAs(request.price());
    }

    @Test
    void requestAndResponseContractsNeverExposeBusinessIdentity() {
        Set<String> components = List.of(
                        CreateServiceRequest.class,
                        UpdateServiceRequest.class,
                        ServiceLifecycleRequest.class,
                        ServiceResponse.class,
                        ServicePageResponse.class)
                .stream()
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertThat(components).doesNotContain("businessId");
    }

    @Test
    void malformedUuidAndBodyUseTheSafeValidationContract() throws Exception {
        mvc.perform(get("/api/business/services/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("Проверете въведените данни."));

        mvc.perform(post("/api/business/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private ServiceDetails details() {
        return new ServiceDetails(
                SERVICE_ID,
                "Haircut",
                "Description",
                30,
                new BigDecimal("25.50"),
                true,
                7,
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
