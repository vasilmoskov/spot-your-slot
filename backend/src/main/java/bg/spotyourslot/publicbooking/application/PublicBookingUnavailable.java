package bg.spotyourslot.publicbooking.application;

/**
 * A public read target that is not available. The subtypes are deliberately cause-free and carry
 * no slug, identifier, or state, so nothing can echo them.
 */
public abstract sealed class PublicBookingUnavailable extends RuntimeException
        permits PublicBookingUnavailable.Business,
                PublicBookingUnavailable.Service,
                PublicBookingUnavailable.Staff {
    private PublicBookingUnavailable(String message) {
        super(message, null, false, false);
    }

    /** Unknown, DRAFT, SUSPENDED, malformed, or reserved Business (ADR-0017): all one case. */
    public static final class Business extends PublicBookingUnavailable {
        public Business() {
            super("Public Business is unavailable");
        }
    }

    /** A missing, foreign, or inactive Service. */
    public static final class Service extends PublicBookingUnavailable {
        public Service() {
            super("Public Service is unavailable");
        }
    }

    /** A missing, foreign, inactive, or unassigned StaffMember. */
    public static final class Staff extends PublicBookingUnavailable {
        public Staff() {
            super("Public StaffMember is unavailable");
        }
    }
}
