package bg.spotyourslot.workforce.web;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.workforce.StaffWorkingScheduleAdministration;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.web.BusinessStaffWorkingScheduleHttpRecords.ReplaceWorkingScheduleRequest;
import bg.spotyourslot.workforce.web.BusinessStaffWorkingScheduleHttpRecords.WorkingScheduleResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business/staff-members")
public class BusinessStaffWorkingScheduleController {
    private final StaffWorkingScheduleAdministration schedules;

    public BusinessStaffWorkingScheduleController(StaffWorkingScheduleAdministration schedules) {
        this.schedules = schedules;
    }

    @GetMapping("/{staffMemberId}/working-schedule")
    public WorkingScheduleResponse get(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId) {
        return WorkingScheduleResponse.from(schedules.get(context, staffMemberId));
    }

    @PutMapping("/{staffMemberId}/working-schedule")
    public WorkingScheduleResponse replace(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable UUID staffMemberId,
            @RequestBody ReplaceWorkingScheduleRequest request) {
        return WorkingScheduleResponse.from(schedules.replace(
                context,
                staffMemberId,
                new ReplaceWorkingPeriodsCommand(
                        request.toWorkingPeriods(), request.expectedVersion())));
    }
}
