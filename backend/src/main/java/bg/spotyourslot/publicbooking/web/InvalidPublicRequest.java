package bg.spotyourslot.publicbooking.web;

/**
 * A structurally invalid public request. It carries no value. The only field it can name is the
 * Service selection ({@code services}), because that is the one field a guest can correct.
 */
final class InvalidPublicRequest extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final boolean services;

    InvalidPublicRequest() {
        this(false);
    }

    private InvalidPublicRequest(boolean services) {
        super("Public booking request is invalid", null, false, false);
        this.services = services;
    }

    /** A malformed Service selection: the public {@code services} field error. */
    static InvalidPublicRequest services() {
        return new InvalidPublicRequest(true);
    }

    boolean isServices() {
        return services;
    }
}
