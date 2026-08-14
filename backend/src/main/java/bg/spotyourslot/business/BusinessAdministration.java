package bg.spotyourslot.business;

import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.BusinessPage;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import java.util.UUID;

public interface BusinessAdministration {
    BusinessPage list(int page, int size);

    BusinessDetails get(UUID businessId);

    BusinessDetails create(CreateBusinessCommand command);

    BusinessDetails update(UUID businessId, UpdateBusinessCommand command);

    BusinessDetails activateDraft(UUID businessId, long expectedVersion);

    BusinessDetails suspendActive(UUID businessId, long expectedVersion);

    BusinessDetails reactivateSuspended(UUID businessId, long expectedVersion);
}
