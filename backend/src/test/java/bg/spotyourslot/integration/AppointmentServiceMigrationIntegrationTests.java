package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
 * The {@code V13} migration (ADR-0027, phase M1) on real PostgreSQL: that it is a new migration and
 * leaves V1 to V12 byte for byte, the upgrade and backfill of a database migrated to V12 with
 * Appointments of every state, the preservation of every existing value (including the maximum
 * price), the measured effect of widening {@code price_eur} on the physical table file, a controlled
 * corrupt fixture that makes the migration fail and leave nothing, the verification block, and the
 * equality of an upgraded schema with a fresh installation. Every value is synthetic and every
 * schema is disposable; nothing touches the development database.
 */
class AppointmentServiceMigrationIntegrationTests extends PostgresIntegrationTest {
    private static final List<String> MIGRATION_FILES = List.of(
            "V1__identity_and_tenancy.sql",
            "V2__enforce_single_active_identity_tokens.sql",
            "V3__add_business_profile_fields.sql",
            "V4__structure_business_address.sql",
            "V5__add_business_services.sql",
            "V6__add_staff_members_and_service_assignments.sql",
            "V7__add_recurring_staff_working_schedules.sql",
            "V8__canonicalize_staff_member_contact_phone.sql",
            "V9__add_schedule_exceptions.sql",
            "V10__add_customers.sql",
            "V11__add_appointments.sql",
            "V12__add_business_schedule_revision.sql");
    private static final List<String> MIGRATION_SHA_256 = List.of(
            "68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49",
            "c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8",
            "655d22a52c06eb41996a75c100c1bfab853c907b9e0e076f128ba2ab8be674a2",
            "aa48255701e3ce6999073801ca0ed37e292b9ada545e3fe4a50221ada596cb98",
            "e2221627ed52ceb951881648d9738d213544ad4c8d79aa35e1e79c0896e958d3",
            "73e89120c0163d6ea79ee28f0d59ae02c055df85066234a3688f525ada16ff1b",
            "d9a8184b7c7c856064426d79dd375e7b4fb7f55a1fec389b7c391512286339b2",
            "2f9fb21a06d4f42e5a3f9b4f479a2bbae2470e4fad0800247aed816d624668e8",
            "9f0560e4daeb139eafe611b4e890a8505ca02ccffa30a293fef24a2a2fd182dd",
            "a80c976b011bdfffc0948d252cf158ce9b75d8c5341b5dc31c36c94a94c3cfaa",
            "3135ee8ffc34df9f5a10ce85170a160e5dfc1c4579c1c1aa1f118318783bbb0f",
            "98717e79648bee52401d5c9ba379bc82b8da50435028313f7d1e93eb48ba4615");
    private static final String V13_FILE = "V13__add_appointment_services.sql";
    private static final String V13_SHA_256 =
            "12526a8c5e5c9fab3abaa647a56da46732539544f066d97c734915da88cace4e";

    private static final BigDecimal MAX_SERVICE_PRICE = new BigDecimal("9999999999.99");

    // ---- the migration files -------------------------------------------------

    @Test
    void v1ThroughV12RemainByteForByteUnchangedAndV13IsPinned() {
        for (int index = 0; index < MIGRATION_FILES.size(); index++) {
            assertThat(sha256(MIGRATION_FILES.get(index)))
                    .as(MIGRATION_FILES.get(index))
                    .isEqualTo(MIGRATION_SHA_256.get(index));
        }
        assertThat(sha256(V13_FILE)).as(V13_FILE).isEqualTo(V13_SHA_256);
    }

    // ---- upgrade, backfill, preservation, file rewrite -----------------------

