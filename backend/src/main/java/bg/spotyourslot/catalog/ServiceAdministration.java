package bg.spotyourslot.catalog;

import bg.spotyourslot.catalog.ServiceRecords.CreateServiceCommand;
import bg.spotyourslot.catalog.ServiceRecords.ServiceDetails;
import bg.spotyourslot.catalog.ServiceRecords.ServicePage;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.catalog.ServiceRecords.UpdateServiceCommand;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.util.UUID;

public interface ServiceAdministration {
    ServicePage list(AuthenticatedBusinessContext context, int page, int size);

    ServiceDetails get(AuthenticatedBusinessContext context, UUID serviceId);

    ServiceDetails create(
            AuthenticatedBusinessContext context, CreateServiceCommand command);

    ServiceDetails update(
            AuthenticatedBusinessContext context,
            UUID serviceId,
            UpdateServiceCommand command);

    ServiceDetails deactivate(
            AuthenticatedBusinessContext context,
            UUID serviceId,
            ServiceVersionCommand command);

    ServiceDetails reactivate(
            AuthenticatedBusinessContext context,
            UUID serviceId,
            ServiceVersionCommand command);
}
