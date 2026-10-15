package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@code V14} migration (ADR-0028 section 8, issue #19 Phase 2) on real PostgreSQL: that it is
 * a new migration and leaves V1 to V13 byte for byte, the upgrade of a database migrated to V13
 * with CONFIRMED and CANCELLED Appointments, what the backfill physically touches, a controlled
 * corrupt fixture that makes the migration fail and leave nothing, the verification block, and the
 * equality of an upgraded schema with a fresh installation. Every value is synthetic and every
 * schema is disposable; nothing touches the development database.
 */
class AppointmentAdministrationMigrationIntegrationTests extends PostgresIntegrationTest {
    private static final Map<String, String> PRIOR_MIGRATIONS = priorMigrations();
    private static final String V14_FILE = "V14__add_appointment_administration.sql";

    private static Map<String, String> priorMigrations() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("V1__identity_and_tenancy.sql",
                "68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49");
        files.put("V2__enforce_single_active_identity_tokens.sql",
                "c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8");
        files.put("V3__add_business_profile_fields.sql",
                "655d22a52c06eb41996a75c100c1bfab853c907b9e0e076f128ba2ab8be674a2");
        files.put("V4__structure_business_address.sql",
                "aa48255701e3ce6999073801ca0ed37e292b9ada545e3fe4a50221ada596cb98");
        files.put("V5__add_business_services.sql",
                "e2221627ed52ceb951881648d9738d213544ad4c8d79aa35e1e79c0896e958d3");
        files.put("V6__add_staff_members_and_service_assignments.sql",
                "73e89120c0163d6ea79ee28f0d59ae02c055df85066234a3688f525ada16ff1b");
        files.put("V7__add_recurring_staff_working_schedules.sql",
                "d9a8184b7c7c856064426d79dd375e7b4fb7f55a1fec389b7c391512286339b2");
        files.put("V8__canonicalize_staff_member_contact_phone.sql",
                "2f9fb21a06d4f42e5a3f9b4f479a2bbae2470e4fad0800247aed816d624668e8");
        files.put("V9__add_schedule_exceptions.sql",
                "9f0560e4daeb139eafe611b4e890a8505ca02ccffa30a293fef24a2a2fd182dd");
        files.put("V10__add_customers.sql",
                "a80c976b011bdfffc0948d252cf158ce9b75d8c5341b5dc31c36c94a94c3cfaa");
        files.put("V11__add_appointments.sql",
                "3135ee8ffc34df9f5a10ce85170a160e5dfc1c4579c1c1aa1f118318783bbb0f");
        files.put("V12__add_business_schedule_revision.sql",
                "98717e79648bee52401d5c9ba379bc82b8da50435028313f7d1e93eb48ba4615");
        files.put("V13__add_appointment_services.sql",
                "12526a8c5e5c9fab3abaa647a56da46732539544f066d97c734915da88cace4e");
        return files;
    }

    @Test
    void v1ThroughV13RemainByteForByteUnchanged() {
        PRIOR_MIGRATIONS.forEach((file, expected) ->
                assertThat(sha256(file)).as(file).isEqualTo(expected));
    }

    @Test
    void upgradingV13BackfillsTheUnknownActorAndLeavesEveryOtherColumnIdentical() {
        String schema = "v14_upgrade_data";
        PGSimpleDataSource dataSource = dataSource(schema);
        JdbcClient upgrade = seedAtV13(dataSource, schema);
        Map<UUID, String> before = rowsAsJson(upgrade, schema, "to_jsonb(a)");
        Map<UUID, String> statusBefore = column(upgrade, schema, "status");

        migrate(dataSource, schema, "14");

        assertThat(statusBefore.values()).contains("CONFIRMED", "CANCELLED");
        Map<UUID, String> after = rowsAsJson(upgrade, schema,
                "(to_jsonb(a) - 'cancelled_at' - 'cancelled_by' - 'internal_note')");
        assertThat(after).isEqualTo(before);
        // Cancelled rows are UNKNOWN with no time; confirmed rows carry nothing; no note anywhere.
        statusBefore.forEach((id, status) -> {
            Map<String, Object> row = upgrade.sql("""
                            SELECT cancelled_by, cancelled_at, internal_note
                            FROM %s.appointment WHERE id = :id
                            """.formatted(schema)).param("id", id)
                    .query((resultSet, rowNumber) -> {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("by", resultSet.getString("cancelled_by"));
                        values.put("at", resultSet.getObject("cancelled_at"));
                        values.put("note", resultSet.getString("internal_note"));
                        return values;
                    }).single();
            assertThat(row.get("at")).isNull();
            assertThat(row.get("note")).isNull();
            assertThat(row.get("by")).isEqualTo(status.equals("CANCELLED") ? "UNKNOWN" : null);
        });
        assertThat(upgrade.sql("""
                        SELECT version FROM %s.flyway_schema_history
                        WHERE success = true AND type = 'SQL'
                        ORDER BY installed_rank DESC LIMIT 1
                        """.formatted(schema)).query(String.class).single()).isEqualTo("14");
    }

    @Test
    void addingTheColumnsRewritesNoTableFileButTheBackfillUpdatesExactlyTheCancelledTuples() {
        String schema = "v14_physical";
        PGSimpleDataSource dataSource = dataSource(schema);
        JdbcClient upgrade = seedAtV13(dataSource, schema);
        long fileBefore = relfilenode(upgrade, schema, "appointment");
        Map<UUID, String> xminBefore = column(upgrade, schema, "xmin::text");
        Map<UUID, String> statuses = column(upgrade, schema, "status");

        migrate(dataSource, schema, "14");

        // No full replacement of the table file ...
        assertThat(relfilenode(upgrade, schema, "appointment"))
                .as("the appointment table file was not replaced")
                .isEqualTo(fileBefore);
        // ... but the backfill is a real UPDATE: it wrote new tuple versions of the pre-existing
        // CANCELLED rows, and of nothing else.
        Map<UUID, String> xminAfter = column(upgrade, schema, "xmin::text");
        assertThat(statuses.values()).contains("CONFIRMED", "CANCELLED");
        statuses.forEach((id, status) -> {
            if (status.equals("CANCELLED")) {
                assertThat(xminAfter.get(id)).as("cancelled tuple %s", id).isNotEqualTo(xminBefore.get(id));
            } else {
                assertThat(xminAfter.get(id)).as("confirmed tuple %s", id).isEqualTo(xminBefore.get(id));
            }
        });
    }

    @Test
    void theNewColumnsHaveNoDefaultAndAreNullable() {
        String schema = "v14_columns";
        PGSimpleDataSource dataSource = dataSource(schema);
        migrate(dataSource, schema, "14");
        JdbcClient upgrade = JdbcClient.create(dataSource);

        List<String> columns = upgrade.sql("""
                        SELECT column_name || '|' || data_type || '|' || is_nullable || '|'
                               || coalesce(character_maximum_length::text, '') || '|'
                               || coalesce(column_default, '')
                        FROM information_schema.columns
                        WHERE table_schema = :schema AND table_name = 'appointment'
                          AND column_name IN ('cancelled_at', 'cancelled_by', 'internal_note')
                        ORDER BY ordinal_position
                        """).param("schema", schema).query(String.class).list();

        assertThat(columns).containsExactly(
                "cancelled_at|timestamp with time zone|YES||",
                "cancelled_by|character varying|YES|16|",
                "internal_note|character varying|YES|500|");
    }

    @Test
    void aControlledCorruptFixtureMakesTheMigrationFailAndLeavesNothingOfV14() {
        String schema = "v14_corrupt_fixture";
        PGSimpleDataSource dataSource = dataSource(schema);
        JdbcClient upgrade = seedAtV13(dataSource, schema);
        // Legacy data the V11 rule would never have allowed: the status rule is removed and a
        // status outside the domain is stored. V14 must refuse to certify it.
        upgrade.sql("ALTER TABLE " + schema
                + ".appointment DROP CONSTRAINT appointment_status_valid").update();
        upgrade.sql("UPDATE " + schema + ".appointment SET status = 'PENDING' "
                + "WHERE id = (SELECT id FROM " + schema + ".appointment "
                + "WHERE status = 'CONFIRMED' LIMIT 1)").update();
        Map<UUID, String> cancelledBefore = column(upgrade, schema, "status");

        Throwable failure = catchThrowable(() -> migrate(dataSource, schema, "14"));

        assertThat(failure).isInstanceOf(FlywayException.class);
        assertThat(upgrade.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = :schema AND table_name = 'appointment'
                          AND column_name IN ('cancelled_at', 'cancelled_by', 'internal_note')
                        """).param("schema", schema).query(Long.class).single()).isZero();
        assertThat(upgrade.sql("""
                        SELECT count(*) FROM pg_constraint
                        WHERE conrelid = CAST(:name AS regclass)
                          AND conname LIKE 'appointment_cancel%'
                        """).param("name", schema + ".appointment").query(Long.class).single()).isZero();
        assertThat(upgrade.sql("""
                        SELECT count(*) FROM %s.flyway_schema_history
                        WHERE version = '14' AND success = true
                        """.formatted(schema)).query(Long.class).single()).isZero();
        assertThat(column(upgrade, schema, "status")).isEqualTo(cancelledBefore);
    }

    @Test
    void theVerificationBlockRefusesACancelledRowWithoutTheUnknownActor() {
        String schema = "v14_verify_block";
        PGSimpleDataSource dataSource = dataSource(schema);
        JdbcClient upgrade = seedAtV13(dataSource, schema);
        migrate(dataSource, schema, "14");
        // Dropping the constraint reproduces a failed backfill without needing a second migration.
        upgrade.sql("ALTER TABLE " + schema
                + ".appointment DROP CONSTRAINT appointment_cancellation_consistent").update();
        upgrade.sql("UPDATE " + schema + ".appointment SET cancelled_by = NULL "
                + "WHERE status = 'CANCELLED'").update();

        Throwable failure = catchThrowable(() -> executeScript(
                dataSource, "SET search_path TO " + schema + ";\n" + verificationBlock()));

        assertThat(sqlStateOf(failure)).isEqualTo("23514");
        assertThat(failure.getCause().getMessage())
                .contains("appointment cancellation backfill is inconsistent");
    }

    @Test
    void theVerificationBlockAcceptsAnInternalNoteOnConfirmedAndCancelledRows() {
        String schema = "v14_verify_note";
        PGSimpleDataSource dataSource = dataSource(schema);
        JdbcClient upgrade = seedAtV13(dataSource, schema);
        migrate(dataSource, schema, "14");
        upgrade.sql("UPDATE " + schema + ".appointment SET internal_note = 'бележка'").update();

        executeScript(dataSource, "SET search_path TO " + schema + ";\n" + verificationBlock());

        assertThat(upgrade.sql("SELECT count(*) FROM " + schema
                + ".appointment WHERE internal_note IS NOT NULL").query(Long.class).single())
                .isGreaterThan(0L);
    }

    @Test
    void anUpgradedSchemaEqualsAFreshInstallation() {
        PGSimpleDataSource upgradedSource = dataSource("v14_equal_upgraded");
        seedAtV13(upgradedSource, "v14_equal_upgraded");
        migrate(upgradedSource, "v14_equal_upgraded", null);
        migrate(dataSource("v14_equal_fresh"), "v14_equal_fresh", null);
        JdbcClient catalog = JdbcClient.create(dataSource(null));

        List<String> upgraded = signature(catalog, "v14_equal_upgraded");
        List<String> fresh = signature(catalog, "v14_equal_fresh");

        assertThat(upgraded).isNotEmpty();
        assertThat(upgraded).isEqualTo(fresh);
        assertThat(upgraded).anyMatch(entry -> entry.startsWith("column|appointment|cancelled_by|"));
        assertThat(upgraded).anyMatch(
                entry -> entry.startsWith("constraint|appointment|appointment_cancellation_consistent|"));
    }

    // ---- fixtures and helpers ------------------------------------------------

    /** A database migrated to V13 with CONFIRMED and CANCELLED Appointments of two Businesses. */
    private JdbcClient seedAtV13(PGSimpleDataSource dataSource, String schema) {
        migrate(dataSource, schema, "12");
        JdbcClient db = JdbcClient.create(dataSource);
        for (int tenantIndex = 0; tenantIndex < 2; tenantIndex++) {
            UUID business = UUID.randomUUID();
            db.sql("""
                            INSERT INTO %s.business(
                                id, slug, display_name, business_type, status, timezone,
                                created_at, updated_at)
                            VALUES (:id, :slug, 'Upgrade Business', 'OTHER', 'ACTIVE', 'Europe/Sofia',
                                    now(), now())
                            """.formatted(schema))
                    .param("id", business).param("slug", "upgrade-" + business).update();
            UUID service = UUID.randomUUID();
            db.sql("""
                            INSERT INTO %s.service(
                                id, business_id, name, description, duration_minutes, price, active,
                                version, created_at, updated_at)
                            VALUES (:id, :b, :name, NULL, 30, 10.00, true, 0, now(), now())
                            """.formatted(schema))
                    .param("id", service).param("b", business).param("name", "Услуга " + service).update();
            UUID staff = UUID.randomUUID();
            db.sql("""
                            INSERT INTO %s.staff_member(
                                id, business_id, display_name, contact_email, contact_phone, active,
                                version, created_at, updated_at)
                            VALUES (:id, :b, 'Мария Тестова', NULL, NULL, true, 0, now(), now())
                            """.formatted(schema))
                    .param("id", staff).param("b", business).update();
            UUID customer = UUID.randomUUID();
            db.sql("""
                            INSERT INTO %s.customer(
                                id, business_id, display_name, phone, email, version, created_at, updated_at)
                            VALUES (:id, :b, 'Тестов Клиент', :phone, NULL, 0, now(), now())
                            """.formatted(schema))
                    .param("id", customer).param("b", business)
                    .param("phone", "+359895" + (200_000 + tenantIndex)).update();
            Instant day = Instant.parse("2026-11-0" + (tenantIndex + 1) + "T06:00:00Z");
            legacyAppointment(db, schema, business, customer, service, staff,
                    "ONLINE", "CONFIRMED", day, 45, true);
            legacyAppointment(db, schema, business, customer, service, staff,
                    "ONLINE", "CANCELLED", day.plusSeconds(3600), 30, true);
            legacyAppointment(db, schema, business, customer, service, staff,
                    "MANUAL", "CONFIRMED", day.plusSeconds(7200), 60, false);
            legacyAppointment(db, schema, business, customer, service, staff,
                    "MANUAL", "CANCELLED", day.plusSeconds(14_400), 15, false);
        }
        migrate(dataSource, schema, "13");
        return db;
    }

    private void legacyAppointment(
            JdbcClient db, String schema, UUID business, UUID customer, UUID service, UUID staff,
            String source, String status, Instant start, int minutes, boolean idempotent) {
        Instant end = start.plusSeconds(minutes * 60L);
        db.sql("""
                        INSERT INTO %s.appointment(
                            id, business_id, customer_id, service_id, staff_member_id, source, status,
                            start_at, end_at, occupied_until, timezone, duration_minutes, price_eur,
                            service_name, staff_display_name, customer_note, public_reference,
                            booking_attempt_hash, request_fingerprint, fingerprint_encoding_version,
                            fingerprint_key_version, version, created_at, updated_at)
                        VALUES (:id, :b, :customer, :service, :staff, :source, :status,
                                :start, :end, :end, 'Europe/Sofia', :minutes, 25.00,
                                'Подстригване', 'Мария Тестова', NULL, :reference,
                                :hash, :fingerprint, :encoding, :key, 3, now(), now())
                        """.formatted(schema))
                .param("id", UUID.randomUUID()).param("b", business).param("customer", customer)
                .param("service", service).param("staff", staff).param("source", source)
                .param("status", status).param("start", utc(start)).param("end", utc(end))
                .param("minutes", minutes)
                .param("reference", AppointmentFixtures.reference())
                .param("hash", idempotent ? AppointmentFixtures.digest() : null, Types.BINARY)
                .param("fingerprint", idempotent ? AppointmentFixtures.digest() : null,
                        Types.BINARY)
                .param("encoding", idempotent ? 1 : null, Types.SMALLINT)
                .param("key", idempotent ? 1 : null, Types.SMALLINT)
                .update();
    }

    private static Map<UUID, String> rowsAsJson(JdbcClient db, String schema, String expression) {
        Map<UUID, String> rows = new LinkedHashMap<>();
        db.sql("SELECT a.id, " + expression + "::text AS doc FROM " + schema
                + ".appointment a ORDER BY a.id")
                .query((resultSet, row) -> {
                    rows.put(resultSet.getObject("id", UUID.class), resultSet.getString("doc"));
                    return row;
                }).list();
        return rows;
    }

    private static Map<UUID, String> column(JdbcClient db, String schema, String expression) {
        Map<UUID, String> rows = new LinkedHashMap<>();
        db.sql("SELECT a.id, " + expression + "::text AS value FROM " + schema
                + ".appointment a ORDER BY a.id")
                .query((resultSet, row) -> {
                    rows.put(resultSet.getObject("id", UUID.class), resultSet.getString("value"));
                    return row;
                }).list();
        return rows;
    }

    private static long relfilenode(JdbcClient db, String schema, String table) {
        return db.sql("SELECT relfilenode::bigint FROM pg_class WHERE oid = CAST(:name AS regclass)")
                .param("name", schema + "." + table).query(Long.class).single();
    }

    /** A schema-independent description of tables, columns, constraints, indexes, triggers, and functions. */
    private static List<String> signature(JdbcClient db, String schema) {
        List<String> entries = new ArrayList<>();
        String schemaPrefix = schema + ".";
        entries.addAll(db.sql("""
                        SELECT 'column|' || table_name || '|' || column_name || '|' || data_type || '|'
                               || is_nullable || '|' || coalesce(character_maximum_length::text, '') || '|'
                               || coalesce(numeric_precision::text, '') || ',' || coalesce(numeric_scale::text, '')
                               || '|' || coalesce(replace(column_default, :prefix, ''), '')
                        FROM information_schema.columns WHERE table_schema = :schema
                        """).param("schema", schema).param("prefix", schemaPrefix)
                .query(String.class).list());
        entries.addAll(db.sql("""
                        SELECT 'constraint|' || c.conrelid::regclass::text || '|' || c.conname || '|'
                               || replace(pg_get_constraintdef(c.oid), :prefix, '')
                        FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                        WHERE n.nspname = :schema
                        """).param("schema", schema).param("prefix", schemaPrefix)
                .query(String.class).list().stream()
                .map(entry -> entry.replace(schemaPrefix, "")).toList());
        entries.addAll(db.sql("""
                        SELECT 'index|' || tablename || '|' || indexname || '|' || replace(indexdef, :prefix, '')
                        FROM pg_indexes WHERE schemaname = :schema
                        """).param("schema", schema).param("prefix", schemaPrefix)
                .query(String.class).list().stream()
                .map(entry -> entry.replace(" " + schema + ".", " ")).toList());
        entries.addAll(db.sql("""
                        SELECT 'trigger|' || c.relname || '|' || t.tgname || '|'
                               || replace(pg_get_triggerdef(t.oid), :prefix, '')
                        FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = :schema AND NOT t.tgisinternal
                        """).param("schema", schema).param("prefix", schemaPrefix)
                .query(String.class).list());
        entries.addAll(db.sql("""
                        SELECT 'function|' || p.proname || '|' || md5(p.prosrc)
                               || '|' || coalesce(replace(array_to_string(p.proconfig, ','), :schema, 'SCHEMA'), '')
                        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname = :schema
                        """).param("schema", schema)
                .query(String.class).list());
        return entries.stream().map(entry -> entry.replace(schema, "SCHEMA")).sorted().toList();
    }

    private static String verificationBlock() {
        try (InputStream stream = AppointmentAdministrationMigrationIntegrationTests.class
                .getResourceAsStream("/db/migration/" + V14_FILE)) {
            assertThat(stream).isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("DO \\$\\$.*?\\$\\$;", Pattern.DOTALL).matcher(text);
            assertThat(matcher.find()).as("the verification DO block").isTrue();
            return matcher.group();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).as("a PostgreSQL error is expected").isNotNull();
        return ((SQLException) current).getSQLState();
    }

    /** A data source whose sessions use the schema, so V12's unqualified business trigger resolves. */
    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        if (schema != null) {
            dataSource.setCurrentSchema(schema);
        }
        return dataSource;
    }

    private static void migrate(PGSimpleDataSource dataSource, String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static void executeScript(PGSimpleDataSource dataSource, String script) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(script);
        } catch (SQLException failure) {
            throw new ScriptFailure(failure);
        }
    }

    /** Lets a test see the original {@link SQLException} through {@code catchThrowable}. */
    private static final class ScriptFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ScriptFailure(SQLException cause) {
            super(cause);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String sha256(String file) {
        try (InputStream stream = AppointmentAdministrationMigrationIntegrationTests.class
                .getResourceAsStream("/db/migration/" + file)) {
            assertThat(stream).as(file).isNotNull();
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
