package bg.spotyourslot.customer.web;

import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerDetails;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerPage;
import bg.spotyourslot.customer.application.CustomerAdministrationRecords.CustomerSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the private Customer API. Requests and responses carry personal data, so every
 * {@code toString()} is redacted. Wire names are {@code phone} and {@code email}.
 */
public final class BusinessCustomerHttpRecords {
    private BusinessCustomerHttpRecords() {
    }

    public record CreateCustomerRequest(String displayName, String phone, String email) {
        @Override
        public String toString() {
            return "CreateCustomerRequest[redacted]";
        }
    }

    public record UpdateCustomerRequest(
            String displayName, String phone, String email, Long expectedVersion) {
        @Override
        public String toString() {
            return "UpdateCustomerRequest[redacted]";
        }
    }

    public record SearchCustomersRequest(
            String search, Integer page, Integer size, String sort, String direction) {
        @Override
        public String toString() {
            return "SearchCustomersRequest[redacted]";
        }
    }

    public record CustomerListItemResponse(UUID id, String displayName, String phone, String email) {
        static CustomerListItemResponse from(CustomerSummary customer) {
            return new CustomerListItemResponse(
                    customer.id(), customer.displayName(), customer.phone(), customer.email());
        }

        @Override
        public String toString() {
            return "CustomerListItemResponse[redacted]";
        }
    }

    public record CustomerResponse(
            UUID id,
            String displayName,
            String phone,
            String email,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static CustomerResponse from(CustomerDetails customer) {
            return new CustomerResponse(
                    customer.id(),
                    customer.displayName(),
                    customer.phone(),
                    customer.email(),
                    customer.version(),
                    customer.createdAt(),
                    customer.updatedAt());
        }

        @Override
        public String toString() {
            return "CustomerResponse[redacted]";
        }
    }

    public record CustomerPageResponse(
            List<CustomerListItemResponse> items, int page, int size, long total) {
        public CustomerPageResponse {
            items = List.copyOf(items);
        }

        static CustomerPageResponse from(CustomerPage page) {
            return new CustomerPageResponse(
                    page.items().stream().map(CustomerListItemResponse::from).toList(),
                    page.page(),
                    page.size(),
                    page.total());
        }

        @Override
        public String toString() {
            return "CustomerPageResponse[redacted]";
        }
    }
}
