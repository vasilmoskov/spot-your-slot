package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code V13} Service-line schema against real PostgreSQL (ADR-0027, phase M1): the exact
 * structure, the deferred whole-visit consistency rule, every line constraint, the maximum visit
 * total, the immutability of lines, same-Business isolation, overlap protection over the whole visit
 * (with adjacency and cancellation), and the rollback of an invalid visit. All values are synthetic.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentServiceSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String RESTRICT_VIOLATION = "23001";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String NUMERIC_OUT_OF_RANGE = "22003";
    private static final String INTEGRITY_VIOLATION = "23000";
    private static final String VISIT_RULE = "appointment_visit_consistent";
    private static final String MAX_SERVICE_PRICE = "9999999999.99";
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
    }

    // ---- structure -----------------------------------------------------------

    @Test
    void theLineTableHasExactlyTheApprovedColumnsConstraintsIndexAndTriggers() {
        assertThat(jdbc.sql("""
                        SELECT column_name || '|' || data_type || '|' || is_nullable || '|'
                               || coalesce(character_maximum_length::text, '') || '|'
                               || coalesce(numeric_precision::text, '') || ','
                               || coalesce(numeric_scale::text, '')
                        FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = 'appointment_service'
                        ORDER BY ordinal_position
                        """).query(String.class).list()).containsExactly(
                "appointment_id|uuid|NO||,",
                "position|smallint|NO||16,0",
                "business_id|uuid|NO||,",
                "service_id|uuid|NO||,",
                "service_name|character varying|NO|200|,",
                "duration_minutes|integer|NO||32,0",
                "price_eur|numeric|NO||12,2");

        Map<String, String> constraints = jdbc.sql("""
                        SELECT conname, contype::text AS kind
                        FROM pg_constraint
                        WHERE conrelid = 'appointment_service'::regclass AND contype <> 'n'
                        """)
                .query((resultSet, row) -> Map.entry(
                        resultSet.getString("conname"), resultSet.getString("kind")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(constraints).containsOnlyKeys(
                "appointment_service_pkey", "appointment_service_appointment_fk",
                "appointment_service_service_fk", "appointment_service_position_range",
                "appointment_service_distinct", "appointment_service_duration_range",
                "appointment_service_price_nonnegative", "appointment_service_line_name_canonical",
                "appointment_visit_consistent_on_line");
        assertThat(constraints.get("appointment_service_pkey")).isEqualTo("p");
        assertThat(constraints.get("appointment_service_distinct")).isEqualTo("u");

        // Both foreign keys are composite, same-Business, and restrictive.
        assertThat(jdbc.sql("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conrelid = 'appointment_service'::regclass AND contype = 'f'
                        ORDER BY conname
                        """).query(String.class).list()).containsExactly(
                "FOREIGN KEY (business_id, appointment_id) REFERENCES appointment(business_id, id) ON DELETE RESTRICT",
                "FOREIGN KEY (business_id, service_id) REFERENCES service(business_id, id) ON DELETE RESTRICT");

        assertThat(jdbc.sql("""
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = current_schema() AND tablename = 'appointment_service'
                        """).query(String.class).list()).containsExactlyInAnyOrder(
                "appointment_service_pkey", "appointment_service_distinct",
                "appointment_service_service_idx");

        assertThat(jdbc.sql("""
                        SELECT t.tgname || '|' || t.tgdeferrable::text || '|' || t.tginitdeferred::text
                               || '|' || (t.tgconstraint <> 0)::text
                        FROM pg_trigger t
                        WHERE t.tgrelid = 'appointment_service'::regclass AND NOT t.tgisinternal
                        """).query(String.class).list()).containsExactlyInAnyOrder(
                "appointment_service_immutable|false|false|false",
                "appointment_visit_consistent_on_line|true|true|true");
        assertThat(jdbc.sql("""
                        SELECT t.tgname || '|' || t.tgdeferrable::text || '|' || t.tginitdeferred::text
                        FROM pg_trigger t
                        WHERE t.tgrelid = 'appointment'::regclass AND NOT t.tgisinternal
                          AND t.tgname LIKE 'appointment_visit%'
                        """).query(String.class).list()).containsExactly(
                "appointment_visit_consistent_on_appointment|true|true");

        assertThat(jdbc.sql("""
                        SELECT column_default || '|' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = 'appointment'
                          AND column_name = 'service_count'
                        """).query(String.class).single()).isEqualTo("1|NO");
    }

    // ---- the deferred whole-visit rule ---------------------------------------

    @Test
    void aConsistentMultiServiceVisitCommitsWithItsLinesInPositionOrder() {
        List<Line> lines = lines(3, "10.00", 20);
        UUID visit = commitVisit(START, lines);

        assertThat(count("appointment")).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT position FROM appointment_service
                        WHERE appointment_id = :id ORDER BY position
                        """).param("id", visit).query(Integer.class).list()).containsExactly(1, 2, 3);
        assertThat(jdbc.sql("""
                        SELECT service_count || '|' || duration_minutes || '|' || price_eur
                        FROM appointment WHERE id = :id
                        """).param("id", visit).query(String.class).single()).isEqualTo("3|60|30.00");
    }

    @Test
    void theRuleIsDeferredSoStatementsSucceedAndOnlyTheCommitJudgesTheVisit() {
        Map<String, Object> row = visitRow(START, lines(1, "10.00", 30), 30, "10.00", 1);
        UUID id = (UUID) row.get("id");
        Throwable failure = catchThrowable(() -> transaction().executeWithoutResult(status -> {
            fixtures.insertRowWithoutLines(row);
            // Inside the transaction the Appointment exists without a line; nothing has failed yet.
            assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE id = :id")
                    .param("id", id).query(Long.class).single()).isEqualTo(1L);
        }));

        Violation violation = violation(failure);
        assertThat(violation.sqlState()).isEqualTo(CHECK_VIOLATION);
        assertThat(violation.constraint()).isEqualTo(VISIT_RULE);
        assertThat(count("appointment")).isZero();
    }

    @Test
    void anAppointmentWithoutAnyLineIsRefusedAtCommit() {
        Violation violation = violation(catchThrowable(() ->
                fixtures.insertRowWithoutLines(AppointmentFixtures.row(tenant, START, 30))));

        assertThat(violation.constraint()).isEqualTo(VISIT_RULE);
        assertThat(count("appointment")).isZero();
        assertThat(count("appointment_service")).isZero();
    }

    static List<Object[]> inconsistentVisits() {
        return List.of(
                new Object[] {"fewer lines than service_count", 2, 60, "20.00", List.of(1)},
                new Object[] {"more lines than service_count", 1, 60, "20.00", List.of(1, 2)},
                new Object[] {"positions skip a number", 2, 60, "20.00", List.of(1, 3)},
                new Object[] {"positions do not start at one", 2, 60, "20.00", List.of(2, 3)},
                new Object[] {"duration differs from the sum", 2, 61, "20.00", List.of(1, 2)},
                new Object[] {"price is one cent more than the sum", 2, 60, "20.01", List.of(1, 2)},
                new Object[] {"price is one cent less than the sum", 2, 60, "19.99", List.of(1, 2)});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentVisits")
    void aVisitThatViolatesTheWholeVisitRuleIsRefusedAtCommitAndLeavesNothing(
            String label, int serviceCount, int totalMinutes, String totalPrice, List<Integer> positions) {
        List<Line> all = lines(3, "10.00", 30);
        List<Line> chosen = new ArrayList<>();
        for (int position : positions) {
            Line template = all.get(position - 1);
            chosen.add(new Line(position, template.service(), template.name(), 30, "10.00"));
        }
        Map<String, Object> row = visitRow(START, all.subList(0, 1), totalMinutes, totalPrice, serviceCount);
        // The headline copies the first line that exists, so only the stated rule is broken.
        row.put("service_id", chosen.get(0).service());
        row.put("service_name", chosen.get(0).name());

        Violation violation = violation(catchThrowable(() -> commit(row, chosen)));

        assertThat(violation.sqlState()).as(label).isEqualTo(CHECK_VIOLATION);
        assertThat(violation.constraint()).as(label).isEqualTo(VISIT_RULE);
        assertThat(count("appointment")).isZero();
        assertThat(count("appointment_service")).isZero();
    }

    @Test
    void theHeadlineMustEqualLineOne() {
        List<Line> lines = lines(2, "10.00", 30);
        Map<String, Object> wrongService = visitRow(START, lines, 60, "20.00", 2);
        wrongService.put("service_id", lines.get(1).service());
        Map<String, Object> wrongName = visitRow(START, lines, 60, "20.00", 2);
        wrongName.put("service_name", "Друго име");

        assertThat(violation(catchThrowable(() -> commit(wrongService, lines))).constraint())
                .isEqualTo(VISIT_RULE);
        assertThat(violation(catchThrowable(() -> commit(wrongName, lines))).constraint())
                .isEqualTo(VISIT_RULE);
        assertThat(count("appointment")).isZero();
    }

    @Test
    void changingTheTotalsOfACommittedVisitWithoutItsLinesIsRefusedButAStatusChangeIsNot() {
        UUID visit = commitVisit(START, lines(2, "10.00", 30));

        Violation price = violation(catchThrowable(() ->
                jdbc.sql("UPDATE appointment SET price_eur = 20.01 WHERE id = :id")
                        .param("id", visit).update()));
        assertThat(price.constraint()).isEqualTo(VISIT_RULE);

        jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE id = :id").param("id", visit).update();
        assertThat(jdbc.sql("SELECT status FROM appointment WHERE id = :id")
                .param("id", visit).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT price_eur::text FROM appointment WHERE id = :id")
                .param("id", visit).query(String.class).single()).isEqualTo("20.00");
    }

    // ---- maximum aggregate price ---------------------------------------------

    @Test
    void fiveServicesOfTheLargestPriceCommitWithTheExactMaximumTotal() {
        UUID visit = commitVisit(START, lines(5, MAX_SERVICE_PRICE, 30));

        assertThat(jdbc.sql("SELECT price_eur::text FROM appointment WHERE id = :id")
                .param("id", visit).query(String.class).single()).isEqualTo("49999999999.95");
        assertThat(jdbc.sql("SELECT sum(price_eur)::text FROM appointment_service WHERE appointment_id = :id")
                .param("id", visit).query(String.class).single()).isEqualTo("49999999999.95");
    }

    @Test
    void theMaximumTotalOffByOneCentIsRefusedAtCommit() {
        List<Line> lines = lines(5, MAX_SERVICE_PRICE, 30);
        Map<String, Object> row = visitRow(START, lines, 150, "49999999999.96", 5);

        assertThat(violation(catchThrowable(() -> commit(row, lines))).constraint()).isEqualTo(VISIT_RULE);
        assertThat(count("appointment")).isZero();
    }

    @Test
    void aTotalAboveTheColumnCapacityIsRefusedByTheDatabase() {
        Map<String, Object> row = visitRow(START, lines(1, "10.00", 30), 30, "100000000000.00", 1);

        assertThat(violation(catchThrowable(() -> fixtures.insertRowWithoutLines(row))).sqlState())
                .isEqualTo(NUMERIC_OUT_OF_RANGE);
    }

    @Test
    void aSingleLinePriceStaysLimitedToTenIntegerDigits() {
        List<Line> lines = List.of(line(1, "10000000000.00", 30));
        Map<String, Object> row = visitRow(START, lines, 30, "10000000000.00", 1);

        assertThat(violation(catchThrowable(() -> commit(row, lines))).sqlState())
                .isEqualTo(NUMERIC_OUT_OF_RANGE);
        assertThat(count("appointment")).isZero();
    }

    // ---- line constraints ----------------------------------------------------

    @Test
    void aServiceCannotAppearTwiceInOneVisit() {
        Line first = line(1, "10.00", 30);
        Line again = new Line(2, first.service(), first.name(), 30, "10.00");
        Map<String, Object> row = visitRow(START, List.of(first, again), 60, "20.00", 2);

        Violation violation = violation(catchThrowable(() -> commit(row, List.of(first, again))));

        assertThat(violation.sqlState()).isEqualTo(UNIQUE_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_service_distinct");
        assertThat(count("appointment")).isZero();
    }

    @Test
    void twoLinesCannotShareAPosition() {
        Line first = line(1, "10.00", 30);
        Line other = new Line(1, line(1, "10.00", 30).service(), "Друга услуга", 30, "10.00");
        Map<String, Object> row = visitRow(START, List.of(first, other), 60, "20.00", 2);

        Violation violation = violation(catchThrowable(() -> commit(row, List.of(first, other))));

        assertThat(violation.sqlState()).isEqualTo(UNIQUE_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_service_pkey");
    }

    static List<Object[]> badLines() {
        return List.of(
                new Object[] {"position 0", 0, "Подстригване", 30, "10.00", "appointment_service_position_range"},
                new Object[] {"position 6", 6, "Подстригване", 30, "10.00", "appointment_service_position_range"},
                new Object[] {"duration 0", 1, "Подстригване", 0, "10.00", "appointment_service_duration_range"},
                new Object[] {"duration 481", 1, "Подстригване", 481, "10.00", "appointment_service_duration_range"},
                new Object[] {"negative price", 1, "Подстригване", 30, "-0.01", "appointment_service_price_nonnegative"},
                new Object[] {"blank name", 1, "", 30, "10.00", "appointment_service_line_name_canonical"},
                new Object[] {"edge space", 1, " Подстригване", 30, "10.00", "appointment_service_line_name_canonical"},
                new Object[] {"double space", 1, "Двойни  интервали", 30, "10.00", "appointment_service_line_name_canonical"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badLines")
    void everyLineConstraintRejectsItsViolationAndLeavesNothing(
            String label, int position, String name, int minutes, String price, String constraint) {
        UUID service = fixtures.service(tenant.business(), 30);
        Map<String, Object> row = visitRow(START, List.of(line(1, "10.00", 30)), 30, "10.00", 1);
        UUID visit = (UUID) row.get("id");

        Violation violation = violation(catchThrowable(() -> transaction().executeWithoutResult(status -> {
            fixtures.insertRowWithoutLines(row);
            insertLine(visit, tenant.business(), position, service, name, minutes, price);
        })));

        assertThat(violation.constraint()).as(label).isEqualTo(constraint);
        assertThat(count("appointment")).isZero();
        assertThat(count("appointment_service")).isZero();
    }

    // ---- tenant isolation ----------------------------------------------------

    @Test
    void aLineCannotReferenceAServiceOfAnotherBusiness() {
        Tenant other = fixtures.tenant();
        Map<String, Object> row = visitRow(START, List.of(line(1, "10.00", 30)), 30, "10.00", 1);
        UUID visit = (UUID) row.get("id");

        Violation violation = violation(catchThrowable(() -> transaction().executeWithoutResult(status -> {
            fixtures.insertRowWithoutLines(row);
            insertLine(visit, tenant.business(), 1, other.service(), "Подстригване", 30, "10.00");
        })));

        assertThat(violation.sqlState()).isEqualTo(FOREIGN_KEY_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_service_service_fk");
        assertThat(count("appointment")).isZero();
    }

    @Test
    void aLineCannotBelongToAnotherBusinessThanItsAppointment() {
        Tenant other = fixtures.tenant();
        Map<String, Object> row = visitRow(START, List.of(line(1, "10.00", 30)), 30, "10.00", 1);
        UUID visit = (UUID) row.get("id");

        Violation violation = violation(catchThrowable(() -> transaction().executeWithoutResult(status -> {
            fixtures.insertRowWithoutLines(row);
            insertLine(visit, other.business(), 1, other.service(), "Подстригване", 30, "10.00");
        })));

        assertThat(violation.sqlState()).isEqualTo(FOREIGN_KEY_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_service_appointment_fk");
    }

    // ---- immutability --------------------------------------------------------

    @Test
    void aLineCanNeitherBeUpdatedNorDeletedAndAnAppointmentWithLinesCannotBeDeleted() {
        UUID visit = commitVisit(START, lines(2, "10.00", 30));

        Violation update = violation(catchThrowable(() ->
                jdbc.sql("UPDATE appointment_service SET price_eur = 1.00 WHERE appointment_id = :id")
                        .param("id", visit).update()));
        Violation delete = violation(catchThrowable(() ->
                jdbc.sql("DELETE FROM appointment_service WHERE appointment_id = :id")
                        .param("id", visit).update()));
        Violation parent = violation(catchThrowable(() ->
                jdbc.sql("DELETE FROM appointment WHERE id = :id").param("id", visit).update()));

        assertThat(update.sqlState()).isEqualTo(INTEGRITY_VIOLATION);
        assertThat(update.constraint()).isEqualTo("appointment_service_immutable");
        assertThat(delete.sqlState()).isEqualTo(INTEGRITY_VIOLATION);
        assertThat(delete.constraint()).isEqualTo("appointment_service_immutable");
        assertThat(parent.sqlState()).isEqualTo(RESTRICT_VIOLATION);
        assertThat(count("appointment_service")).isEqualTo(2L);
    }

    // ---- whole-visit overlap, adjacency, cancellation, rollback --------------

    @Test
    void theExistingExclusionProtectsTheWholeVisitInterval() {
        // A 90-minute visit of three Services at 09:00 occupies [09:00, 10:30).
        UUID visit = commitVisit(START, lines(3, "10.00", 30));

        // A booking that starts inside the later part of the visit is refused although it clears the
        // first Service.
        Violation overlap = violation(catchThrowable(() ->
                fixtures.insertRow(AppointmentFixtures.row(tenant, START.plusSeconds(75 * 60L), 30))));
        assertThat(overlap.sqlState()).isEqualTo(EXCLUSION_VIOLATION);
        assertThat(overlap.constraint()).isEqualTo("appointment_staff_no_overlap");

        // Adjacent intervals are allowed on both sides (half-open bounds).
        fixtures.insertRow(AppointmentFixtures.row(tenant, START.plusSeconds(90 * 60L), 30));
        fixtures.insertRow(AppointmentFixtures.row(tenant, START.minusSeconds(30 * 60L), 30));
        assertThat(count("appointment")).isEqualTo(3L);

        // A second visit that overlaps the first is refused as a whole, with all of its lines.
        long linesBefore = count("appointment_service");
        List<Line> overlapping = lines(2, "10.00", 30);
        Violation second = violation(catchThrowable(() ->
                commit(visitRow(START.plusSeconds(15 * 60L), overlapping, 60, "20.00", 2), overlapping)));
        assertThat(second.constraint()).isEqualTo("appointment_staff_no_overlap");
        assertThat(count("appointment_service")).isEqualTo(linesBefore);

        // A cancelled visit stops blocking the time.
        jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE id = :id").param("id", visit).update();
        List<Line> reuse = lines(2, "10.00", 30);
        commit(visitRow(START.plusSeconds(15 * 60L), reuse, 60, "20.00", 2), reuse);
        assertThat(count("appointment")).isEqualTo(4L);
    }

    @Test
    void anotherStaffMemberIsNotBlockedByTheVisit() {
        commitVisit(START, lines(3, "10.00", 30));
        UUID otherStaff = fixtures.staffMember(tenant.business(), tenant.service());
        Tenant forOther = new Tenant(tenant.business(), tenant.service(), otherStaff, tenant.customer());

        fixtures.insertRow(AppointmentFixtures.row(forOther, START, 30));

        assertThat(count("appointment")).isEqualTo(2L);
    }

    @Test
    void aFailureAfterTheVisitAndItsLinesWereWrittenRollsBackEverything() {
        List<Line> lines = lines(3, "10.00", 30);
        Map<String, Object> row = visitRow(START, lines, 90, "30.00", 3);

        Throwable failure = catchThrowable(() -> transaction().executeWithoutResult(status -> {
            fixtures.insertRowWithoutLines(row);
            for (Line line : lines) {
                insertLine((UUID) row.get("id"), tenant.business(), line.position(), line.service(),
                        line.name(), line.minutes(), line.price());
            }
            throw new IllegalStateException("simulated failure after the lines were written");
        }));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(count("appointment")).isZero();
        assertThat(count("appointment_service")).isZero();
    }

    // ---- helpers -------------------------------------------------------------

    private record Line(int position, UUID service, String name, int minutes, String price) {
    }

    private Line line(int position, String price, int minutes) {
        return new Line(position, fixtures.service(tenant.business(), minutes),
                "Услуга " + position, minutes, price);
    }

    private List<Line> lines(int count, String price, int minutes) {
        List<Line> lines = new ArrayList<>();
        for (int position = 1; position <= count; position++) {
            lines.add(line(position, price, minutes));
        }
        return lines;
    }

    /** A MANUAL, CONFIRMED visit row whose headline is the first line and whose totals are given. */
    private Map<String, Object> visitRow(
            Instant start, List<Line> lines, int totalMinutes, String totalPrice, int serviceCount) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, start, totalMinutes);
        row.put("service_id", lines.get(0).service());
        row.put("service_name", lines.get(0).name());
        row.put("price_eur", new BigDecimal(totalPrice));
        row.put("service_count", serviceCount);
        return row;
    }

    private UUID commitVisit(Instant start, List<Line> lines) {
        int minutes = lines.stream().mapToInt(Line::minutes).sum();
        BigDecimal price = lines.stream().map(line -> new BigDecimal(line.price()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> row = visitRow(start, lines, minutes, price.toPlainString(), lines.size());
        commit(row, lines);
        return (UUID) row.get("id");
    }

    /** Writes the visit row and its lines in one transaction; the deferred rule is judged at commit. */
    private void commit(Map<String, Object> row, List<Line> lines) {
        transaction().executeWithoutResult(status -> {
            fixtures.insertRowWithoutLines(row);
            for (Line line : lines) {
                insertLine((UUID) row.get("id"), tenant.business(), line.position(), line.service(),
                        line.name(), line.minutes(), line.price());
            }
        });
    }

    private void insertLine(
            UUID appointment, UUID business, int position, UUID service, String name, int minutes,
            String price) {
        jdbc.sql("""
                        INSERT INTO appointment_service(
                            appointment_id, position, business_id, service_id, service_name,
                            duration_minutes, price_eur)
                        VALUES (:appointment, :position, :business, :service, :name, :minutes,
                                CAST(:price AS numeric))
                        """)
                .param("appointment", appointment)
                .param("position", (short) position, java.sql.Types.SMALLINT)
                .param("business", business)
                .param("service", service)
                .param("name", name)
                .param("minutes", minutes)
                .param("price", price)
                .update();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private record Violation(String sqlState, String constraint) {
    }

    private static Violation violation(Throwable failure) {
        assertThat(failure).as("the operation must fail").isNotNull();
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
