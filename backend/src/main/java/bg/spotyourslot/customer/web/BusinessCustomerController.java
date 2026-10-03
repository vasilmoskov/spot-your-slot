package bg.spotyourslot.customer.web;

import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CreateCustomerCommand;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerSearchCommand;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.UpdateCustomerCommand;
import bg.spotyourslot.customer.application.CustomerAdministrationService;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.CreateCustomerRequest;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.CustomerPageResponse;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.CustomerResponse;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.SearchCustomersRequest;
import bg.spotyourslot.customer.web.BusinessCustomerHttpRecords.UpdateCustomerRequest;
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

/**
 * The private, owner-only Customer administration API (ADR-0021). The search term travels only in
 * the {@code POST /search} body, never in a URL. The Business comes from the authenticated
 * context; a Business ID in a body is ignored.
 */
@RestController
@RequestMapping("/api/business/customers")
public class BusinessCustomerController {
    /**
     * Any path segment except the literal {@code search}, so {@code GET} and {@code PUT} on
     * {@code /search} are method errors (405, only {@code POST} exists) rather than a malformed ID.
     * Other malformed IDs still reach the handler and are a generic validation error.
     */
    private static final String ID = "{customerId:^(?!search$).+}";

    private final CustomerAdministrationService customers;

    public BusinessCustomerController(CustomerAdministrationService customers) {
        this.customers = customers;
    }

    @GetMapping
    public CustomerPageResponse list(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return CustomerPageResponse.from(customers.list(context, page, size, sort, direction));
    }

    @PostMapping("/search")
    public CustomerPageResponse search(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestBody SearchCustomersRequest request) {
        return CustomerPageResponse.from(customers.search(
                context,
                new CustomerSearchCommand(
                        request.search(),
                        request.page(),
                        request.size(),
                        request.sort(),
                        request.direction())));
    }

    @GetMapping("/" + ID)
    public CustomerResponse get(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID customerId) {
        return CustomerResponse.from(customers.get(context, customerId));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestBody CreateCustomerRequest request) {
        var created = customers.create(
                context,
                new CreateCustomerCommand(request.displayName(), request.phone(), request.email()));
        URI location = URI.create("/api/business/customers/" + created.id());
        return ResponseEntity.created(location).body(CustomerResponse.from(created));
    }

    @PutMapping("/" + ID)
    public CustomerResponse update(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID customerId,
            @RequestBody UpdateCustomerRequest request) {
        return CustomerResponse.from(customers.update(
                context,
                customerId,
                new UpdateCustomerCommand(
                        request.displayName(),
                        request.phone(),
                        request.email(),
                        request.expectedVersion())));
    }
}
