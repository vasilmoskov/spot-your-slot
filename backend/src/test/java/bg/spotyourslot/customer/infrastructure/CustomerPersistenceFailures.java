package bg.spotyourslot.customer.infrastructure;

/**
 * Test-only factory for the internal persistence exceptions, whose constructors are package-private,
 * so the application layer's sanitizing can be exercised without a database.
 */
public final class CustomerPersistenceFailures {
    private CustomerPersistenceFailures() {
    }

    public static CustomerPersistenceException unexpected(String sqlState) {
        return new CustomerPersistenceException.UnexpectedFailure(sqlState);
    }

    public static CustomerPersistenceException unknownBusiness() {
        return new CustomerPersistenceException.UnknownBusiness();
    }

    public static CustomerPersistenceException invalidData() {
        return new CustomerPersistenceException.InvalidData();
    }

    public static CustomerPersistenceException duplicatePhone() {
        return new CustomerPersistenceException.DuplicatePhone();
    }

    public static CustomerPersistenceException duplicateEmail() {
        return new CustomerPersistenceException.DuplicateEmail();
    }
}
