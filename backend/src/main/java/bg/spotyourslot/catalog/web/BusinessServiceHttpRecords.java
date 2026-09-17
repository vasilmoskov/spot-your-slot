package bg.spotyourslot.catalog.web;

import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.ServicePage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BusinessServiceHttpRecords {
    private BusinessServiceHttpRecords() {
    }

    public record CreateServiceRequest(
            String name,
            String description,
            Integer durationMinutes,
            BigDecimal price) {
    }

    public record UpdateServiceRequest(
            String name,
            String description,
            Integer durationMinutes,
            BigDecimal price,
            Long expectedVersion) {
    }

    public record ServiceLifecycleRequest(Long expectedVersion) {
    }

    public record ServiceResponse(
            UUID id,
            String name,
            String description,
            int durationMinutes,
            BigDecimal price,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static ServiceResponse from(ServiceDetails service) {
            return new ServiceResponse(
                    service.id(),
                    service.name(),
                    service.description(),
                    service.durationMinutes(),
                    service.price(),
                    service.active(),
                    service.version(),
                    service.createdAt(),
                    service.updatedAt());
        }
    }

    public record ServicePageResponse(
            List<ServiceResponse> services,
            int page,
            int size,
            long totalElements) {
        public ServicePageResponse {
            services = List.copyOf(services);
        }

        static ServicePageResponse from(ServicePage page) {
            return new ServicePageResponse(
                    page.services().stream()
                            .map(ServiceResponse::from)
                            .toList(),
                    page.page(),
                    page.size(),
                    page.totalElements());
        }
    }
}
