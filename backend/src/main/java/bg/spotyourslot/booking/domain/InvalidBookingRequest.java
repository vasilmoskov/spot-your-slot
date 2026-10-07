package bg.spotyourslot.booking.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A booking request that failed validation. It names every invalid field and carries no value, so
 * its message can never leak a submitted name, phone, email, or note.
 */
public final class InvalidBookingRequest extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Set<RequestField> fields;

    public InvalidBookingRequest(Set<RequestField> fields) {
        super("Booking request is invalid", null, false, false);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("At least one invalid field is required");
        }
        this.fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
    }

    public Set<RequestField> fields() {
        return fields;
    }
}
