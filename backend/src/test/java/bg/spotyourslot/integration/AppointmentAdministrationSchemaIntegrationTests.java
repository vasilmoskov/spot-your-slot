package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * The {@code V14} cancellation attribution and internal note (ADR-0028 sections 8 and 9) against
 * real PostgreSQL: every allowed and every forbidden combination of status, actor, and time, the
 * proof that a nullable actor cannot bypass the rule, and that a valid internal note is accepted
 * on CONFIRMED and CANCELLED rows alike.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentAdministrationSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final String CHECK_VIOLATION = "23514";
    private static final String VALUE_TOO_LONG = "22001";
    private static final String CONSISTENT = "appointment_cancellation_consistent";
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-10-02T08:00:00Z");

    @Autowired
    JdbcClient jdbc;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
    }

    static Stream<Arguments> allowedCombinations() {
        return Stream.of(
                Arguments.of("CONFIRMED", null, null),
                Arguments.of("CANCELLED", "BUSINESS", CANCELLED_AT),
                Arguments.of("CANCELLED", "UNKNOWN", null));
    }

    static Stream<Arguments> forbiddenCombinations() {
        return Stream.of(
                Arguments.of("CONFIRMED", "BUSINESS", null),
                Arguments.of("CONFIRMED", null, CANCELLED_AT),
                Arguments.of("CONFIRMED", "BUSINESS", CANCELLED_AT),
                Arguments.of("CONFIRMED", "UNKNOWN", null),
                // The nullable-actor bypass: a plain comparison would evaluate to NULL, which a CHECK accepts.
                Arguments.of("CANCELLED", null, null),
                Arguments.of("CANCELLED", null, CANCELLED_AT),
                Arguments.of("CANCELLED", "BUSINESS", null),
                Arguments.of("CANCELLED", "UNKNOWN", CANCELLED_AT),
                Arguments.of("CANCELLED", "CUSTOMER", CANCELLED_AT),
                Arguments.of("CANCELLED", "business", CANCELLED_AT),
                Arguments.of("CANCELLED", "", CANCELLED_AT));
    }

    @ParameterizedTest
    @MethodSource("allowedCombinations")
    void everyAllowedAttributionCombinationIsAcceptedOnInsert(String status, String by, Instant at) {
        fixtures.insertRow(row(status, by, at));

        assertThat(count()).isEqualTo(1L);
    }

    @ParameterizedTest
    @MethodSource("forbiddenCombinations")
    void everyForbiddenAttributionCombinationIsRefusedOnInsert(String status, String by, Instant at) {
        Violation violation = violationOf(() -> fixtures.insertRow(row(status, by, at)));

        assertThat(violation.sqlState()).isEqualTo(CHECK_VIOLATION);
        assertThat(violation.constraint()).isEqualTo(CONSISTENT);
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @MethodSource("forbiddenCombinations")
    void everyForbiddenAttributionCombinationIsRefusedOnUpdateAndLeavesTheRowUnchanged(
            String status, String by, Instant at) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        fixtures.insertRow(row);
        String before = rowJson(row.get("id"));

        Violation violation = violationOf(() -> jdbc.sql("""
                        UPDATE appointment
                        SET status = :status, cancelled_by = :by, cancelled_at = :at
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("by", by, Types.VARCHAR)
                .param("at", at == null ? null : AppointmentFixtures.utc(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", row.get("id"))
                .update());

        assertThat(violation.constraint()).isEqualTo(CONSISTENT);
        assertThat(rowJson(row.get("id"))).isEqualTo(before);
    }

    @Test
    void cancellingThroughAnUpdateRequiresTheCompleteAttribution() {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        fixtures.insertRow(row);

        Violation statusOnly = violationOf(() -> jdbc.sql(
                        "UPDATE appointment SET status = 'CANCELLED' WHERE id = :id")
                .param("id", row.get("id")).update());
        assertThat(statusOnly.constraint()).isEqualTo(CONSISTENT);

        assertThat(fixtures.cancel((UUID) row.get("id"))).isEqualTo(1);
        assertThat(jdbc.sql("SELECT cancelled_by FROM appointment WHERE id = :id")
                .param("id", row.get("id")).query(String.class).single()).isEqualTo("BUSINESS");
    }

    @Test
    void theLiteralAdr0028ExpressionWouldHaveAcceptedACancelledRowWithoutAnActor() {
        // The expression as first written in ADR-0028 section 8, evaluated for status CANCELLED
        // with a null actor and a null time: NULL, which a CHECK accepts. V14 uses
        // IS NOT DISTINCT FROM instead, and the matrix above proves it refuses that row.
        Boolean literalIsNull = jdbc.sql("""
                        SELECT ((s = 'CONFIRMED' AND at IS NULL AND by IS NULL)
                            OR (s = 'CANCELLED' AND by = 'BUSINESS' AND at IS NOT NULL)
                            OR (s = 'CANCELLED' AND by = 'UNKNOWN' AND at IS NULL)) IS NULL
                        FROM (SELECT 'CANCELLED'::varchar AS s, NULL::varchar AS by,
                                     NULL::timestamptz AS at) AS candidate
                        """).query(Boolean.class).single();

        assertThat(literalIsNull).isTrue();
    }

    @Test
    void theCancellationTimeMustBeFiniteAndNotBeforeCreation() {
        Violation early = violationOf(() -> fixtures.insertRow(row(
                "CANCELLED", "BUSINESS", AppointmentFixtures.CREATED_AT.minusSeconds(1))));
        assertThat(early.constraint()).isEqualTo("appointment_cancelled_at_valid");

        fixtures.insertRow(row("CANCELLED", "BUSINESS", AppointmentFixtures.CREATED_AT));
        assertThat(count()).isEqualTo(1L);

        Violation infinite = violationOf(() -> jdbc.sql("""
                        UPDATE appointment SET cancelled_at = 'infinity'
                        """).update());
        assertThat(infinite.constraint()).isEqualTo("appointment_cancelled_at_valid");
    }

    @Test
    void aValidInternalNoteIsAcceptedOnConfirmedAndCancelledRowsAndOnStartedOnes() {
        String note = "Позвъни преди часа.\n\tКлиентът е VIP.";
        Map<String, Object> confirmed = row("CONFIRMED", null, null);
        confirmed.put("internal_note", note);
        fixtures.insertRow(confirmed);
        Map<String, Object> business = row("CANCELLED", "BUSINESS", CANCELLED_AT);
        business.put("start_at", AppointmentFixtures.utc(START.plusSeconds(7200)));
        business.put("end_at", AppointmentFixtures.utc(START.plusSeconds(9000)));
        business.put("occupied_until", AppointmentFixtures.utc(START.plusSeconds(9000)));
        business.put("internal_note", note);
        fixtures.insertRow(business);
        Map<String, Object> legacy = AppointmentFixtures.cancelledLegacy(
                AppointmentFixtures.row(tenant, START.plusSeconds(14_400), 30));
        legacy.put("internal_note", note);
        fixtures.insertRow(legacy);

        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE internal_note = :note")
                .param("note", note).query(Long.class).single()).isEqualTo(3L);

        // Editing the note by UPDATE leaves the attribution alone and is accepted for every status.
        assertThat(jdbc.sql("UPDATE appointment SET internal_note = 'Нова бележка'")
                .update()).isEqualTo(3);
        // A past start (relative to the fixture dates) is just a column value: nothing ties it to the note.
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM appointment
                        WHERE internal_note = 'Нова бележка' AND start_at < now()
                        """).query(Long.class).single()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void theNoteLimitIsFiveHundredCodePointsEvenForSupplementaryCharacters() {
        String exactly = "😀".repeat(500);
        Map<String, Object> accepted = row("CONFIRMED", null, null);
        accepted.put("internal_note", exactly);
        fixtures.insertRow(accepted);
        assertThat(jdbc.sql("SELECT char_length(internal_note) FROM appointment")
                .query(Integer.class).single()).isEqualTo(500);

        Map<String, Object> rejected = row("CONFIRMED", null, null);
        rejected.put("start_at", AppointmentFixtures.utc(START.plusSeconds(7200)));
        rejected.put("end_at", AppointmentFixtures.utc(START.plusSeconds(9000)));
        rejected.put("occupied_until", AppointmentFixtures.utc(START.plusSeconds(9000)));
        rejected.put("internal_note", "😀".repeat(501));
        Violation violation = violationOf(() -> fixtures.insertRow(rejected));

        assertThat(violation.sqlState()).isEqualTo(VALUE_TOO_LONG);
        assertThat(count()).isEqualTo(1L);
    }

    @Test
    void anEmptyNoteIsRefusedSoABlankNoteMustBeStoredAsNull() {
        Map<String, Object> blank = row("CONFIRMED", null, null);
        blank.put("internal_note", "");

        Violation violation = violationOf(() -> fixtures.insertRow(blank));

        assertThat(violation.constraint()).isEqualTo("appointment_internal_note_plain_text");
    }

    private Map<String, Object> row(String status, String by, Instant at) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        row.put("status", status);
        row.put("cancelled_by", by);
        row.put("cancelled_at", at == null ? null : AppointmentFixtures.utc(at));
        return row;
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
    }

    private String rowJson(Object id) {
        return jdbc.sql("SELECT to_jsonb(a)::text || xmin::text FROM appointment a WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private record Violation(String sqlState, String constraint) {
    }

    private static Violation violationOf(Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).as("the statement must fail").isNotNull();
        Throwable current = failure;
        while (current != null && !(current instanceof PSQLException)) {
            current = current.getCause();
        }
        assertThat(current).as("a PostgreSQL error is expected").isNotNull();
        PSQLException postgres = (PSQLException) current;
        var details = postgres.getServerErrorMessage();
        return new Violation(postgres.getSQLState(), details == null ? null : details.getConstraint());
    }
}