    @Test
    void upgradingV12BackfillsOneLinePerAppointmentAndPreservesEveryStoredValue() {
        String schema = "v13_upgrade_data";
        PGSimpleDataSource dataSource = dataSource(schema);
        migrate(dataSource, schema, "12");
        JdbcClient upgrade = JdbcClient.create(dataSource);
        List<Legacy> legacy = seedLegacyAppointments(upgrade, schema);
        assertThat(legacy).hasSizeGreaterThanOrEqualTo(9);

        Map<UUID, String> before = rowsAsJson(upgrade, schema, "(to_jsonb(a))");
        Map<UUID, String> priceTextBefore = priceText(upgrade, schema);
        long fileBefore = relfilenode(upgrade, schema, "appointment");
        String priceTypeBefore = priceColumnType(upgrade, schema);

        migrate(dataSource, schema, null);

        assertThat(priceTypeBefore).isEqualTo("numeric(12,2)");
        assertThat(priceColumnType(upgrade, schema)).isEqualTo("numeric(13,2)");
        // Every column that existed before holds exactly the same value; only service_count is new.
        Map<UUID, String> after = rowsAsJson(upgrade, schema, "(to_jsonb(a) - 'service_count')");
        assertThat(after).isEqualTo(before);
        assertThat(priceText(upgrade, schema)).isEqualTo(priceTextBefore);
        assertThat(upgrade.sql("SELECT count(*) FROM " + schema + ".appointment WHERE service_count <> 1")
                .query(Long.class).single()).isZero();

        // Exactly one line per Appointment, copied from the Appointment's own snapshot.
        assertThat(upgrade.sql("SELECT count(*) FROM " + schema + ".appointment_service")
                .query(Long.class).single()).isEqualTo((long) legacy.size());
        assertThat(upgrade.sql("""
                        SELECT count(*)
                        FROM %1$s.appointment a
                        JOIN %1$s.appointment_service l ON l.appointment_id = a.id
                        WHERE l.position = 1
                          AND l.business_id = a.business_id
                          AND l.service_id = a.service_id
                          AND l.service_name = a.service_name
                          AND l.duration_minutes = a.duration_minutes
                          AND l.price_eur = a.price_eur
                          AND l.price_eur::text = a.price_eur::text
                        """.formatted(schema))
                .query(Long.class).single()).isEqualTo((long) legacy.size());

        // The maximum Service price and its neighbours survived as the same text.
        assertThat(priceText(upgrade, schema).values())
                .contains("9999999999.99", "0.00", "0.01", "25.00");

        // The measured physical effect: the table file was not rewritten (reported by this test).
        long fileAfter = relfilenode(upgrade, schema, "appointment");
        assertThat(fileAfter)
                .as("PostgreSQL did not rewrite the appointment table when price_eur was widened")
                .isEqualTo(fileBefore);

        assertThat(upgrade.sql("""
                        SELECT version FROM %s.flyway_schema_history
                        WHERE success = true AND type = 'SQL'
                        ORDER BY installed_rank DESC LIMIT 1
                        """.formatted(schema))
                .query(String.class).single()).isEqualTo("13");
    }

    @Test
    void anUpgradedDatabaseAcceptsAMaximumPriceVisitAndRefusesAnInconsistentOne() {
        String schema = "v13_upgrade_visit";
        PGSimpleDataSource dataSource = dataSource(schema);
        migrate(dataSource, schema, "12");
        JdbcClient upgrade = JdbcClient.create(dataSource);
        seedLegacyAppointments(upgrade, schema);
        migrate(dataSource, schema, null);

        UUID business = upgrade.sql("SELECT id FROM " + schema + ".business LIMIT 1")
                .query(UUID.class).single();
        UUID staff = upgrade.sql("SELECT id FROM " + schema + ".staff_member WHERE business_id = :b LIMIT 1")
                .param("b", business).query(UUID.class).single();
        UUID customer = upgrade.sql("SELECT id FROM " + schema + ".customer WHERE business_id = :b LIMIT 1")
                .param("b", business).query(UUID.class).single();
        List<UUID> services = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            services.add(insertService(upgrade, schema, business, "Услуга " + index));
        }
        Instant start = Instant.parse("2027-03-01T09:00:00Z");
        UUID visit = UUID.randomUUID();
        // One implicit transaction: the visit row and its five lines (the largest possible total).
        executeScript(dataSource, visitScript(
                schema, visit, "AAAAAAAAAA", "49999999999.95", start, business, customer, staff, services));

        assertThat(upgrade.sql("SELECT price_eur::text FROM " + schema + ".appointment WHERE id = :id")
                .param("id", visit).query(String.class).single()).isEqualTo("49999999999.95");

