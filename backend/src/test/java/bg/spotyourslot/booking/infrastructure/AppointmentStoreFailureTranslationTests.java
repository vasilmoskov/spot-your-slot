package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.ConcurrentFailure;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.DuplicateAttempt;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.DuplicatePublicReference;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.InvalidData;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.OverlapConflict;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.UnexpectedFailure;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.UnknownReference;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Classification is structural only: the SQLState plus the exact constraint, table, or column name
 * from the driver's structured error fields. Driver exceptions are built directly so every state
 * can be exercised without provoking it through valid domain values.
 */
class AppointmentStoreFailureTranslationTests {
    @Test
    void theOverlapExclusionIsTheTypedOverlapConflict() {
        assertThat(translate(error("23P01", "appointment_staff_no_overlap", null, null)))
                .isInstanceOf(OverlapConflict.class);
    }

    @Test
    void theApprovedUniqueConstraintsAreTheTypedDuplicates() {
        assertThat(translate(error("23505", "appointment_business_public_reference_unique", null, null)))
                .isInstanceOf(DuplicatePublicReference.class);
        assertThat(translate(error("23505", "appointment_business_attempt_hash_unique", null, null)))
                .isInstanceOf(DuplicateAttempt.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "appointment_business_fk", "appointment_customer_fk", "appointment_service_fk",
        "appointment_staff_member_fk"})
    void everyApprovedForeignKeyIsAnUnknownReference(String constraint) {
        assertThat(translate(error("23503", constraint, null, null)))
                .isInstanceOf(UnknownReference.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "appointment_source_valid", "appointment_status_valid", "appointment_instants_finite",
        "appointment_duration_minutes_range", "appointment_end_matches_duration",
        "appointment_occupied_until_equals_end", "appointment_price_nonnegative",
        "appointment_timezone_not_blank", "appointment_service_name_canonical",
        "appointment_staff_display_name_canonical", "appointment_customer_note_plain_text",
        "appointment_public_reference_format", "appointment_attempt_hash_length",
        "appointment_fingerprint_length", "appointment_fingerprint_versions_positive",
        "appointment_idempotency_all_or_none", "appointment_online_requires_idempotency",
        "appointment_version_nonnegative", "appointment_timestamps_finite_ordered",
        // The V13 constraints of the visit and its Service lines.
        "appointment_service_count_range", "appointment_service_position_range",
        "appointment_service_duration_range", "appointment_service_price_nonnegative",
        "appointment_service_line_name_canonical", "appointment_visit_consistent",
        // The V14 cancellation attribution and internal note.
        "appointment_cancellation_consistent", "appointment_cancelled_at_valid",
        "appointment_internal_note_plain_text"})
    void everyApprovedCheckConstraintIsInvalidData(String constraint) {
        assertThat(translate(error("23514", constraint, null, null))).isInstanceOf(InvalidData.class);
    }

    @Test
    void theStoreKnowsExactlyTheChecksAndForeignKeysOfV11V13AndV14() {
        // Nineteen checks and four foreign keys of V11, plus six checks and two foreign keys of V13,
        // plus three checks of V14.
        assertThat(AppointmentStore.CHECK_CONSTRAINTS).hasSize(28);
        assertThat(AppointmentStore.FOREIGN_KEYS).hasSize(6);
        assertThat(AppointmentStore.LINE_UNIQUE_CONSTRAINTS)
                .containsExactlyInAnyOrder("appointment_service_pkey", "appointment_service_distinct");
    }

