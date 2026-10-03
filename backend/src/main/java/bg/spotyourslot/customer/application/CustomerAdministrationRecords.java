package bg.spotyourslot.customer.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Commands and read models of Customer administration. They carry personal data, so every
 * {@code toString()} is redacted.
 */
public final class CustomerAdministrationRecords {
    private CustomerAdministrationRecords() {
    }

    public record CreateCustomerCommand(String displayName, String phone, String email) {
        @Override
        public String toString() {
            return "CreateCustomerCommand[redacted]";
        }
    }

    public record UpdateCustomerCommand(
            String displayName, String phone, String email, Long expectedVersion) {
        @Override
        public String toString() {
            return "UpdateCustomerCommand[redacted]";
        }
    }

    /** A search request. Absent {@code page} or {@code size} mean the defaults. */
    public record CustomerSearchCommand(
            String search, Integer page, Integer size, String sort, String direction) {
        @Override
        public String toString() {
            return "CustomerSearchCommand[redacted]";
        }
    }

    public record CustomerSummary(UUID id, String displayName, String phone, String email) {
        @Override
        public String toString() {
            return "CustomerSummary[redacted]";
        }
    }

    public record CustomerDetails(
            UUID id,
            String displayName,
            String phone,
            String email,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        @Override
        public String toString() {
            return "CustomerDetails[redacted]";
        }
    }

    public record CustomerPage(List<CustomerSummary> items, int page, int size, long total) {
        public CustomerPage {
            items = List.copyOf(items);
        }

        @Override
        public String toString() {
            return "CustomerPage[redacted]";
        }
    }
}
