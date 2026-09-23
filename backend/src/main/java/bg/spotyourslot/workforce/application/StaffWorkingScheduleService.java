package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffWorkingScheduleNotFound;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingScheduleRow;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingScheduleStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-scoped schedule persistence, without the owner authorization, Business
 * lifecycle enforcement, or outer lock order that a later phase adds around it.
 */
@Service
public class StaffWorkingScheduleService {
    private final StaffWorkingScheduleStore store;
    private final StaffWorkingScheduleInputValidator validator;
    private final Clock clock;

    public StaffWorkingScheduleService(
            StaffWorkingScheduleStore store,
            StaffWorkingScheduleInputValidator validator,
            Clock clock) {
        this.store = store;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StaffWorkingScheduleDetails find(UUID businessId, UUID staffMemberId) {
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        StaffWorkingScheduleRow schedule = requireSchedule(businessId, validatedStaffMemberId);
        List<WorkingPeriod> periods = store.findPeriods(businessId, validatedStaffMemberId);
        return details(schedule, periods);
    }

    @Transactional
    public StaffWorkingScheduleDetails replace(
            UUID businessId, UUID staffMemberId, ReplaceWorkingPeriodsCommand command) {
        UUID validatedStaffMemberId = validator.validateStaffMemberId(staffMemberId);
        ReplaceWorkingPeriodsCommand validated = validator.validateReplacement(command);
        StaffWorkingScheduleRow current = requireSchedule(businessId, validatedStaffMemberId);
        requireCurrentVersion(current, validated.expectedVersion());

        StaffWorkingScheduleRow guarded = store.advanceScheduleVersion(
                        businessId,
                        validatedStaffMemberId,
                        validated.expectedVersion(),
                        clock.instant())
                .orElseThrow(ConcurrentUpdate::new);
        store.replacePeriods(businessId, validatedStaffMemberId, validated.periods());
        List<WorkingPeriod> periods = store.findPeriods(businessId, validatedStaffMemberId);
        return details(guarded, periods);
    }

    private StaffWorkingScheduleRow requireSchedule(UUID businessId, UUID staffMemberId) {
        return store.findByBusinessIdAndStaffMemberId(businessId, staffMemberId)
                .orElseThrow(StaffWorkingScheduleNotFound::new);
    }

    private void requireCurrentVersion(StaffWorkingScheduleRow current, long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdate();
        }
    }

    private StaffWorkingScheduleDetails details(
            StaffWorkingScheduleRow row, List<WorkingPeriod> periods) {
        return new StaffWorkingScheduleDetails(
                row.staffMemberId(),
                periods,
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }
}
