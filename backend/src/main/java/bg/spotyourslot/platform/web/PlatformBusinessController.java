package bg.spotyourslot.platform.web;

import bg.spotyourslot.business.BusinessRecords;
import bg.spotyourslot.platform.application.PlatformBusinessService;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.BusinessDetailsResponse;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.BusinessPageResponse;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.CreateBusinessRequest;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.LifecycleRequest;
import bg.spotyourslot.platform.web.PlatformBusinessHttpRecords.UpdateBusinessRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/platform/businesses")
public class PlatformBusinessController {
    private final PlatformBusinessService businesses;

    public PlatformBusinessController(PlatformBusinessService businesses) {
        this.businesses = businesses;
    }

    @GetMapping
    public BusinessPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return BusinessPageResponse.from(businesses.list(page, size));
    }

    @GetMapping("/{businessId}")
    public BusinessDetailsResponse get(@PathVariable UUID businessId) {
        return BusinessDetailsResponse.from(businesses.get(businessId));
    }

    @PostMapping
    public ResponseEntity<BusinessDetailsResponse> create(
            @Valid @RequestBody CreateBusinessRequest request) {
        var created = businesses.create(BusinessRecords.createCommand(
                request.slug(),
                request.displayName(),
                request.businessType(),
                request.timezone(),
                request.description(),
                request.address(),
                request.phone(),
                request.contactEmail()));
        URI location = URI.create("/api/platform/businesses/" + created.id());
        return ResponseEntity.created(location)
                .body(BusinessDetailsResponse.from(created));
    }

    @PutMapping("/{businessId}")
    public BusinessDetailsResponse update(
            @PathVariable UUID businessId,
            @Valid @RequestBody UpdateBusinessRequest request) {
        var updated = businesses.update(
                businessId,
                BusinessRecords.updateCommand(
                        request.slug(),
                        request.displayName(),
                        request.businessType(),
                        request.timezone(),
                        request.description(),
                        request.address(),
                        request.phone(),
                        request.contactEmail(),
                        request.expectedVersion()));
        return BusinessDetailsResponse.from(updated);
    }

    @PostMapping("/{businessId}/activate")
    public BusinessDetailsResponse activate(
            @PathVariable UUID businessId,
            @Valid @RequestBody LifecycleRequest request) {
        return BusinessDetailsResponse.from(
                businesses.activateDraft(businessId, request.expectedVersion()));
    }

    @PostMapping("/{businessId}/suspend")
    public BusinessDetailsResponse suspend(
            @PathVariable UUID businessId,
            @Valid @RequestBody LifecycleRequest request) {
        return BusinessDetailsResponse.from(
                businesses.suspendActive(businessId, request.expectedVersion()));
    }

    @PostMapping("/{businessId}/reactivate")
    public BusinessDetailsResponse reactivate(
            @PathVariable UUID businessId,
            @Valid @RequestBody LifecycleRequest request) {
        return BusinessDetailsResponse.from(
                businesses.reactivateSuspended(businessId, request.expectedVersion()));
    }
}
