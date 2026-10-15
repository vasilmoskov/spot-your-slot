package bg.spotyourslot.workforce.application;

import bg.spotyourslot.workforce.StaffCalendarAccess;
import bg.spotyourslot.workforce.infrastructure.StaffAvailabilityReadStore;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StaffCalendarAccessService implements StaffCalendarAccess {
    private final StaffAvailabilityReadStore store;

    public StaffCalendarAccessService(StaffAvailabilityReadStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<CalendarStaffMember> find(
            UUID businessId, UUID staffMemberIdOrNull, boolean withWeeklyPeriods) {
        Objects.requireNonNull(businessId, "businessId");
        try {
            return store.findCalendarStaff(businessId, staffMemberIdOrNull, withWeeklyPeriods).stream()
                    .map(row -> new CalendarStaffMember(
                            row.id(), row.displayName(), row.active(), row.weeklyPeriods()))
                    .toList();
        } catch (UnexpectedFailure exception) {
            throw new StaffCalendarFailure();
        }
    }
}
