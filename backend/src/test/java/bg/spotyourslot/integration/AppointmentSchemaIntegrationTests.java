package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

/**
 * The {@code V11} Appointment schema against real PostgreSQL: migration integrity, exact columns
 * and nullability, every CHECK and foreign key, uniqueness, snapshots, and the overlap exclusion
 * (including DST instants). All values are synthetic.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String RESTRICT_VIOLATION = "23001";
    private static final String VALUE_TOO_LONG = "22001";

    private static final List<String> PRIOR_MIGRATION_SHA_256 = List.of(
            "68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49",
            "c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8",
            "655d22a52c06eb41996a75c100c1bfab853c907b9e0e076f128ba2ab8be674a2",
            "aa48255701e3ce6999073801ca0ed37e292b9ada545e3fe4a50221ada596cb98",
            "e2221627ed52ceb951881648d9738d213544ad4c8d79aa35e1e79c0896e958d3",
            "73e89120c0163d6ea79ee28f0d59ae02c055df85066234a3688f525ada16ff1b",
            "d9a8184b7c7c856064426d79dd375e7b4fb7f55a1fec389b7c391512286339b2",
            "2f9fb21a06d4f42e5a3f9b4f479a2bbae2470e4fad0800247aed816d624668e8",
            "9f0560e4daeb139eafe611b4e890a8505ca02ccffa30a293fef24a2a2fd182dd",
            "a80c976b011bdfffc0948d252cf158ce9b75d8c5341b5dc31c36c94a94c3cfaa");
    private static final List<String> PRIOR_MIGRATION_FILES = List.of(
            "V1__identity_and_tenancy.sql",
            "V2__enforce_single_active_identity_tokens.sql",
            "V3__add_business_profile_fields.sql",
            "V4__structure_business_address.sql",
            "V5__add_business_services.sql",
            "V6__add_staff_members_and_service_assignments.sql",
            "V7__add_recurring_staff_working_schedules.sql",
            "V8__canonicalize_staff_member_contact_phone.sql",
            "V9__add_schedule_exceptions.sql",
            "V10__add_customers.sql");
    private static final String V11_SHA_256 =
            "3135ee8ffc34df9f5a10ce85170a160e5dfc1c4579c1c1aa1f118318783bbb0f";

    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");

    @Autowired
    JdbcClient jdbc;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
    }

    // ---- migration integrity -------------------------------------------------

    @Test
    void migrationsFromEmptyAreExactlyV1ThroughV11WithV11Newest() {
        List<String> versions = jdbc.sql("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE type = 'SQL' AND success = true
                        ORDER BY installed_rank
                        """)
                .query(String.class)
                .list();

        assertThat(versions)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
    }

    @Test
    void v1ThroughV10RemainByteForByteUnchangedAndV11IsPinned() {
        for (int index = 0; index < PRIOR_MIGRATION_FILES.size(); index++) {
            assertThat(sha256(PRIOR_MIGRATION_FILES.get(index)))
                    .as(PRIOR_MIGRATION_FILES.get(index))
                    .isEqualTo(PRIOR_MIGRATION_SHA_256.get(index));
        }
        assertThat(sha256("V11__add_appointments.sql")).isEqualTo(V11_SHA_256);
    }

    @Test
    void upgradingV10PreservesExistingDataAndAddsAnEmptyUsableAppointmentTable() {
        String schema = "v11_appointment_upgrade";
        var dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("10")
                .load()
                .migrate();
        UUID businessId = UUID.randomUUID();
        JdbcClient upgrade = JdbcClient.create(dataSource);
        upgrade.sql("""
                        INSERT INTO v11_appointment_upgrade.business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (:id, :slug, 'Upgrade Business', 'OTHER', 'ACTIVE',
                                'Europe/Sofia', now(), now())
                        """)
                .param("id", businessId)
                .param("slug", "upgrade-" + businessId)
                .update();

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        assertThat(upgrade.sql("SELECT count(*) FROM v11_appointment_upgrade.business")
                        .query(Long.class).single()).isEqualTo(1L);
        assertThat(upgrade.sql("SELECT count(*) FROM v11_appointment_upgrade.appointment")
                        .query(Long.class).single()).isZero();
        assertThat(upgrade.sql("""
                        SELECT version
                        FROM v11_appointment_upgrade.flyway_schema_history
                        WHERE success = true AND type = 'SQL'
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """)
                .query(String.class).single()).isEqualTo("11");
    }

    // ---- exact structure -----------------------------------------------------

    @Test
    void theTableHasExactlyTheApprovedColumnsTypesAndNullability() {
        List<String> columns = jdbc.sql("""
                        SELECT column_name || '|' || data_type || '|' || is_nullable || '|'
                               || coalesce(character_maximum_length::text, '') || '|'
                               || coalesce(numeric_precision::text, '') || ','
                               || coalesce(numeric_scale::text, '')
                        FROM information_schema.columns
                        WHERE table_schema = current_schema() AND table_name = 'appointment'
                        ORDER BY ordinal_position
                        """)
                .query(String.class)
                .list();

        assertThat(columns).containsExactly(
                "id|uuid|NO||,",
                "business_id|uuid|NO||,",
                "customer_id|uuid|NO||,",
                "service_id|uuid|NO||,",
                "staff_member_id|uuid|NO||,",
                "source|character varying|NO|16|,",
                "status|character varying|NO|16|,",
                "start_at|timestamp with time zone|NO||,",
                "end_at|timestamp with time zone|NO||,",
                "occupied_until|timestamp with time zone|NO||,",
                "timezone|character varying|NO|100|,",
                "duration_minutes|integer|NO||32,0",
                "price_eur|numeric|NO||12,2",
                "service_name|character varying|NO|200|,",
                "staff_display_name|character varying|NO|200|,",
                "customer_note|character varying|YES|500|,",
                "public_reference|character varying|NO|10|,",
                "booking_attempt_hash|bytea|YES||,",
                "request_fingerprint|bytea|YES||,",
                "fingerprint_encoding_version|smallint|YES||16,0",
                "fingerprint_key_version|smallint|YES||16,0",
                "version|bigint|NO||64,0",
                "created_at|timestamp with time zone|NO||,",
                "updated_at|timestamp with time zone|NO||,");
    }

    @Test
    void theTableHasExactlyTheApprovedConstraintsAndIndexes() {
        Map<String, String> constraints = jdbc.sql("""
                        SELECT conname, contype::text AS kind
                        FROM pg_constraint
                        WHERE conrelid = 'appointment'::regclass AND contype <> 'n'
                        """)
                .query((resultSet, row) -> Map.entry(
                        resultSet.getString("conname"), resultSet.getString("kind")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(constraints).containsOnlyKeys(
                "appointment_pkey",
                "appointment_business_fk", "appointment_customer_fk", "appointment_service_fk",
                "appointment_staff_member_fk",
                "appointment_source_valid", "appointment_status_valid",
                "appointment_instants_finite", "appointment_duration_minutes_range",
                "appointment_end_matches_duration", "appointment_occupied_until_equals_end",
                "appointment_price_nonnegative", "appointment_timezone_not_blank",
                "appointment_service_name_canonical", "appointment_staff_display_name_canonical",
                "appointment_customer_note_plain_text", "appointment_public_reference_format",
                "appointment_attempt_hash_length", "appointment_fingerprint_length",
                "appointment_fingerprint_versions_positive",
                "appointment_idempotency_all_or_none", "appointment_online_requires_idempotency",
                "appointment_version_nonnegative", "appointment_timestamps_finite_ordered",
                "appointment_business_id_id_unique",
                "appointment_business_public_reference_unique",
                "appointment_business_attempt_hash_unique",
                "appointment_staff_no_overlap");
        assertThat(constraints.values().stream().filter("f"::equals).count()).isEqualTo(4);
        assertThat(constraints.get("appointment_staff_no_overlap")).isEqualTo("x");
        assertThat(constraints.get("appointment_pkey")).isEqualTo("p");

        assertThat(jdbc.sql("""
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = current_schema() AND tablename = 'appointment'
                        """)
                .query(String.class).list()).containsExactlyInAnyOrder(
                "appointment_pkey",
                "appointment_business_id_id_unique",
                "appointment_business_public_reference_unique",
                "appointment_business_attempt_hash_unique",
                "appointment_staff_no_overlap");
    }

    @Test
    void everyForeignKeyIsACompositeSameBusinessRestrictiveReference() {
        List<String> definitions = jdbc.sql("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conrelid = 'appointment'::regclass AND contype = 'f'
                        ORDER BY conname
                        """)
                .query(String.class).list();

        assertThat(definitions).containsExactly(
                "FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT",
                "FOREIGN KEY (business_id, customer_id) REFERENCES customer(business_id, id) ON DELETE RESTRICT",
                "FOREIGN KEY (business_id, service_id) REFERENCES service(business_id, id) ON DELETE RESTRICT",
                "FOREIGN KEY (business_id, staff_member_id) REFERENCES staff_member(business_id, id) ON DELETE RESTRICT");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_constraint
                        WHERE conrelid = 'appointment'::regclass
                          AND contype = 'f' AND confrelid = 'staff_member_service'::regclass
                        """)
                .query(Long.class).single()).isZero();
    }

    @Test
    void theExclusionKeysOnTheStaffMemberAndTheHalfOpenRangeOfConfirmedRowsOnly() {
        String definition = jdbc.sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint
                        WHERE conrelid = 'appointment'::regclass
                          AND conname = 'appointment_staff_no_overlap'
                        """)
                .query(String.class).single();

        assertThat(definition)
                .contains("EXCLUDE USING gist")
                .contains("staff_member_id WITH =")
                .contains("tstzrange(start_at, occupied_until, '[)'")
                .contains("WITH &&")
                .contains("CONFIRMED");
        assertThat(definition).doesNotContain("business_id WITH");
    }

    // ---- explicit nullability ------------------------------------------------

    static List<String> notNullColumns() {
        return List.of(
                "id", "business_id", "customer_id", "service_id", "staff_member_id", "source",
                "status", "start_at", "end_at", "occupied_until", "timezone", "duration_minutes",
                "price_eur", "service_name", "staff_display_name", "public_reference", "version",
                "created_at", "updated_at");
    }

    @ParameterizedTest
    @MethodSource("notNullColumns")
    void everyRequiredColumnRejectsNullThroughNotNullAndNotACheck(String column) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        row.put(column, null);

        Violation violation = violationOf(() -> fixtures.insertRow(row));

        assertThat(violation.sqlState()).isEqualTo(NOT_NULL_VIOLATION);
        assertThat(violation.column()).isEqualTo(column);
    }

    @Test
    void theNoteAndTheFourIdempotencyColumnsAcceptNull() {
        fixtures.insertRow(AppointmentFixtures.row(tenant, START, 30));

        assertThat(jdbc.sql("""
                        SELECT customer_note IS NULL AND booking_attempt_hash IS NULL
                               AND request_fingerprint IS NULL
                               AND fingerprint_encoding_version IS NULL
                               AND fingerprint_key_version IS NULL
                        FROM appointment
                        """)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void validOnlineAndManualFieldCombinationsAreAccepted() {
        Map<String, Object> online = AppointmentFixtures.row(tenant, START, 30);
        online.put("source", "ONLINE");
        online.put("customer_note", "Моля, обадете се преди часа.\n\tБлагодаря.");
        addAttempt(online);
        fixtures.insertRow(online);

        Map<String, Object> manual = AppointmentFixtures.row(tenant, START.plusSeconds(3600), 30);
        fixtures.insertRow(manual);

        Map<String, Object> manualWithAttempt =
                AppointmentFixtures.row(tenant, START.plusSeconds(7200), 30);
        addAttempt(manualWithAttempt);
        fixtures.insertRow(manualWithAttempt);

        assertThat(count()).isEqualTo(3L);
    }

    // ---- CHECK constraints ---------------------------------------------------

    static List<Arguments> checkCases() {
        Instant end = START.plusSeconds(30 * 60L);
        return List.of(
                check("source", "WALKIN", "appointment_source_valid"),
                check("status", "PENDING", "appointment_status_valid"),
                check("status", "COMPLETED", "appointment_status_valid"),
                check("status", "NO_SHOW", "appointment_status_valid"),
                check("status", "CANCELED", "appointment_status_valid"),
                check("status", "confirmed", "appointment_status_valid"),
                check("price_eur", new BigDecimal("-0.01"), "appointment_price_nonnegative"),
                check("timezone", "", "appointment_timezone_not_blank"),
                check("timezone", " Europe/Sofia", "appointment_timezone_not_blank"),
                check("service_name", "", "appointment_service_name_canonical"),
                check("service_name", " Подстригване", "appointment_service_name_canonical"),
                check("service_name", "Двойни  интервали", "appointment_service_name_canonical"),
                check("staff_display_name", "", "appointment_staff_display_name_canonical"),
                check("staff_display_name", "Мария ", "appointment_staff_display_name_canonical"),
                check("customer_note", "", "appointment_customer_note_plain_text"),
                check("customer_note", " бележка", "appointment_customer_note_plain_text"),
                check("customer_note", "бележка\n", "appointment_customer_note_plain_text"),
                check("customer_note", "ред\u0001ред", "appointment_customer_note_plain_text"),
                check("customer_note", "ред\rред", "appointment_customer_note_plain_text"),
                check("customer_note", "ред\u007Fред", "appointment_customer_note_plain_text"),
                check("public_reference", "abcdefghjk", "appointment_public_reference_format"),
                check("public_reference", "IIIIIIIIII", "appointment_public_reference_format"),
                check("public_reference", "OOOOOOOOOO", "appointment_public_reference_format"),
                check("public_reference", "ABC", "appointment_public_reference_format"),
                check("version", -1L, "appointment_version_nonnegative"),
                check("occupied_until", end.plusSeconds(60), "appointment_occupied_until_equals_end"),
                check("occupied_until", end.minusSeconds(60), "appointment_occupied_until_equals_end"),
                check("end_at", end.plusSeconds(60), "appointment_end_matches_duration"),
                check("duration_minutes", 481, "appointment_duration_minutes_range"),
                check("duration_minutes", 0, "appointment_duration_minutes_range"),
                check("updated_at", Instant.parse("2026-09-30T08:00:00Z"),
                        "appointment_timestamps_finite_ordered"));
    }

    private static Arguments check(String column, Object value, String constraint) {
        return Arguments.of(column, value, constraint);
    }

    @ParameterizedTest
    @MethodSource("checkCases")
    void everyCheckRejectsExactlyItsViolation(String column, Object value, String constraint) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        Object stored = value instanceof Instant instant ? AppointmentFixtures.utc(instant) : value;
        row.put(column, stored);
        if (column.equals("end_at")) {
            // The blocking end follows, so only the end-versus-duration rule is broken.
            row.put("occupied_until", stored);
        }
        if (column.equals("duration_minutes")) {
            // Keep the instants consistent so only the duration range is broken.
            Instant end = START.plusSeconds(((Integer) value) * 60L);
            row.put("end_at", AppointmentFixtures.utc(end));
            row.put("occupied_until", AppointmentFixtures.utc(end));
        }

        Violation violation = violationOf(() -> fixtures.insertRow(row));

        assertThat(violation.sqlState()).isEqualTo(CHECK_VIOLATION);
        assertThat(violation.constraint()).isEqualTo(constraint);
        assertThat(count()).isZero();
    }

    @Test
    void infiniteInstantsAreRejectedByTheFiniteCheck() {
        Violation infinite = violationOf(() -> jdbc.sql("""
                        INSERT INTO appointment(
                            id, business_id, customer_id, service_id, staff_member_id, source,
                            status, start_at, end_at, occupied_until, timezone, duration_minutes,
                            price_eur, service_name, staff_display_name, public_reference,
                            version, created_at, updated_at)
                        VALUES (gen_random_uuid(), :business, :customer, :service, :staff,
                                'MANUAL', 'CONFIRMED', 'infinity', 'infinity', 'infinity',
                                'Europe/Sofia', 30, 1.00, 'Услуга', 'Служител', 'ABCDEFGHJK',
                                0, now(), now())
                        """)
                .param("business", tenant.business())
                .param("customer", tenant.customer())
                .param("service", tenant.service())
                .param("staff", tenant.staff())
                .update());

        assertThat(infinite.sqlState()).isEqualTo(CHECK_VIOLATION);
        assertThat(infinite.constraint()).isEqualTo("appointment_instants_finite");
        assertThat(count()).isZero();
    }

    static List<String> snapshotNameCases() {
        return List.of(
                "Подстригване", "Мария Иванова-Петрова", "Д-р Иван Петров",
                "Двойни  интервали", "Тест\tтаб", "Тест\u00A0нбсп", "Тест\u3000идеографски",
                "\uFF21\uFF22\uFF23", "\uFB01nish", "Услуга\u00B2", " Услуга", "Услуга ", "");
    }

    @ParameterizedTest
    @MethodSource("snapshotNameCases")
    void theDomainAndTheDatabaseAgreeOnWhichSnapshotNamesAreCanonical(String name) {
        boolean domainAccepts = true;
        try {
            new bg.spotyourslot.booking.domain.NewAppointment(
                    UUID.randomUUID(), tenant.business(), tenant.customer(), tenant.service(),
                    tenant.staff(), bg.spotyourslot.booking.domain.AppointmentSource.MANUAL, START,
                    30, BigDecimal.TEN, "Europe/Sofia", name, name, null,
                    AppointmentFixtures.reference(), null, Instant.parse("2026-10-01T08:00:00Z"));
        } catch (IllegalArgumentException rejected) {
            domainAccepts = false;
        }

        for (String column : List.of("service_name", "staff_display_name")) {
            Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
            row.put(column, name);
            Violation violation = domainAccepts
                    ? null
                    : violationOf(() -> fixtures.insertRow(row));
            if (domainAccepts) {
                fixtures.insertRow(row);
                jdbc.sql("DELETE FROM appointment").update();
            } else {
                assertThat(violation.sqlState()).as(column).isEqualTo(CHECK_VIOLATION);
                assertThat(violation.constraint()).as(column).isIn(
                        "appointment_service_name_canonical",
                        "appointment_staff_display_name_canonical");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"note", "reference"})
    void tooLongTextIsRejectedByTheColumnLengthBeforeAnyCheck(String which) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        if (which.equals("note")) {
            row.put("customer_note", "а".repeat(501));
        } else {
            row.put("public_reference", "A".repeat(11));
        }

        assertThat(violationOf(() -> fixtures.insertRow(row)).sqlState()).isEqualTo(VALUE_TOO_LONG);
    }

    @Test
    void aNoteOfFiveHundredCodePointsIsAcceptedAndCountedInCodePoints() {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        row.put("customer_note", "😀".repeat(500));

        fixtures.insertRow(row);

        assertThat(jdbc.sql("SELECT char_length(customer_note) FROM appointment")
                .query(Integer.class).single()).isEqualTo(500);
    }

    // ---- idempotency-field invariants ----------------------------------------

    static List<Arguments> idempotencyCases() {
        return List.of(
                Arguments.of("hash 31 bytes", "booking_attempt_hash", new byte[31],
                        "appointment_attempt_hash_length"),
                Arguments.of("fingerprint 33 bytes", "request_fingerprint", new byte[33],
                        "appointment_fingerprint_length"),
                Arguments.of("encoding version 0", "fingerprint_encoding_version", (short) 0,
                        "appointment_fingerprint_versions_positive"),
                Arguments.of("key version 0", "fingerprint_key_version", (short) 0,
                        "appointment_fingerprint_versions_positive"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idempotencyCases")
    void invalidIdempotencyValuesAreRejected(
            String name, String column, Object value, String constraint) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        addAttempt(row);
        row.put(column, value);

        Violation violation = violationOf(() -> fixtures.insertRow(row));

        assertThat(violation.sqlState()).isEqualTo(CHECK_VIOLATION);
        assertThat(violation.constraint()).isEqualTo(constraint);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "booking_attempt_hash", "request_fingerprint", "fingerprint_encoding_version",
        "fingerprint_key_version"})
    void anyPartialIdempotencySetIsRejected(String presentColumn) {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        Map<String, Object> full = new HashMap<>();
        addAttempt(full);
        row.put(presentColumn, full.get(presentColumn));

        Violation violation = violationOf(() -> fixtures.insertRow(row));

        assertThat(violation.sqlState()).isEqualTo(CHECK_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_idempotency_all_or_none");
    }

    @Test
    void anOnlineRowWithoutIdempotencyIsRejectedAndAnIncompleteOneIsToo() {
        Map<String, Object> without = AppointmentFixtures.row(tenant, START, 30);
        without.put("source", "ONLINE");

        Violation violation = violationOf(() -> fixtures.insertRow(without));

        assertThat(violation.sqlState()).isEqualTo(CHECK_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_online_requires_idempotency");

        Map<String, Object> incomplete = AppointmentFixtures.row(tenant, START, 30);
        incomplete.put("source", "ONLINE");
        incomplete.put("booking_attempt_hash", AppointmentFixtures.digest());

        assertThat(violationOf(() -> fixtures.insertRow(incomplete)).constraint())
                .isEqualTo("appointment_idempotency_all_or_none");
        assertThat(count()).isZero();
    }

    // ---- references and tenant isolation -------------------------------------

    @Test
    void everyCrossBusinessReferenceIsRejectedByItsCompositeForeignKey() {
        Tenant other = fixtures.tenant();

        Map<String, Object> customer = AppointmentFixtures.row(tenant, START, 30);
        customer.put("customer_id", other.customer());
        assertForeignKey(customer, "appointment_customer_fk");

        Map<String, Object> service = AppointmentFixtures.row(tenant, START, 30);
        service.put("service_id", other.service());
        assertForeignKey(service, "appointment_service_fk");

        Map<String, Object> staff = AppointmentFixtures.row(tenant, START, 30);
        staff.put("staff_member_id", other.staff());
        assertForeignKey(staff, "appointment_staff_member_fk");

        assertThat(count()).isZero();
    }

    @Test
    void aMissingBusinessAndMissingReferencesAreRejected() {
        Map<String, Object> business = AppointmentFixtures.row(tenant, START, 30);
        business.put("business_id", UUID.randomUUID());
        assertForeignKey(business, "appointment_business_fk");

        Map<String, Object> customer = AppointmentFixtures.row(tenant, START, 30);
        customer.put("customer_id", UUID.randomUUID());
        assertForeignKey(customer, "appointment_customer_fk");

        assertThat(count()).isZero();
    }

    @Test
    void referencedRowsCannotBeDeletedWhileAnAppointmentExists() {
        fixtures.insertRow(AppointmentFixtures.row(tenant, START, 30));

        for (String statement : List.of(
                "DELETE FROM customer WHERE id = :id",
                "DELETE FROM service WHERE id = :id",
                "DELETE FROM staff_member WHERE id = :id",
                "DELETE FROM business WHERE id = :id")) {
            UUID id = statement.contains("customer") ? tenant.customer()
                    : statement.contains("service") ? tenant.service()
                    : statement.contains("staff_member") ? tenant.staff()
                    : tenant.business();
            assertThat(violationOf(() -> jdbc.sql(statement).param("id", id).update()).sqlState())
                    .as(statement).isEqualTo(RESTRICT_VIOLATION);
        }
        assertThat(count()).isEqualTo(1L);
    }

    // ---- uniqueness ----------------------------------------------------------

    @Test
    void aPublicReferenceIsUniquePerBusinessOnly() {
        Tenant other = fixtures.tenant();
        Map<String, Object> first = AppointmentFixtures.row(tenant, START, 30);
        fixtures.insertRow(first);

        Map<String, Object> duplicate = AppointmentFixtures.row(tenant, START.plusSeconds(7200), 30);
        duplicate.put("public_reference", first.get("public_reference"));
        Violation violation = violationOf(() -> fixtures.insertRow(duplicate));

        assertThat(violation.sqlState()).isEqualTo(UNIQUE_VIOLATION);
        assertThat(violation.constraint())
                .isEqualTo("appointment_business_public_reference_unique");

        Map<String, Object> otherBusiness = AppointmentFixtures.row(other, START, 30);
        otherBusiness.put("public_reference", first.get("public_reference"));
        fixtures.insertRow(otherBusiness);
        assertThat(count()).isEqualTo(2L);
    }

    @Test
    void anAttemptHashIsUniquePerBusinessOnlyAndNullHashesNeverCollide() {
        Tenant other = fixtures.tenant();
        Map<String, Object> first = AppointmentFixtures.row(tenant, START, 30);
        first.put("source", "ONLINE");
        addAttempt(first);
        fixtures.insertRow(first);

        Map<String, Object> duplicate = AppointmentFixtures.row(tenant, START.plusSeconds(7200), 30);
        duplicate.put("source", "ONLINE");
        addAttempt(duplicate);
        duplicate.put("booking_attempt_hash", first.get("booking_attempt_hash"));
        Violation violation = violationOf(() -> fixtures.insertRow(duplicate));

        assertThat(violation.sqlState()).isEqualTo(UNIQUE_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_business_attempt_hash_unique");

        Map<String, Object> otherBusiness = AppointmentFixtures.row(other, START, 30);
        otherBusiness.put("source", "ONLINE");
        addAttempt(otherBusiness);
        otherBusiness.put("booking_attempt_hash", first.get("booking_attempt_hash"));
        fixtures.insertRow(otherBusiness);

        fixtures.insertRow(AppointmentFixtures.row(tenant, START.plusSeconds(3600), 30));
        fixtures.insertRow(AppointmentFixtures.row(tenant, START.plusSeconds(5400), 30));
        assertThat(count()).isEqualTo(4L);
    }

    // ---- snapshots -----------------------------------------------------------

    @Test
    void laterServiceStaffMemberAndBusinessChangesNeverRewriteTheSnapshots() {
        Map<String, Object> row = AppointmentFixtures.row(tenant, START, 30);
        fixtures.insertRow(row);

        jdbc.sql("""
                        UPDATE service SET name = 'Преименувана', price = 99.99,
                            duration_minutes = 120, active = false
                        WHERE id = :id
                        """)
                .param("id", tenant.service()).update();
        jdbc.sql("UPDATE staff_member SET display_name = 'Преименуван', active = false WHERE id = :id")
                .param("id", tenant.staff()).update();
        jdbc.sql("UPDATE business SET timezone = 'Europe/London' WHERE id = :id")
                .param("id", tenant.business()).update();
        jdbc.sql("DELETE FROM staff_member_service WHERE staff_member_id = :id")
                .param("id", tenant.staff()).update();

        Map<String, Object> stored = jdbc.sql("""
                        SELECT service_name, staff_display_name, price_eur, duration_minutes,
                               timezone, status, start_at, end_at
                        FROM appointment
                        """)
                .query().singleRow();
        assertThat(stored.get("service_name")).isEqualTo(AppointmentFixtures.SERVICE_NAME);
        assertThat(stored.get("staff_display_name")).isEqualTo(AppointmentFixtures.STAFF_NAME);
        assertThat((BigDecimal) stored.get("price_eur")).isEqualByComparingTo("25.00");
        assertThat(stored.get("duration_minutes")).isEqualTo(30);
        assertThat(stored.get("timezone")).isEqualTo("Europe/Sofia");
        assertThat(stored.get("status")).isEqualTo("CONFIRMED");
    }

    // ---- overlap exclusion ---------------------------------------------------

    @Test
    void identicalPartialContainedAndContainingOverlapsAreRejected() {
        fixtures.insertRow(AppointmentFixtures.row(tenant, START, 60));

        for (Instant start : List.of(
                START,
                START.plusSeconds(30 * 60L),
                START.minusSeconds(30 * 60L),
                START.plusSeconds(15 * 60L),
                START.minusSeconds(15 * 60L))) {
            int minutes = start.equals(START.plusSeconds(15 * 60L)) ? 15 : 60;
            Violation violation = violationOf(() -> fixtures.insertRow(
                    AppointmentFixtures.row(tenant, start, minutes)));
            assertThat(violation.sqlState()).as(start.toString()).isEqualTo(EXCLUSION_VIOLATION);
            assertThat(violation.constraint()).isEqualTo("appointment_staff_no_overlap");
        }
        assertThat(violationOf(() -> fixtures.insertRow(
                AppointmentFixtures.row(tenant, START.minusSeconds(3600), 180))).sqlState())
                .isEqualTo(EXCLUSION_VIOLATION);
        assertThat(count()).isEqualTo(1L);
    }

    @Test
    void adjacentAppointmentsOnBothSidesAreAllowed() {
        fixtures.insertRow(AppointmentFixtures.row(tenant, START, 60));

        fixtures.insertRow(AppointmentFixtures.row(tenant, START.minusSeconds(30 * 60L), 30));
        fixtures.insertRow(AppointmentFixtures.row(tenant, START.plusSeconds(3600), 30));

        assertThat(count()).isEqualTo(3L);
    }

    @Test
    void differentStaffMembersAndDifferentBusinessesAreIndependent() {
        fixtures.insertRow(AppointmentFixtures.row(tenant, START, 60));
        UUID secondStaff = fixtures.staffMember(tenant.business(), tenant.service());
        Tenant otherBusiness = fixtures.tenant();

        fixtures.insertRow(AppointmentFixtures.row(
                new Tenant(tenant.business(), tenant.service(), secondStaff, tenant.customer()),
                START, 60));
        fixtures.insertRow(AppointmentFixtures.row(otherBusiness, START, 60));

        assertThat(count()).isEqualTo(3L);
    }

    @Test
    void cancelledRowsNeverBlockInEitherDirection() {
        Map<String, Object> cancelled = AppointmentFixtures.row(tenant, START, 60);
        cancelled.put("status", "CANCELLED");
        fixtures.insertRow(cancelled);

        fixtures.insertRow(AppointmentFixtures.row(tenant, START, 60));
        Map<String, Object> secondCancelled = AppointmentFixtures.row(tenant, START, 60);
        secondCancelled.put("status", "CANCELLED");
        fixtures.insertRow(secondCancelled);

        assertThat(count()).isEqualTo(3L);
    }

    @Test
    void theConstraintAlsoGuardsUpdatesThatMakeARowBlockingAgain() {
        Map<String, Object> cancelled = AppointmentFixtures.row(tenant, START, 60);
        cancelled.put("status", "CANCELLED");
        fixtures.insertRow(cancelled);
        fixtures.insertRow(AppointmentFixtures.row(tenant, START, 60));

        Violation violation = violationOf(() -> jdbc.sql(
                        "UPDATE appointment SET status = 'CONFIRMED' WHERE id = :id")
                .param("id", cancelled.get("id")).update());

        assertThat(violation.sqlState()).isEqualTo(EXCLUSION_VIOLATION);
        assertThat(violation.constraint()).isEqualTo("appointment_staff_no_overlap");

        jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE status = 'CONFIRMED'").update();
        jdbc.sql("UPDATE appointment SET status = 'CONFIRMED' WHERE id = :id")
                .param("id", cancelled.get("id")).update();
        assertThat(count()).isEqualTo(2L);
    }

    // ---- DST instants --------------------------------------------------------

    @Test
    void aRepeatedWallClockHourHasTwoDistinctNonOverlappingInstantRanges() {
        ZoneOffsetTransition overlap = autumnTransition();
        Instant transition = overlap.getInstant();
        // 30 minutes before and after the transition share the same local wall-clock time.
        Instant first = transition.minusSeconds(30 * 60L);
        Instant second = transition.plusSeconds(30 * 60L);
        assertThat(first.atZone(ZoneId.of("Europe/Sofia")).toLocalTime())
                .isEqualTo(second.atZone(ZoneId.of("Europe/Sofia")).toLocalTime());

        fixtures.insertRow(AppointmentFixtures.row(tenant, first, 15));
        fixtures.insertRow(AppointmentFixtures.row(tenant, second, 15));

        assertThat(count()).isEqualTo(2L);
        assertThat(violationOf(() -> fixtures.insertRow(
                AppointmentFixtures.row(tenant, transition.minusSeconds(20 * 60L), 15))).sqlState())
                .isEqualTo(EXCLUSION_VIOLATION);
        // Adjacent to the first range at the transition instant is allowed.
        fixtures.insertRow(AppointmentFixtures.row(tenant, first.plusSeconds(15 * 60L), 15));
    }

    @Test
    void anAppointmentCrossingATransitionKeepsItsElapsedDurationAndBlocksInInstants() {
        ZoneOffsetTransition overlap = autumnTransition();
        Instant start = overlap.getInstant().minusSeconds(30 * 60L);
        fixtures.insertRow(AppointmentFixtures.row(tenant, start, 60));

        assertThat(jdbc.sql("SELECT end_at - start_at FROM appointment")
                .query(String.class).single()).isEqualTo("01:00:00");
        assertThat(violationOf(() -> fixtures.insertRow(AppointmentFixtures.row(
                tenant, overlap.getInstant().plusSeconds(10 * 60L), 15))).sqlState())
                .isEqualTo(EXCLUSION_VIOLATION);
        fixtures.insertRow(AppointmentFixtures.row(tenant, start.plusSeconds(3600), 30));

        ZoneOffsetTransition gap = springTransition();
        Instant gapStart = gap.getInstant().minusSeconds(30 * 60L);
        fixtures.insertRow(AppointmentFixtures.row(tenant, gapStart, 60));
        assertThat(jdbc.sql("""
                        SELECT end_at - start_at FROM appointment WHERE start_at = :start
                        """)
                .param("start", AppointmentFixtures.utc(gapStart))
                .query(String.class).single()).isEqualTo("01:00:00");
        assertThat(violationOf(() -> fixtures.insertRow(AppointmentFixtures.row(
                tenant, gap.getInstant().plusSeconds(15 * 60L), 15))).sqlState())
                .isEqualTo(EXCLUSION_VIOLATION);
    }

    private static ZoneOffsetTransition springTransition() {
        return ZoneId.of("Europe/Sofia").getRules()
                .nextTransition(Instant.parse("2027-01-01T00:00:00Z"));
    }

    private static ZoneOffsetTransition autumnTransition() {
        return ZoneId.of("Europe/Sofia").getRules()
                .nextTransition(springTransition().getInstant());
    }

    // ---- helpers -------------------------------------------------------------

    private static void addAttempt(Map<String, Object> row) {
        row.put("booking_attempt_hash", AppointmentFixtures.digest());
        row.put("request_fingerprint", AppointmentFixtures.digest());
        row.put("fingerprint_encoding_version", (short) 1);
        row.put("fingerprint_key_version", (short) 1);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
    }

    private void assertForeignKey(Map<String, Object> row, String constraint) {
        Violation violation = violationOf(() -> fixtures.insertRow(row));
        assertThat(violation.sqlState()).isEqualTo(FOREIGN_KEY_VIOLATION);
        assertThat(violation.constraint()).isEqualTo(constraint);
    }

    private record Violation(String sqlState, String constraint, String column) {
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
        return new Violation(
                postgres.getSQLState(),
                details == null ? null : details.getConstraint(),
                details == null ? null : details.getColumn());
    }

    private static String sha256(String file) {
        try (InputStream stream = AppointmentSchemaIntegrationTests.class
                .getResourceAsStream("/db/migration/" + file)) {
            assertThat(stream).as(file).isNotNull();
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
