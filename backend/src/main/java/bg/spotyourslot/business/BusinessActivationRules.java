package bg.spotyourslot.business;

import bg.spotyourslot.business.BusinessApplicationException.InvalidLifecycleTransition;
import bg.spotyourslot.business.BusinessRecords.BusinessDetails;
import bg.spotyourslot.business.domain.BusinessStatus;

public final class BusinessActivationRules {
    private BusinessActivationRules() {
    }

    public static void requireDraft(BusinessDetails business) {
        if (business.status() != BusinessStatus.DRAFT) {
            throw new InvalidLifecycleTransition(
                    business.status(), BusinessStatus.ACTIVE);
        }
    }
}
