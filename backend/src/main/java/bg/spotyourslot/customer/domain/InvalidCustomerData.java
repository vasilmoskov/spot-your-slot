package bg.spotyourslot.customer.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Thrown when Customer data violates a domain invariant. It carries the identifiers of every
 * invalid field found in one validation pass, so a caller can report all of them together. It
 * deliberately holds no submitted value, no validation-library message, and no cause.
 */
public final class InvalidCustomerData extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Set<CustomerField> fields;

    public InvalidCustomerData(Set<CustomerField> fields) {
        super("Customer data is invalid");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("At least one invalid field is required");
        }
        this.fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
    }

    /** The immutable set of invalid fields, never empty. */
    public Set<CustomerField> fields() {
        return fields;
    }
}
