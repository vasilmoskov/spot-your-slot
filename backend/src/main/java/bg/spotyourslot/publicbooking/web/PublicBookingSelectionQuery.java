package bg.spotyourslot.publicbooking.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The canonical representation of a Service selection on the two read routes (ADR-0027): the
 * repeated query parameter {@code serviceId}, one canonical 36-character UUID per occurrence. The
 * order of the parameters carries no meaning. A missing or empty selection, more than five, a
 * duplicate, a comma list, or any malformed or non-canonical value is the public
 * {@code services} validation error; the value is never echoed. A selection never holds a name, a
 * price, or personal data, so nothing sensitive can reach a URL.
 */
final class PublicBookingSelectionQuery {
    static final String PARAMETER = "serviceId";
    private static final int MAX_SERVICES = 5;

    private PublicBookingSelectionQuery() {
    }

    static List<UUID> parse(String[] values) {
        if (values == null || values.length < 1 || values.length > MAX_SERVICES) {
            throw InvalidPublicRequest.services();
        }
        List<UUID> selection = new ArrayList<>(values.length);
        Set<UUID> seen = new HashSet<>();
        for (String value : values) {
            UUID id = CanonicalUuid.parse(value);
            if (id == null || !seen.add(id)) {
                throw InvalidPublicRequest.services();
            }
            selection.add(id);
        }
        return List.copyOf(selection);
    }
}