    @ParameterizedTest
    @ValueSource(strings = {"appointment_service_appointment_fk", "appointment_service_service_fk"})
    void everyLineForeignKeyIsAnUnknownReference(String constraint) {
        assertThat(translate(error("23503", constraint, null, null)))
                .isInstanceOf(UnknownReference.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"appointment_service_pkey", "appointment_service_distinct"})
    void aDuplicateServiceOrPositionInOneVisitIsInvalidDataNotAnOrdinaryConflict(String constraint) {
        assertThat(translate(error("23505", constraint, null, null))).isInstanceOf(InvalidData.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"appointment_id", "position", "business_id", "service_id", "service_name",
        "duration_minutes", "price_eur"})
    void aNotNullViolationOfAServiceLineColumnIsInvalidData(String column) {
        assertThat(translate(error("23502", null, "appointment_service", column)))
                .isInstanceOf(InvalidData.class);
    }

    @Test
    void aNumericOverflowIsInvalidDataWithoutAConstraint() {
        assertThat(translate(error("22003", null, null, null))).isInstanceOf(InvalidData.class);
    }

    @Test
    void theImmutabilityViolationIsAnUnexpectedFailureThatKeepsOnlyItsSqlState() {
        var failure = translate(error("23000", "appointment_service_immutable", "appointment_service", null));

        assertThat(failure).isInstanceOf(UnexpectedFailure.class);
        assertThat(((UnexpectedFailure) failure).sqlState()).isEqualTo("23000");
        assertThat(failure.getMessage()).isEqualTo("Appointment persistence operation failed");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void aLineConstraintNameUnderTheWrongSqlStateOrTableIsNotClassified() {
        assertThat(translate(error("23514", "appointment_service_pkey", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", "appointment_service_line_name_canonical", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23503", "appointment_visit_consistent", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23502", null, "appointment_service", "service_count")))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23502", null, "customer", "position")))
                .isInstanceOf(UnexpectedFailure.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "business_id", "customer_id", "price_eur", "service_name", "updated_at"})
    void aNotNullViolationOfAnAppointmentColumnIsInvalidData(String column) {
        assertThat(translate(error("23502", null, "appointment", column)))
                .isInstanceOf(InvalidData.class);
    }

    @Test
    void aValueTooLongAndADatetimeOverflowAreInvalidData() {
        assertThat(translate(error("22001", null, null, null))).isInstanceOf(InvalidData.class);
        assertThat(translate(error("22008", null, null, null))).isInstanceOf(InvalidData.class);
    }

    @Test
    void serializationFailuresAndDeadlocksAreTheRetryableConcurrentFailure() {
        assertThat(translate(error("40001", null, null, null))).isInstanceOf(ConcurrentFailure.class);
        assertThat(translate(error("40P01", null, null, null))).isInstanceOf(ConcurrentFailure.class);
    }

    @Test
    void anUnknownConstraintTableOrColumnIsNeverAnExpectedFailure() {
        assertThat(translate(error("23505", "appointment_pkey", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", "appointment_business_id_id_unique", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", null, null, null))).isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23P01", "schedule_exception_staff_time_off_no_overlap", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23P01", null, null, null))).isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23503", "customer_business_fk", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23514", "customer_phone_canonical", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23514", null, null, null))).isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23502", null, "customer", "display_name")))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23502", null, "appointment", "unknown_column")))
                .isInstanceOf(UnexpectedFailure.class);
    }

    @Test
    void anApprovedConstraintNameUnderTheWrongSqlStateIsNotClassified() {
        assertThat(translate(error("23514", "appointment_staff_no_overlap", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", "appointment_staff_no_overlap", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23P01", "appointment_business_attempt_hash_unique", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
        assertThat(translate(error("23505", "appointment_customer_fk", null, null)))
                .isInstanceOf(UnexpectedFailure.class);
    }

    @Test
    void messageTextIsNeverParsedToClassifyAFailure() {
        var driver = new PSQLException(
                "ERROR: conflicting key value violates exclusion constraint "
                        + "\"appointment_staff_no_overlap\" Detail: Key (staff_member_id, tstzrange)",
                PSQLState.EXCLUSION_VIOLATION);

        assertThat(translate(driver)).isInstanceOf(UnexpectedFailure.class);
    }

    @Test
    void findsTheDriverErrorBehindSpringWrappers() {
        var driver = error("23P01", "appointment_staff_no_overlap", null, null);

        assertThat(translate(new DuplicateKeyException("wrapped", driver)))
                .isInstanceOf(OverlapConflict.class);
        assertThat(translate(new DataIntegrityViolationException("outer", new RuntimeException(driver))))
                .isInstanceOf(OverlapConflict.class);
    }

    @Test
    void aNonSqlFailureIsUnexpectedWithoutAnSqlState() {
        UnexpectedFailure failure = (UnexpectedFailure) translate(new IllegalStateException("secret text"));

        assertThat(failure.sqlState()).isNull();
        assertThat(failure).hasMessage("Appointment persistence operation failed");
    }

    @Test
    void aGenericSqlExceptionAndADriverErrorWithoutServerDataKeepOnlyTheirSqlState() {
        assertThat(((UnexpectedFailure) translate(new SQLException("boom", "08006"))).sqlState())
                .isEqualTo("08006");
        assertThat(((UnexpectedFailure) translate(
                new PSQLException("ERROR: boom", PSQLState.UNIQUE_VIOLATION))).sqlState())
                .isEqualTo("23505");
    }

    @Test
    void everyTranslatedFailureHasNoCauseNoSuppressedExceptionAndNoSensitiveText() {
        String sentinel = "СЕНТИНЕЛ-бележка-7731";
        PSQLException driver = error("23P01", "appointment_staff_no_overlap", "appointment", "x");

        for (AppointmentPersistenceException failure : translatedFailures(driver, sentinel)) {
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getMessage()).doesNotContain(sentinel).doesNotContain("appointment_");
            assertThat(failure.toString())
                    .doesNotContain(sentinel)
                    .doesNotContain("secret")
                    .doesNotContain("appointment_")
                    .doesNotContain("staff_member_id")
                    .doesNotContainIgnoringCase("select")
                    .doesNotContainIgnoringCase("insert");
        }
    }

    @Test
    void theOriginalDatabaseExceptionIsNotReachableFromAnyTranslatedFailure() {
        PSQLException driver = error("23P01", "appointment_staff_no_overlap", "appointment", "x");
        var wrapped = new DuplicateKeyException("wrapped", driver);

        for (AppointmentPersistenceException failure : translatedFailures(wrapped, "secret")) {
            assertThat(reachableThrowables(failure))
                    .noneMatch(throwable -> throwable instanceof SQLException)
                    .noneMatch(throwable -> throwable == driver || throwable == wrapped);
        }
    }

    @Test
    void anUnexpectedFailureKeepsItsOwnApplicationStackTraceOnly() {
        UnexpectedFailure failure = (UnexpectedFailure) translate(error("57014", null, null, null));

        assertThat(failure.getStackTrace()).isNotEmpty();
        assertThat(failure.getStackTrace()[0].getClassName()).isEqualTo(AppointmentStore.class.getName());
        assertThat(java.util.Arrays.stream(failure.getStackTrace()).map(StackTraceElement::toString))
                .noneMatch(frame -> frame.contains("57014") || frame.contains("secret"));
    }

    private static List<AppointmentPersistenceException> translatedFailures(
            Throwable driver, String secret) {
        return List.of(
                translate(driver),
                translate(error("23503", "appointment_customer_fk", null, null)),
                translate(error("23514", "appointment_customer_note_plain_text", null, null)),
                translate(error("23505", "appointment_business_attempt_hash_unique", null, null)),
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

    private static AppointmentPersistenceException translate(Throwable failure) {
        return AppointmentStore.translate(failure);
    }

    private static PSQLException error(String sqlState, String constraint, String table, String column) {
        StringBuilder fields = new StringBuilder();
        fields.append('S').append("ERROR").append('\0');
        fields.append('C').append(sqlState).append('\0');
        fields.append('M').append("fixed driver message with Key (note)=(СЕНТИНЕЛ-бележка-7731)").append('\0');
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
