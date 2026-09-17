package bg.spotyourslot.identity;

import java.util.Optional;
import java.util.UUID;

public interface AuthenticatedBusinessContext {
    UUID userId();

    Optional<UUID> selectedBusinessId();
}
