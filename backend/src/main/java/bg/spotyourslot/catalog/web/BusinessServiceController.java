package bg.spotyourslot.catalog.web;

import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.CreateServiceRequest;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.ServiceLifecycleRequest;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.ServicePageResponse;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.ServiceResponse;
import bg.spotyourslot.catalog.web.BusinessServiceHttpRecords.UpdateServiceRequest;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business/services")
public class BusinessServiceController {
    private final ServiceAdministration services;

    public BusinessServiceController(ServiceAdministration services) {
        this.services = services;
    }

    @GetMapping
    public ServicePageResponse list(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ServicePageResponse.from(services.list(context, page, size));
    }

    @GetMapping("/{serviceId}")
    public ServiceResponse get(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID serviceId) {
        return ServiceResponse.from(services.get(context, serviceId));
    }

    @PostMapping
    public ResponseEntity<ServiceResponse> create(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestBody CreateServiceRequest request) {
        var created = services.create(context, new CreateServiceCommand(
                request.name(),
                request.description(),
                request.durationMinutes(),
                request.price()));
        URI location = URI.create("/api/business/services/" + created.id());
        return ResponseEntity.created(location)
                .body(ServiceResponse.from(created));
    }

    @PutMapping("/{serviceId}")
    public ServiceResponse update(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID serviceId,
            @RequestBody UpdateServiceRequest request) {
        return ServiceResponse.from(services.update(
                context,
                serviceId,
                new UpdateServiceCommand(
                        request.name(),
                        request.description(),
                        request.durationMinutes(),
                        request.price(),
                        request.expectedVersion())));
    }

    @PostMapping("/{serviceId}/deactivate")
    public ServiceResponse deactivate(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID serviceId,
            @RequestBody ServiceLifecycleRequest request) {
        return ServiceResponse.from(services.deactivate(
                context,
                serviceId,
                new ServiceVersionCommand(request.expectedVersion())));
    }

    @PostMapping("/{serviceId}/reactivate")
    public ServiceResponse reactivate(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID serviceId,
            @RequestBody ServiceLifecycleRequest request) {
        return ServiceResponse.from(services.reactivate(
                context,
                serviceId,
                new ServiceVersionCommand(request.expectedVersion())));
    }
}
