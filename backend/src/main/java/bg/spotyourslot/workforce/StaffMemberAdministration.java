package bg.spotyourslot.workforce;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.workforce.StaffMemberRecords.CreateStaffMemberCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberDetails;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberPage;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.UpdateStaffMemberCommand;
import java.util.UUID;

public interface StaffMemberAdministration {
    StaffMemberPage list(AuthenticatedBusinessContext context, int page, int size);

    StaffMemberDetails get(
            AuthenticatedBusinessContext context, UUID staffMemberId);

    StaffMemberDetails create(
            AuthenticatedBusinessContext context, CreateStaffMemberCommand command);

    StaffMemberDetails update(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            UpdateStaffMemberCommand command);

    StaffMemberDetails deactivate(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            StaffMemberVersionCommand command);

    StaffMemberDetails reactivate(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            StaffMemberVersionCommand command);
}
