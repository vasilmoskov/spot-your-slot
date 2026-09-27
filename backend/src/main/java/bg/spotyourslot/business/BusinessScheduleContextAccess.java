package bg.spotyourslot.business;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

public interface BusinessScheduleContextAccess {
    Optional<BusinessScheduleContext> findScheduleContext(UUID businessId);

    Optional<BusinessScheduleContext> lockScheduleContext(UUID businessId);

    record BusinessScheduleContext(
            UUID businessId,
            LifecycleStatus status,
            ZoneId timezone) {
    }
}
