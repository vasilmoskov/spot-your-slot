package bg.spotyourslot.business.domain;

import java.util.Set;

/**
 * The single authoritative list of top-level public path roots that a Business slug may not use
 * (ADR-0018). Matching is exact and is performed on the canonical lowercase slug, so
 * near-misses such as {@code booking-studio} remain valid. The frontend keeps a mirror only for
 * local validation and routing; this class is the source of truth.
 */
public final class ReservedBusinessSlugs {
    private static final Set<String> VALUES = Set.of(
            "forgot-password",
            "password-reset",
            "invitation",
            "login",
            "logout",
            "profile",
            "platform",
            "business",
            "api",
            "actuator",
            "assets",
            "admin",
            "b",
            "book",
            "booking",
            "cancel",
            "cancellation",
            "confirmation",
            "appointments");

    private ReservedBusinessSlugs() {
    }

    public static boolean isReserved(BusinessSlug slug) {
        return VALUES.contains(slug.value());
    }

    public static Set<String> values() {
        return VALUES;
    }
}
