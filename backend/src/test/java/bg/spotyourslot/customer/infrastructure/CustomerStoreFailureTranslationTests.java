package bg.spotyourslot.customer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicateEmail;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.DuplicatePhone;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.InvalidData;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnexpectedFailure;
import bg.spotyourslot.customer.infrastructure.CustomerPersistenceException.UnknownBusiness;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Classification is structural only: the SQLState plus the exact constraint, table, or column name
 * from the driver's structured error fields. These tests build driver exceptions directly so every
 * state can be exercised without provoking it through valid domain values.
 */
class CustomerStoreFailureTranslationTests {
    @Test
    void mapsTheApprovedUniqueConstraintsToTheTypedDuplicates() {
        assertThat(translate(error("23505", "customer_business_phone_unique", null, null)))
                .isInstanceOf(DuplicatePhone.class);
        assertThat(translate(error("23505", "customer_business_email_unique", null, null)))
                .isInstanceOf(DuplicateEmail.class);
    }

    @Test
    void mapsTheBusinessForeignKeyToAnUnknownBusiness() {
        assertThat(translate(error("23503", "customer_business_fk", null, null)))
                .isInstanceOf(UnknownBusiness.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "customer_display_name_canonical",
        "customer_display_name_not_blank",
        "customer_phone_canonical",
        "customer_email_canonical",
        "customer_contact_present",
        "customer_version_nonnegative",
        "customer_timestamps_finite_ordered",
    })
    void mapsEveryApprovedCheckConstraintToInvalidData(String constraint) {
        assertThat(translate(error("23514", constraint, null, null))).isInstanceOf(InvalidData.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"display_name", "business_id", "id", "version", "created_at", "updated_at"})
    void mapsANotNullViolationOfACustomerColumnToInvalidData(String column) {
        assertThat(translate(error("23502", null, "customer", column))).isInstanceOf(InvalidData.class);
    }

    @Test
    void mapsAValueTooLongToInvalidData() {
        assertThat(translate(error("22001", null, null, null))).isInstanceOf(InvalidData.class);
    }

