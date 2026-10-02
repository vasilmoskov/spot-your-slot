package bg.spotyourslot.customer.infrastructure;

import bg.spotyourslot.customer.domain.Customer;
import bg.spotyourslot.customer.domain.CustomerProfile;
import bg.spotyourslot.customer.domain.InvalidCustomerData;
import bg.spotyourslot.customer.domain.NewCustomer;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicateEmail;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicatePhone;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.InvalidData;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnexpectedFailure;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnknownBusiness;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Internal Customer persistence. It owns every Customer SQL statement and returns domain
 * {@link Customer} values, never persistence entities (an intentional Customer-module design
 * choice). It is not a published contract.
 *
 * <p>Transaction semantics: the store opens no transaction of its own and joins the caller's, so a
 * rollback of the caller restores the previous state. Every write is a single statement. Reads take
 * no lock. Every statement is scoped by {@code business_id}, so a lookup in another Business behaves
 * exactly like a missing Customer. The Business of an existing Customer is never an update input.
 *
 * <p>Failures are classified only structurally, from the PostgreSQL SQLState and the exact
 * constraint (or table and column) name the driver reports, never by parsing message text, and are
 * rethrown as fixed, cause-free {@link CustomerPersistenceException}s.
 */
@Repository
public class CustomerStore {
    static final String PHONE_UNIQUE = "customer_business_phone_unique";
    static final String EMAIL_UNIQUE = "customer_business_email_unique";
    static final String BUSINESS_FOREIGN_KEY = "customer_business_fk";
    static final Set<String> CHECK_CONSTRAINTS = Set.of(
            "customer_display_name_canonical",
            "customer_display_name_not_blank",
            "customer_phone_canonical",
            "customer_email_canonical",
            "customer_contact_present",
            "customer_version_nonnegative",
            "customer_timestamps_finite_ordered");
    private static final int MAX_CAUSE_DEPTH = 16;
    private static final String TABLE = "customer";
    private static final Set<String> COLUMNS = Set.of(
            "id", "business_id", "display_name", "phone", "email", "version",
            "created_at", "updated_at");

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String VALUE_TOO_LONG = "22001";

