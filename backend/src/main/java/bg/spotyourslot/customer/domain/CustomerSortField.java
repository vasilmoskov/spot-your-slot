package bg.spotyourslot.customer.domain;

/** The closed set of Customer list orderings; each maps to a fixed, trusted SQL fragment. */
public enum CustomerSortField {
    NAME,
    PHONE,
    EMAIL
}
