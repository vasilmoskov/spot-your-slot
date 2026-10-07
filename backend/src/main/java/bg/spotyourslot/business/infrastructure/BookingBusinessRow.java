package bg.spotyourslot.business.infrastructure;

import bg.spotyourslot.business.domain.BusinessStatus;
import java.util.UUID;

/** The two Business facts a guest booking attempt needs after it locks the Business. */
public record BookingBusinessRow(UUID id, BusinessStatus status) {
}
