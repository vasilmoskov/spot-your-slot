package bg.spotyourslot.workforce.web;

import bg.spotyourslot.workforce.StaffMemberRecords.AssignedServiceSummary;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberAssignments;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberPage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class BusinessStaffMemberHttpRecords {
    private BusinessStaffMemberHttpRecords() {
    }

    public record CreateStaffMemberRequest(
            String displayName,
            String contactEmail,
            String contactPhone) {
    }

    public record UpdateStaffMemberRequest(
            String displayName,
            String contactEmail,
            String contactPhone,
            Long expectedVersion) {
    }

    public record StaffMemberLifecycleRequest(Long expectedVersion) {
    }

    public record ReplaceServiceAssignmentsRequest(
            List<UUID> serviceIds,
            Long expectedVersion) {
        public ReplaceServiceAssignmentsRequest {
            if (serviceIds != null) {
                serviceIds = Collections.unmodifiableList(new ArrayList<>(serviceIds));
            }
        }
    }

    public record StaffMemberResponse(
            UUID id,
            String displayName,
            String contactEmail,
            String contactPhone,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        static StaffMemberResponse from(StaffMemberDetails staffMember) {
            return new StaffMemberResponse(
                    staffMember.id(),
                    staffMember.displayName(),
                    staffMember.contactEmail(),
                    staffMember.contactPhone(),
                    staffMember.active(),
                    staffMember.version(),
                    staffMember.createdAt(),
                    staffMember.updatedAt());
        }
    }

    public record StaffMemberPageResponse(
            List<StaffMemberResponse> staffMembers,
            int page,
            int size,
            long totalElements) {
        public StaffMemberPageResponse {
            staffMembers = List.copyOf(staffMembers);
        }

        static StaffMemberPageResponse from(StaffMemberPage page) {
            return new StaffMemberPageResponse(
                    page.staffMembers().stream()
                            .map(StaffMemberResponse::from)
                            .toList(),
                    page.page(),
                    page.size(),
                    page.totalElements());
        }
    }

    public record AssignedServiceResponse(UUID id, String name, boolean active) {
        static AssignedServiceResponse from(AssignedServiceSummary service) {
            return new AssignedServiceResponse(
                    service.id(), service.name(), service.active());
        }
    }

    public record StaffMemberAssignmentsResponse(
            UUID staffMemberId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<AssignedServiceResponse> services) {
        public StaffMemberAssignmentsResponse {
            services = List.copyOf(services);
        }

        static StaffMemberAssignmentsResponse from(StaffMemberAssignments assignments) {
            return new StaffMemberAssignmentsResponse(
                    assignments.staffMemberId(),
                    assignments.version(),
                    assignments.createdAt(),
                    assignments.updatedAt(),
                    assignments.services().stream()
                            .map(AssignedServiceResponse::from)
                            .toList());
        }
    }
}