    @Test
    void anUnknownConstraintOrTableOrColumnIsNeverAnExpectedFailure() {
        assertThat(translate(error("23505", "customer_pkey", null, null))).isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", "some_other_unique", null, null))).isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", null, null, null))).isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23503", "staff_member_business_fk", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23514", "staff_member_contact_phone_canonical", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23514", null, null, null))).isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23502", null, "staff_member", "display_name")))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23502", null, "customer", "unknown_column")))
                .isInstanceOf(UnexpectedFailure.class);
    }

    @Test
    void theApprovedConstraintNameUnderTheWrongSqlStateIsNotClassified() {
        assertThat(translate(error("23514", "customer_business_phone_unique", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", "customer_business_fk", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
    }

    @Test
    void serializationFailuresAndDeadlocksStayInternalUnexpectedFailuresKeepingOnlyTheSqlState() {
        assertThat(((UnexpectedFailure) translate(error("40001", null, null, null))).sqlState())
                .isEqualTo("40001");
        assertThat(((UnexpectedFailure) translate(error("40P01", null, null, null))).sqlState())
                .isEqualTo("40P01");
    }

    @Test
    void messageTextIsNeverParsedToClassifyAFailure() {
        // The human-readable text names the phone constraint, but the structured field is absent.
        var driver = new PSQLException(
                "ERROR: duplicate key value violates unique constraint "
                        + "\"customer_business_phone_unique\" Detail: Key (business_id, phone)=(x, +359) "
                        + "already exists.",
                org.postgresql.util.PSQLState.UNIQUE_VIOLATION);

        assertThat(translate(driver)).isInstanceOf(UnexpectedFailure.class);
    }

    @Test
    void findsTheDriverErrorBehindSpringWrappers() {
        var driver = error("23505", "customer_business_email_unique", null, null);

        assertThat(translate(new DuplicateKeyException("wrapped", driver))).isInstanceOf(DuplicateEmail.class);
        assertThat(translate(new DataIntegrityViolationException("outer", new RuntimeException(driver))))
                .isInstanceOf(DuplicateEmail.class);
    }

    @Test
    void aNonSqlFailureIsAnUnexpectedFailureWithoutAnSqlState() {
        UnexpectedFailure failure = (UnexpectedFailure) translate(new IllegalStateException("secret text"));

        assertThat(failure.sqlState()).isNull();
        assertThat(failure).hasMessage("Customer persistence operation failed");
    }

    @Test
    void aGenericSqlExceptionWithoutDriverFieldsKeepsOnlyItsSqlState() {
        UnexpectedFailure failure = (UnexpectedFailure) translate(new SQLException("boom", "08006"));

        assertThat(failure.sqlState()).isEqualTo("08006");
    }

    @Test
    void aDriverErrorWithoutStructuredServerDataIsUnexpectedKeepingOnlyItsSqlState() {
        // The standard SQLState is present but the driver supplied no ServerErrorMessage.
        var driver = new PSQLException("ERROR: boom", org.postgresql.util.PSQLState.UNIQUE_VIOLATION);

        UnexpectedFailure failure = (UnexpectedFailure) translate(driver);

        assertThat(failure.sqlState()).isEqualTo("23505");
    }

    @Test
    void everyTranslatedFailureHasNoCauseNoSuppressedExceptionAndNoSensitiveText() {
        String sentinel = "+359897000111";
        PSQLException driver = error("23505", "customer_business_phone_unique", "customer", "phone");

        for (CustomerPersistenceException failure : translatedFailures(driver, sentinel)) {
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getMessage()).doesNotContain(sentinel).doesNotContain("customer_business");
            assertThat(failure.toString())
                    .doesNotContain(sentinel)
                    .doesNotContain("secret")
                    .doesNotContain("customer_business")
                    .doesNotContainIgnoringCase("select")
                    .doesNotContainIgnoringCase("insert");
        }
    }

    @Test
    void theOriginalDatabaseExceptionIsNotReachableFromAnyTranslatedFailure() {
        PSQLException driver = error("23505", "customer_business_phone_unique", "customer", "phone");
        var wrapped = new DuplicateKeyException("wrapped", driver);

        for (CustomerPersistenceException failure : translatedFailures(wrapped, "secret")) {
            assertThat(reachableThrowables(failure))
                    .noneMatch(throwable -> throwable instanceof SQLException)
                    .noneMatch(throwable -> throwable == driver || throwable == wrapped);
        }
    }

    @Test
    void anUnexpectedFailureKeepsItsOwnStackTraceWithApplicationFramesOnly() {
        UnexpectedFailure failure = (UnexpectedFailure) translate(error("40001", null, null, null));

        assertThat(failure.getStackTrace()).isNotEmpty();
        assertThat(failure.getStackTrace()[0].getClassName())
                .isEqualTo(CustomerStore.class.getName());
        assertThat(java.util.Arrays.stream(failure.getStackTrace()).map(StackTraceElement::toString))
                .noneMatch(frame -> frame.contains("40001") || frame.contains("secret"));
    }

    private static List<CustomerPersistenceException> translatedFailures(Throwable driver, String secret) {
        return List.of(
                translate(driver),
                translate(error("23503", "customer_business_fk", null, null)),
                translate(error("23514", "customer_phone_canonical", null, null)),
                translate(error("40001", null, null, null)),
                translate(new IllegalStateException(secret)));
    }

    private static List<Throwable> reachableThrowables(Throwable root) {
        List<Throwable> reachable = new ArrayList<>();
        ArrayDeque<Throwable> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            Throwable current = pending.pop();
            if (reachable.contains(current)) {
                continue;
            }
            reachable.add(current);
            if (current.getCause() != null) {
                pending.push(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                pending.push(suppressed);
            }
        }
        return reachable;
    }

    private static CustomerPersistenceException translate(Throwable failure) {
        return CustomerStore.translate(failure);
    }

    private static PSQLException error(String sqlState, String constraint, String table, String column) {
        StringBuilder fields = new StringBuilder();
        fields.append('S').append("ERROR").append('\0');
        fields.append('C').append(sqlState).append('\0');
        fields.append('M').append("fixed driver message with Key (phone)=(+359000000000)").append('\0');
        if (constraint != null) {
            fields.append('n').append(constraint).append('\0');
        }
        if (table != null) {
            fields.append('t').append(table).append('\0');
        }
        if (column != null) {
            fields.append('c').append(column).append('\0');
        }
        fields.append('\0');
        return new PSQLException(new ServerErrorMessage(fields.toString()));
    }
}
