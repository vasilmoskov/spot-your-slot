package bg.spotyourslot.workforce.application;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.workforce.StaffWorkingScheduleAdministration;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.BusinessAccessDenied;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.BusinessSuspended;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffMemberInactive;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffMemberNotFound;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleAdministrationDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleDetails;
import bg.spotyourslot.workforce.infrastructure.StaffMemberRow;
import bg.spotyourslot.workforce.infrastructure.StaffMemberStore;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaffWorkingScheduleAdministrationService implements StaffWorkingScheduleAdministration {
    private final StaffMemberStore staffMembers;
    private final StaffWorkingScheduleService schedules;
    private final StaffWorkingScheduleInputValidator validator;
    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;

    public StaffWorkingScheduleAdministrationService(
            StaffMemberStore staffMembers,
            StaffWorkingScheduleService schedules,
            StaffWorkingScheduleInputValidator validator,
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners) {
        this.staffMembers = staffMembers;
        this.schedules = schedules;
        this.validator = validator;
        this.businesses = businesses;
        this.owners = owners;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public StaffWorkingScheduleAdministrationDetails get(
            AuthenticatedBusinessContext context, UUID staffMemberId) {
        BusinessSelection selection = authorizeRead(context);
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        requireStaffMember(selection.businessId(), validatedStaffMemberId);
        StaffWorkingScheduleDetails details = schedules.find(
                selection.businessId(), validatedStaffMemberId);
        return administrationDetails(details, selection.timezone());
    }

    @Override
    @Transactional
    public StaffWorkingScheduleAdministrationDetails replace(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            ReplaceWorkingPeriodsCommand command) {
        BusinessSelection selection = authorizeMutation(context);
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        StaffMemberRow staffMember = staffMembers.lockActiveState(
                        selection.businessId(), validatedStaffMemberId)
                .orElseThrow(StaffMemberNotFound::new);
        if (!staffMember.active()) {
            throw new StaffMemberInactive();
        }
        StaffWorkingScheduleDetails details = schedules.replace(
                selection.businessId(), validatedStaffMemberId, command);
        return administrationDetails(details, selection.timezone());
    }

    private BusinessSelection authorizeRead(AuthenticatedBusinessContext context) {
        UUID userId = requireUserId(context);
        UUID businessId = requireSelectedBusinessId(context);
        BusinessScheduleContext scheduleContext = businesses.findScheduleContext(businessId)
                .orElseThrow(BusinessAccessDenied::new);
        if (owners.authorize(userId, businessId) != Authorization.GRANTED) {
            throw new BusinessAccessDenied();
        }
        return new BusinessSelection(businessId, scheduleContext.timezone());
    }

    private BusinessSelection authorizeMutation(AuthenticatedBusinessContext context) {
        UUID userId = requireUserId(context);
        UUID businessId = requireSelectedBusinessId(context);
        BusinessScheduleContext scheduleContext = businesses.lockScheduleContext(businessId)
                .orElseThrow(BusinessAccessDenied::new);
        if (owners.lockAndAuthorize(userId, businessId) != Authorization.GRANTED) {
            throw new BusinessAccessDenied();
        }
        if (scheduleContext.status() == LifecycleStatus.SUSPENDED) {
            throw new BusinessSuspended();
        }
        return new BusinessSelection(businessId, scheduleContext.timezone());
    }

    private UUID requireUserId(AuthenticatedBusinessContext context) {
        if (context == null || context.userId() == null) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        return context.userId();
    }

    private UUID requireSelectedBusinessId(AuthenticatedBusinessContext context) {
        return context.selectedBusinessId().orElseThrow(SelectedBusinessRequired::new);
    }

    private StaffMemberRow requireStaffMember(UUID businessId, UUID staffMemberId) {
        return staffMembers.findByBusinessIdAndId(businessId, staffMemberId)
                .orElseThrow(StaffMemberNotFound::new);
    }

    private StaffWorkingScheduleAdministrationDetails administrationDetails(
            StaffWorkingScheduleDetails details, ZoneId timezone) {
        return new StaffWorkingScheduleAdministrationDetails(
                details.staffMemberId(),
                timezone,
                details.periods(),
                details.version(),
                details.createdAt(),
                details.updatedAt());
    }

    private record BusinessSelection(UUID businessId, ZoneId timezone) {
    }
}
