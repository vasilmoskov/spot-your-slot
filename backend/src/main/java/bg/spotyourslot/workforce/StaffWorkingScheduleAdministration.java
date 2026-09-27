package bg.spotyourslot.workforce;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleAdministrationDetails;
import java.util.UUID;

public interface StaffWorkingScheduleAdministration {
    StaffWorkingScheduleAdministrationDetails get(
            AuthenticatedBusinessContext context, UUID staffMemberId);

    StaffWorkingScheduleAdministrationDetails replace(
            AuthenticatedBusinessContext context,
            UUID staffMemberId,
            ReplaceWorkingPeriodsCommand command);
}
