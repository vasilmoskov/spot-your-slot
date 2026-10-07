package bg.spotyourslot.publicbooking.web;

/** A structurally invalid public request. It carries no value and no field. */
final class InvalidPublicRequest extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidPublicRequest() {
        super("Public booking request is invalid", null, false, false);
    }
}
