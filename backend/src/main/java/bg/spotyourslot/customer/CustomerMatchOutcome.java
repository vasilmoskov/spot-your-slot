package bg.spotyourslot.customer;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The four normal results of {@link CustomerIdentification#findOrCreate}. None carries a submitted
 * contact value, a match basis, or holder information. A concurrent or internal failure is never an
 * outcome: it is thrown as {@link CustomerConcurrentConflict} or {@link CustomerOperationFailure}.
 */
public sealed interface CustomerMatchOutcome {
    /** The supplied identifiers already belong to one Customer of the Business. */
    record ExistingCustomer(UUID customerId) implements CustomerMatchOutcome {
        public ExistingCustomer {
            Objects.requireNonNull(customerId, "customerId");
        }
    }

    /** No supplied identifier was held, so a new Customer was inserted. */
    record CreatedCustomer(UUID customerId) implements CustomerMatchOutcome {
        public CreatedCustomer {
            Objects.requireNonNull(customerId, "customerId");
        }
    }

    /** The submitted identity failed validation; only the invalid field identifiers are reported. */
    record InvalidIdentity(Set<IdentityField> fields) implements CustomerMatchOutcome {
        public InvalidIdentity {
            if (fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("At least one invalid field is required");
            }
            fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
        }
    }

    /**
     * The identifiers are inconsistent with the stored Customers (a partial or split match). It
     * deliberately reveals neither which identifier matched nor any Customer.
     */
    record IdentityConflict() implements CustomerMatchOutcome {
    }
}
