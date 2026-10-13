package bg.spotyourslot.publicbooking.web;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict identifier reading for the new Service selection (ADR-0027). {@link UUID#fromString}
 * also accepts short forms and signed hexadecimal groups (a leading plus sign), so the text must
 * first match the canonical 8-4-4-4-12 hexadecimal form exactly.
 */
final class CanonicalUuid {
    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private CanonicalUuid() {
    }

    /** @return the identifier, or {@code null} when the text is not exactly the canonical form */
    static UUID parse(String text) {
        if (text == null || !CANONICAL.matcher(text).matches()) {
            return null;
        }
        return UUID.fromString(text);
    }
}
