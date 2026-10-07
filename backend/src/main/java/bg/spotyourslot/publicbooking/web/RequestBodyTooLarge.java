package bg.spotyourslot.publicbooking.web;

import java.io.IOException;

/**
 * Thrown by the bounded request stream when the bytes actually read exceed the configured maximum,
 * or when the declared length already does. It carries no size, value, or cause.
 */
final class RequestBodyTooLarge extends IOException {
    private static final long serialVersionUID = 1L;

    RequestBodyTooLarge() {
        super("Public booking request body is too large", null);
    }
}
