package bg.spotyourslot.workforce.web;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.CreateStaffMemberRequest;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.ReplaceServiceAssignmentsRequest;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberAssignmentsResponse;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberLifecycleRequest;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberPageResponse;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.StaffMemberResponse;
import bg.spotyourslot.workforce.web.BusinessStaffMemberHttpRecords.UpdateStaffMemberRequest;
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
@RequestMapping("/api/business/staff-members")
public class BusinessStaffMemberController {
    private final StaffMemberAdministration staffMembers;

    public BusinessStaffMemberController(StaffMemberAdministration staffMembers) {
        this.staffMembers = staffMembers;
    }

    @GetMapping
    public StaffMemberPageResponse list(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return StaffMemberPageResponse.from(staffMembers.list(context, page, size));
    }

    @GetMapping("/{staffMemberId}")
    public StaffMemberResponse get(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId) {
        return StaffMemberResponse.from(staffMembers.get(context, staffMemberId));
    }

    @PostMapping
    public ResponseEntity<StaffMemberResponse> create(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestBody CreateStaffMemberRequest request) {
        var created = staffMembers.create(context, new CreateStaffMemberCommand(
                request.displayName(), request.contactEmail(), request.contactPhone()));
        URI location = URI.create("/api/business/staff-members/" + created.id());
        return ResponseEntity.created(location)
                .body(StaffMemberResponse.from(created));
    }

    @PutMapping("/{staffMemberId}")
    public StaffMemberResponse update(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId,
            @RequestBody UpdateStaffMemberRequest request) {
        return StaffMemberResponse.from(staffMembers.update(
                context,
                staffMemberId,
                new UpdateStaffMemberCommand(
                        request.displayName(),
                        request.contactEmail(),
                        request.contactPhone(),
                        request.expectedVersion())));
    }

    @PostMapping("/{staffMemberId}/deactivate")
    public StaffMemberResponse deactivate(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId,
            @RequestBody StaffMemberLifecycleRequest request) {
        return StaffMemberResponse.from(staffMembers.deactivate(
                context,
                staffMemberId,
                new StaffMemberVersionCommand(request.expectedVersion())));
    }

    @PostMapping("/{staffMemberId}/reactivate")
    public StaffMemberResponse reactivate(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId,
            @RequestBody StaffMemberLifecycleRequest request) {
        return StaffMemberResponse.from(staffMembers.reactivate(
                context,
                staffMemberId,
                new StaffMemberVersionCommand(request.expectedVersion())));
    }

    @GetMapping("/{staffMemberId}/service-assignments")
    public StaffMemberAssignmentsResponse listServiceAssignments(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId) {
        return StaffMemberAssignmentsResponse.from(
                staffMembers.listServiceAssignments(context, staffMemberId));
    }

    @PutMapping("/{staffMemberId}/service-assignments")
    public StaffMemberAssignmentsResponse replaceServiceAssignments(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId,
            @RequestBody ReplaceServiceAssignmentsRequest request) {
        return StaffMemberAssignmentsResponse.from(staffMembers.replaceServiceAssignments(
                context,
                staffMemberId,
                new ReplaceServiceAssignmentsCommand(
                        request.serviceIds(), request.expectedVersion())));
    }
}