        // The same visit with a total one cent too high is refused at commit and leaves nothing.
        UUID wrong = UUID.randomUUID();
        Throwable failure = catchThrowable(() -> executeScript(dataSource, visitScript(
                schema, wrong, "BBBBBBBBBB", "49999999999.96", start.plusSeconds(86_400),
                business, customer, staff, services)));
        assertThat(sqlStateOf(failure)).isEqualTo("23514");
        assertThat(upgrade.sql("SELECT count(*) FROM " + schema + ".appointment WHERE id = :id")
                .param("id", wrong).query(Long.class).single()).isZero();
        assertThat(upgrade.sql("SELECT count(*) FROM " + schema + ".appointment_service WHERE appointment_id = :id")
                .param("id", wrong).query(Long.class).single()).isZero();
    }

    private static String visitScript(
            String schema, UUID visit, String reference, String total, Instant start, UUID business,
            UUID customer, UUID staff, List<UUID> services) {
        StringBuilder sql = new StringBuilder("""
                INSERT INTO %1$s.appointment(
                    id, business_id, customer_id, service_id, staff_member_id, source, status,
                    start_at, end_at, occupied_until, timezone, duration_minutes, price_eur,
                    service_name, staff_display_name, public_reference, version, created_at,
                    updated_at, service_count)
                VALUES ('%2$s', '%3$s', '%4$s', '%5$s', '%6$s', 'MANUAL', 'CONFIRMED',
                        '%7$s', '%8$s', '%8$s', 'Europe/Sofia', 150, %9$s,
                        'Услуга 0', 'Служител', '%10$s', 0, now(), now(), 5);
                """.formatted(schema, visit, business, customer, services.get(0), staff,
                        utc(start), utc(start.plusSeconds(150 * 60L)), total, reference));
        for (int index = 0; index < 5; index++) {
            sql.append("INSERT INTO %s.appointment_service VALUES ('%s', %d, '%s', '%s', '%s', 30, %s);\n"
                    .formatted(schema, visit, index + 1, business, services.get(index),
                            "Услуга " + index, MAX_SERVICE_PRICE));
        }
        return sql.toString();
    }

    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).as("a PostgreSQL error is expected").isNotNull();
        return ((SQLException) current).getSQLState();
    }

    @Test
    void aControlledCorruptFixtureMakesTheMigrationFailAndLeavesNothingOfV13() {
        String schema = "v13_corrupt_fixture";
        PGSimpleDataSource dataSource = dataSource(schema);
        migrate(dataSource, schema, "12");
        JdbcClient upgrade = JdbcClient.create(dataSource);
        // Legacy data the V11 rule would never have allowed: V11's own name check is removed and a
        // snapshot name with a doubled space is stored. The backfill must refuse to copy it.
        seedLegacyAppointments(upgrade, schema);
        upgrade.sql("ALTER TABLE " + schema
                + ".appointment DROP CONSTRAINT appointment_service_name_canonical").update();
        upgrade.sql("UPDATE " + schema + ".appointment SET service_name = 'Двойни  интервали' "
                + "WHERE id = (SELECT id FROM " + schema + ".appointment LIMIT 1)").update();

        Throwable failure = catchThrowable(() -> migrate(dataSource, schema, null));

        assertThat(failure).isInstanceOf(FlywayException.class);
        assertThat(upgrade.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = :schema AND table_name = 'appointment_service'
                        """).param("schema", schema).query(Long.class).single()).isZero();
        assertThat(upgrade.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = :schema AND table_name = 'appointment'
                          AND column_name = 'service_count'
                        """).param("schema", schema).query(Long.class).single()).isZero();
        assertThat(priceColumnType(upgrade, schema)).isEqualTo("numeric(12,2)");
        assertThat(upgrade.sql("""
                        SELECT count(*) FROM %s.flyway_schema_history
                        WHERE version = '13' AND success = true
                        """.formatted(schema)).query(Long.class).single()).isZero();
    }

    @Test
    void theVerificationBlockRefusesAnAppointmentWithoutItsBackfilledLine() {
        String schema = "v13_verify_block";
        PGSimpleDataSource dataSource = dataSource(schema);
        migrate(dataSource, schema, "12");
        JdbcClient upgrade = JdbcClient.create(dataSource);
        seedLegacyAppointments(upgrade, schema);
        migrate(dataSource, schema, null);
        // Removing the lines (a truncate fires no row trigger) reproduces a failed backfill.
        upgrade.sql("TRUNCATE " + schema + ".appointment_service").update();

        Throwable failure = catchThrowable(() -> executeScript(
                dataSource, "SET search_path TO " + schema + ";\n" + verificationBlock()));

        assertThat(sqlStateOf(failure)).isEqualTo("23514");
        assertThat(failure.getCause().getMessage()).contains("appointment_service backfill is inconsistent");
    }

    @Test
    void anUpgradedSchemaEqualsAFreshInstallation() {
        PGSimpleDataSource upgradedSource = dataSource("v13_equal_upgraded");
        migrate(upgradedSource, "v13_equal_upgraded", "12");
        seedLegacyAppointments(JdbcClient.create(upgradedSource), "v13_equal_upgraded");
        migrate(upgradedSource, "v13_equal_upgraded", null);
        migrate(dataSource("v13_equal_fresh"), "v13_equal_fresh", null);
        JdbcClient catalog = JdbcClient.create(dataSource(null));

        List<String> upgraded = signature(catalog, "v13_equal_upgraded");
        List<String> fresh = signature(catalog, "v13_equal_fresh");

        assertThat(upgraded).isNotEmpty();
        assertThat(upgraded).isEqualTo(fresh);
        assertThat(upgraded).anyMatch(entry -> entry.startsWith("column|appointment_service|position|"));
        assertThat(upgraded).anyMatch(entry -> entry.startsWith("trigger|appointment_service|appointment_service_immutable"));
    }

    // ---- fixtures and helpers ------------------------------------------------

    private record Legacy(UUID id, String name, String price) {
    }

    /**
     * Appointments of every state in a database at V12: CONFIRMED and CANCELLED, ONLINE with
     * idempotency columns and MANUAL without, the extreme and neighbouring prices, a DST-edge visit
     * of the longest duration, and a second Business.
     */
    private List<Legacy> seedLegacyAppointments(JdbcClient db, String schema) {
        List<Legacy> created = new ArrayList<>();
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
            UUID service = insertService(db, schema, business, "Подстригване");
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
                    .param("phone", "+359895" + (100_000 + tenantIndex)).update();

            Instant day = Instant.parse("2026-11-0" + (tenantIndex + 1) + "T06:00:00Z");
            created.add(legacyAppointment(db, schema, business, customer, service, staff,
                    "ONLINE", "CONFIRMED", day, 45, "25.00", "Подстригване", true));
            created.add(legacyAppointment(db, schema, business, customer, service, staff,
                    "ONLINE", "CANCELLED", day.plusSeconds(3600), 30, "37.50", "Подстригване", true));
            created.add(legacyAppointment(db, schema, business, customer, service, staff,
                    "MANUAL", "CONFIRMED", day.plusSeconds(7200), 60, "0.00", "Подстригване", false));
            created.add(legacyAppointment(db, schema, business, customer, service, staff,
                    "MANUAL", "CONFIRMED", day.plusSeconds(14_400), 15, "0.01", "Подстригване", false));
            created.add(legacyAppointment(db, schema, business, customer, service, staff,
                    "ONLINE", "CONFIRMED", day.plusSeconds(18_000), 120,
                    MAX_SERVICE_PRICE.toPlainString(), "Подстригване", true));
            // The clocks go back at 2026-10-25T01:00:00Z; a 480-minute visit crosses it.
            created.add(legacyAppointment(db, schema, business, customer, service, staff,
                    "MANUAL", "CONFIRMED", Instant.parse("2026-10-24T22:30:00Z"), 480,
                    "99.99", "Подстригване", false));
        }
        return created;
    }

    private Legacy legacyAppointment(
            JdbcClient db, String schema, UUID business, UUID customer, UUID service, UUID staff,
            String source, String status, Instant start, int minutes, String price, String name,
            boolean idempotent) {
        UUID id = UUID.randomUUID();
        Instant end = start.plusSeconds(minutes * 60L);
        db.sql("""
                        INSERT INTO %s.appointment(
                            id, business_id, customer_id, service_id, staff_member_id, source, status,
                            start_at, end_at, occupied_until, timezone, duration_minutes, price_eur,
                            service_name, staff_display_name, customer_note, public_reference,
                            booking_attempt_hash, request_fingerprint, fingerprint_encoding_version,
                            fingerprint_key_version, version, created_at, updated_at)
                        VALUES (:id, :b, :customer, :service, :staff, :source, :status,
                                :start, :end, :end, 'Europe/Sofia', :minutes, CAST(:price AS numeric),
                                :name, 'Мария Тестова', NULL, :reference,
                                :hash, :fingerprint, :encoding, :key, 0, now(), now())
                        """.formatted(schema))
                .param("id", id).param("b", business).param("customer", customer)
                .param("service", service).param("staff", staff).param("source", source)
                .param("status", status).param("start", utc(start)).param("end", utc(end))
                .param("minutes", minutes).param("price", price).param("name", name)
                .param("reference", AppointmentFixtures.reference())
                .param("hash", idempotent ? AppointmentFixtures.digest() : null,
                        java.sql.Types.BINARY)
                .param("fingerprint", idempotent ? AppointmentFixtures.digest() : null,
                        java.sql.Types.BINARY)
                .param("encoding", idempotent ? 1 : null, java.sql.Types.SMALLINT)
                .param("key", idempotent ? 1 : null, java.sql.Types.SMALLINT)
                .update();
        return new Legacy(id, name, price);
    }

    private UUID insertService(JdbcClient db, String schema, UUID business, String name) {
        UUID id = UUID.randomUUID();
        db.sql("""
                        INSERT INTO %s.service(
                            id, business_id, name, description, duration_minutes, price, active,
                            version, created_at, updated_at)
                        VALUES (:id, :b, :name, NULL, 30, 10.00, true, 0, now(), now())
                        """.formatted(schema))
                .param("id", id).param("b", business).param("name", name + " " + id).update();
        return id;
    }

    private static Map<UUID, String> rowsAsJson(JdbcClient db, String schema, String expression) {
        Map<UUID, String> rows = new LinkedHashMap<>();
        db.sql("SELECT a.id, " + expression + "::text AS doc FROM " + schema + ".appointment a ORDER BY a.id")
                .query((resultSet, row) -> {
                    rows.put(resultSet.getObject("id", UUID.class), resultSet.getString("doc"));
                    return row;
                }).list();
        return rows;
    }

    private static Map<UUID, String> priceText(JdbcClient db, String schema) {
        Map<UUID, String> rows = new LinkedHashMap<>();
        db.sql("SELECT id, price_eur::text AS price FROM " + schema + ".appointment ORDER BY id")
                .query((resultSet, row) -> {
                    rows.put(resultSet.getObject("id", UUID.class), resultSet.getString("price"));
                    return row;
                }).list();
        return rows;
    }

    private static long relfilenode(JdbcClient db, String schema, String table) {
        return db.sql("SELECT relfilenode::bigint FROM pg_class WHERE oid = CAST(:name AS regclass)")
                .param("name", schema + "." + table).query(Long.class).single();
    }

    private static String priceColumnType(JdbcClient db, String schema) {
        return db.sql("""
                        SELECT format_type(a.atttypid, a.atttypmod)
                        FROM pg_attribute a
                        WHERE a.attrelid = CAST(:name AS regclass) AND a.attname = 'price_eur'
                        """)
                .param("name", schema + ".appointment").query(String.class).single();
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
        try (InputStream stream = AppointmentServiceMigrationIntegrationTests.class
                .getResourceAsStream("/db/migration/" + V13_FILE)) {
            assertThat(stream).isNotNull();
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("DO \\$\\$.*?\\$\\$;", Pattern.DOTALL).matcher(text);
            assertThat(matcher.find()).as("the verification DO block").isTrue();
            return matcher.group();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
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
        try (InputStream stream = AppointmentServiceMigrationIntegrationTests.class
                .getResourceAsStream("/db/migration/" + file)) {
            assertThat(stream).as(file).isNotNull();
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
