package bg.spotyourslot.platform.application;

import bg.spotyourslot.business.BusinessActivationRules;
import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.business.BusinessApplicationException.ConcurrentUpdate;
import bg.spotyourslot.business.BusinessApplicationException.InputField;
import bg.spotyourslot.business.BusinessApplicationException.InvalidInput;
import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.BusinessRecords.BusinessPage;
import bg.spotyourslot.business.BusinessRecords.CreateBusinessCommand;
import bg.spotyourslot.business.BusinessRecords.UpdateBusinessCommand;
import bg.spotyourslot.identity.ActiveBusinessOwnerQuery;
import bg.spotyourslot.platform.MissingActiveOwner;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformBusinessService {
    private final BusinessAdministration businesses;
    private final ActiveBusinessOwnerQuery activeOwners;

    public PlatformBusinessService(
            BusinessAdministration businesses, ActiveBusinessOwnerQuery activeOwners) {
        this.businesses = businesses;
        this.activeOwners = activeOwners;
    }

    @Transactional(readOnly = true)
    public BusinessPage list(int page, int size) {
        return businesses.list(page, size);
    }

    @Transactional(readOnly = true)
    public BusinessDetails get(UUID businessId) {
        return businesses.get(businessId);
    }

    @Transactional
    public BusinessDetails create(CreateBusinessCommand command) {
        return businesses.create(command);
    }

    @Transactional
    public BusinessDetails update(UUID businessId, UpdateBusinessCommand command) {
        return businesses.update(businessId, command);
    }

    @Transactional
    public BusinessDetails activateDraft(UUID businessId, long expectedVersion) {
        validateBusinessId(businessId);
        validateExpectedVersion(expectedVersion);
        BusinessDetails current = businesses.get(businessId);
        if (current.version() != expectedVersion) {
            throw new ConcurrentUpdate();
        }
        BusinessActivationRules.requireDraft(current);
        if (!activeOwners.hasActiveBusinessOwner(businessId)) {
            throw new MissingActiveOwner();
        }
        return businesses.activateDraft(businessId, expectedVersion);
    }

    @Transactional
    public BusinessDetails suspendActive(UUID businessId, long expectedVersion) {
        return businesses.suspendActive(businessId, expectedVersion);
    }

    @Transactional
    public BusinessDetails reactivateSuspended(UUID businessId, long expectedVersion) {
        return businesses.reactivateSuspended(businessId, expectedVersion);
    }

    private void validateBusinessId(UUID businessId) {
        if (businessId == null) {
            throw new InvalidInput(InputField.BUSINESS_ID);
        }
    }

    private void validateExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new InvalidInput(InputField.EXPECTED_VERSION);
        }
    }
}
