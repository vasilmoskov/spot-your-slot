package bg.spotyourslot.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ServiceRecords {
    private ServiceRecords() {
    }

    public record ServiceDetails(
            UUID id,
            String name,
            String description,
            int durationMinutes,
            BigDecimal price,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ServicePage(
            List<ServiceDetails> services,
            int page,
            int size,
            long totalElements) {
        public ServicePage {
            services = List.copyOf(services);
        }
    }

    public record CreateServiceCommand(
            String name,
            String description,
            Integer durationMinutes,
            BigDecimal price) {
    }

    public record UpdateServiceCommand(
            String name,
            String description,
            Integer durationMinutes,
            BigDecimal price,
            Long expectedVersion) {
    }

    public record ServiceVersionCommand(Long expectedVersion) {
    }
}