    private static final String RETURNING_COLUMNS = """
            id, business_id, display_name, phone, email, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public CustomerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new Customer at version 0; a held phone or email is reported, never merged. */
    public Customer insert(NewCustomer customer) {
        CustomerProfile profile = customer.profile();
        return execute(() -> jdbc.sql("""
                        INSERT INTO customer(
                            id, business_id, display_name, phone, email,
                            version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :displayName, :phone, :email,
                            0, :createdAt, :createdAt)
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("id", customer.id())
                .param("businessId", customer.businessId())
                .param("displayName", profile.displayName())
                .param("phone", profile.phone())
                .param("email", profile.email())
                .param("createdAt", databaseTime(customer.createdAt()))
                .query(this::customer)
                .single());
    }

    /**
     * Inserts a new Customer at version 0 unless a unique constraint already holds a conflicting
     * row ({@code ON CONFLICT DO NOTHING}, no conflict target, so a held phone, a held email, and
     * an ID collision alike). A conflict returns empty and, unlike a plain insert, neither raises
     * nor aborts the caller's transaction; the caller re-reads to learn the holder. Foreign-key and
     * check violations are still translated failures.
     */
    public Optional<Customer> insertIfAbsent(NewCustomer customer) {
        CustomerProfile profile = customer.profile();
        return execute(() -> jdbc.sql("""
                        INSERT INTO customer(
                            id, business_id, display_name, phone, email,
                            version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :displayName, :phone, :email,
                            0, :createdAt, :createdAt)
                        ON CONFLICT DO NOTHING
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("id", customer.id())
                .param("businessId", customer.businessId())
                .param("displayName", profile.displayName())
                .param("phone", profile.phone())
                .param("email", profile.email())
                .param("createdAt", databaseTime(customer.createdAt()))
                .query(this::customer)
                .optional());
    }

    /**
     * One statement returning the Customers of the Business that hold the supplied phone or the
     * supplied email: at most two rows, because each identifier is unique per Business, and a
     * single row when both identifiers belong to the same Customer. A null identifier matches
     * nothing. The order is unspecified and callers must not depend on it. No lock is taken, and
     * another Business's holder is never returned.
     */
    public List<Customer> findHolders(UUID businessId, String phone, String email) {
        return execute(() -> jdbc.sql("""
                        SELECT
                        """ + RETURNING_COLUMNS + """
                        FROM customer
                        WHERE business_id = :businessId
                          AND (phone = :phone OR email = :email)
                        """)
                .param("businessId", businessId)
                .param("phone", phone, Types.VARCHAR)
                .param("email", email, Types.VARCHAR)
                .query(this::customer)
                .list());
    }

    public Optional<Customer> findById(UUID businessId, UUID customerId) {
        return execute(() -> jdbc.sql("""
                        SELECT
                        """ + RETURNING_COLUMNS + """
                        FROM customer
                        WHERE business_id = :businessId
                          AND id = :customerId
                        """)
                .param("businessId", businessId)
                .param("customerId", customerId)
                .query(this::customer)
                .optional());
    }

    public Optional<Customer> findByPhone(UUID businessId, String phone) {
        return execute(() -> jdbc.sql("""
                        SELECT
                        """ + RETURNING_COLUMNS + """
                        FROM customer
                        WHERE business_id = :businessId
                          AND phone = :phone
                        """)
                .param("businessId", businessId)
                .param("phone", phone)
                .query(this::customer)
                .optional());
    }

    public Optional<Customer> findByEmail(UUID businessId, String email) {
        return execute(() -> jdbc.sql("""
                        SELECT
                        """ + RETURNING_COLUMNS + """
                        FROM customer
                        WHERE business_id = :businessId
                          AND email = :email
                        """)
                .param("businessId", businessId)
                .param("email", email)
                .query(this::customer)
                .optional());
    }

    /**
     * Replaces name, phone, and email with one statement guarded by {@code business_id}, {@code id},
     * and the expected version. It returns the updated Customer with the version incremented
     * exactly once, or empty when no row matches: a missing Customer, a Customer of another
     * Business, and a stale version are deliberately indistinguishable here. The update instant
     * never moves backwards ({@code GREATEST}); a failed update changes nothing.
     */
    public Optional<Customer> update(
            UUID businessId,
            UUID customerId,
            CustomerProfile profile,
            long expectedVersion,
            Instant updatedAt) {
        return execute(() -> jdbc.sql("""
                        UPDATE customer
                        SET display_name = :displayName,
                            phone = :phone,
                            email = :email,
                            version = version + 1,
                            updated_at = GREATEST(updated_at, :updatedAt)
                        WHERE business_id = :businessId
                          AND id = :customerId
                          AND version = :expectedVersion
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("displayName", profile.displayName())
                .param("phone", profile.phone())
                .param("email", profile.email())
                .param("updatedAt", databaseTime(updatedAt))
                .param("businessId", businessId)
                .param("customerId", customerId)
                .param("expectedVersion", expectedVersion)
                .query(this::customer)
                .optional());
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (CustomerPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    /**
     * Classifies a failure structurally and discards it. The first {@link SQLException} in the
     * cause chain supplies the standard SQLState; for a PostgreSQL {@link PSQLException} the exact
     * constraint, table, and column names come from its {@link ServerErrorMessage}. Message text is
     * never read, missing structured data is treated as unexpected, and the translated exception
     * retains no reference to the original throwable.
     */
    static CustomerPersistenceException translate(Throwable failure) {
        SQLException sqlException = findSqlException(failure);
        if (sqlException == null) {
            return new UnexpectedFailure(null);
        }
        String sqlState = sqlException.getSQLState();
        String constraint = null;
        String table = null;
        String column = null;
        if (sqlException instanceof PSQLException postgres) {
            ServerErrorMessage details = postgres.getServerErrorMessage();
            if (details != null) {
                constraint = details.getConstraint();
                table = details.getTable();
                column = details.getColumn();
            }
        }

        if (UNIQUE_VIOLATION.equals(sqlState)) {
            if (PHONE_UNIQUE.equals(constraint)) {
                return new DuplicatePhone();
            }
            if (EMAIL_UNIQUE.equals(constraint)) {
                return new DuplicateEmail();
            }
        } else if (FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            if (BUSINESS_FOREIGN_KEY.equals(constraint)) {
                return new UnknownBusiness();
            }
        } else if (CHECK_VIOLATION.equals(sqlState)) {
            if (constraint != null && CHECK_CONSTRAINTS.contains(constraint)) {
                return new InvalidData();
            }
        } else if (NOT_NULL_VIOLATION.equals(sqlState)) {
            if (TABLE.equals(table) && column != null && COLUMNS.contains(column)) {
                return new InvalidData();
            }
        } else if (VALUE_TOO_LONG.equals(sqlState)) {
            // PostgreSQL reports no constraint for this state; every Customer statement writes
            // only the three length-limited Customer columns.
            return new InvalidData();
        }
        return new UnexpectedFailure(sqlState);
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private Customer customer(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            return new Customer(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("business_id", UUID.class),
                    new CustomerProfile(
                            resultSet.getString("display_name"),
                            resultSet.getString("phone"),
                            resultSet.getString("email")),
                    resultSet.getLong("version"),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
        } catch (InvalidCustomerData | IllegalArgumentException | NullPointerException corrupt) {
            // A stored row that violates a domain invariant is a persistence failure, not input.
            throw new CorruptRow();
        }
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final class CorruptRow extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CorruptRow() {
            super("Stored Customer row is invalid", null, false, false);
        }
    }
}
