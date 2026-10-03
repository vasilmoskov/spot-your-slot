package bg.spotyourslot.customer.application;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The typed, expected failures of Customer administration. Every message is fixed and no instance
 * holds a submitted value, an ID, SQL, a cause, or a suppressed exception: only field identifiers
 * are carried, so the web layer can name the field without echoing data.
 */
public abstract sealed class CustomerAdministrationException extends RuntimeException
        permits CustomerAdministrationException.InvalidInput,
                CustomerAdministrationException.BusinessAccessDenied,
                CustomerAdministrationException.BusinessSuspended,
                CustomerAdministrationException.CustomerNotFound,
                CustomerAdministrationException.ContactConflict,
                CustomerAdministrationException.ConcurrentUpdate {
    private static final long serialVersionUID = 1L;

    private CustomerAdministrationException(String safeMessage) {
        super(safeMessage, null, false, true);
    }

    /** The input fields a client can correct (the first four) and the generic request values. */
    public enum InputField {
        DISPLAY_NAME,
        PHONE,
        EMAIL,
        CONTACT,
        COMMAND,
        BUSINESS_ID,
        CUSTOMER_ID,
        EXPECTED_VERSION,
        SEARCH,
        PAGE,
        SIZE,
        SORT,
        DIRECTION
    }

    /** The identifiers a conflicting Customer already holds. */
    public enum ConflictField {
        PHONE,
        EMAIL
    }

    public static final class InvalidInput extends CustomerAdministrationException {
        private static final long serialVersionUID = 1L;

        private final transient Set<InputField> fields;

        public InvalidInput(Set<InputField> fields) {
            super("Customer input is invalid");
            if (fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("At least one invalid field is required");
            }
            this.fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
        }

        public InvalidInput(InputField field) {
            this(EnumSet.of(field));
        }

        public Set<InputField> fields() {
            return fields;
        }
    }

    public static final class BusinessAccessDenied extends CustomerAdministrationException {
        private static final long serialVersionUID = 1L;

        public BusinessAccessDenied() {
            super("Business access to Customers is denied");
        }
    }

    public static final class BusinessSuspended extends CustomerAdministrationException {
        private static final long serialVersionUID = 1L;

        public BusinessSuspended() {
            super("Suspended Business cannot mutate Customers");
        }
    }

    /** The Customer does not exist in the selected Business (missing and foreign are identical). */
    public static final class CustomerNotFound extends CustomerAdministrationException {
        private static final long serialVersionUID = 1L;

        public CustomerNotFound() {
            super("Customer was not found");
        }
    }

    /** Another Customer of the Business already holds the named identifiers. */
    public static final class ContactConflict extends CustomerAdministrationException {
        private static final long serialVersionUID = 1L;

        private final transient Set<ConflictField> fields;

        public ContactConflict(Set<ConflictField> fields) {
            super("Customer contact is held by another Customer");
            if (fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("At least one conflicting field is required");
            }
            this.fields = Collections.unmodifiableSet(EnumSet.copyOf(fields));
        }

        public Set<ConflictField> fields() {
            return fields;
        }
    }

    /** The expected version is stale. */
    public static final class ConcurrentUpdate extends CustomerAdministrationException {
        private static final long serialVersionUID = 1L;

        public ConcurrentUpdate() {
            super("Customer was changed by another operation");
        }
    }
}
