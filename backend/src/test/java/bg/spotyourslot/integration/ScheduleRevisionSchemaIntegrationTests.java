package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code V12} schedule revision schema (ADR-0025) against real PostgreSQL: migration integrity,
 * the backfill of existing Businesses, the initialization of every new Business, and the exact
 * structure and constraints. All values are synthetic.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ScheduleRevisionSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String RESTRICT_VIOLATION = "23001";

    private static final Map<String, String> PRIOR_MIGRATIONS = Map.ofEntries(
            Map.entry(
                    "V1__identity_and_tenancy.sql",
                    "68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49"),
            Map.entry(
                    "V2__enforce_single_active_identity_tokens.sql",
                    "c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8"),
            Map.entry(
                    "V3__add_business_profile_fields.sql",
                    "655d22a52c06eb41996a75c100c1bfab853c907b9e0e076f128ba2ab8be674a2"),
            Map.entry(
                    "V4__structure_business_address.sql",
                    "aa48255701e3ce6999073801ca0ed37e292b9ada545e3fe4a50221ada596cb98"),
            Map.entry(
                    "V5__add_business_services.sql",
                    "e2221627ed52ceb951881648d9738d213544ad4c8d79aa35e1e79c0896e958d3"),
            Map.entry(
                    "V6__add_staff_members_and_service_assignments.sql",
                    "73e89120c0163d6ea79ee28f0d59ae02c055df85066234a3688f525ada16ff1b"),
            Map.entry(
                    "V7__add_recurring_staff_working_schedules.sql",
                    "d9a8184b7c7c856064426d79dd375e7b4fb7f55a1fec389b7c391512286339b2"),
            Map.entry(
                    "V8__canonicalize_staff_member_contact_phone.sql",
                    "2f9fb21a06d4f42e5a3f9b4f479a2bbae2470e4fad0800247aed816d624668e8"),
            Map.entry(
                    "V9__add_schedule_exceptions.sql",
                    "9f0560e4daeb139eafe611b4e890a8505ca02ccffa30a293fef24a2a2fd182dd"),
            Map.entry(
                    "V10__add_customers.sql",
                    "a80c976b011bdfffc0948d252cf158ce9b75d8c5341b5dc31c36c94a94c3cfaa"),
            Map.entry(
                    "V11__add_appointments.sql",
                    "3135ee8ffc34df9f5a10ce85170a160e5dfc1c4579c1c1aa1f118318783bbb0f"));
    private static final String V12_FILE = "V12__add_business_schedule_revision.sql";
    private static final String V12_SHA_256 =
            "98717e79648bee52401d5c9ba379bc82b8da50435028313f7d1e93eb48ba4615";

    private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-09-01T08:00:00Z");

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    // ---- migration integrity -------------------------------------------------

    @Test
    void migrationsFromEmptyAreExactlyV1ThroughV12WithV12Newest() {
        List<String> versions = jdbc.sql("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE type = 'SQL' AND success = true
                        ORDER BY installed_rank
                        """)
                .query(String.class)
                .list();

        assertThat(versions).containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    void v1ThroughV11RemainByteForByteUnchangedAndV12IsPinned() {
        PRIOR_MIGRATIONS.forEach((file, expected) ->
                assertThat(sha256(file)).as(file).isEqualTo(expected));
        assertThat(PRIOR_MIGRATIONS).hasSize(11);
        assertThat(sha256(V12_FILE)).isEqualTo(V12_SHA_256);
    }

    // ---- backfill ------------------------------------------------------------

    @Test
    void upgradingV11BackfillsEveryExistingBusinessAtRevisionZeroAndStampsItsCreationTime()
            throws SQLException {
        String schema = "v12_revision_upgrade";
        var dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("11")
                .load()
                .migrate();
        JdbcClient upgrade = JdbcClient.create(dataSource);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        insertBusiness(upgrade, schema + ".business", first, CREATED);
        insertBusiness(upgrade, schema + ".business", second, CREATED.plusDays(3));
        insertBusiness(upgrade, schema + ".business", third, CREATED.plusDays(9));
        assertThat(upgrade.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = :schema AND table_name = 'business_schedule_revision'
                        """)
                .param("schema", schema)
                .query(Long.class).single()).isZero();

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        var rows = upgrade.sql("""
                        SELECT r.business_id, r.revision, r.updated_at, b.created_at
                        FROM v12_revision_upgrade.business_schedule_revision r
                        JOIN v12_revision_upgrade.business b ON b.id = r.business_id
                        ORDER BY b.created_at
                        """)
                .query((resultSet, rowNumber) -> new BackfilledRow(
                        resultSet.getObject("business_id", UUID.class),
                        resultSet.getLong("revision"),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        assertThat(rows).extracting(BackfilledRow::businessId).containsExactly(first, second, third);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.revision()).isZero();
            assertThat(row.updatedAt()).isEqualTo(row.createdAt());
        });
        assertThat(upgrade.sql("SELECT count(*) FROM v12_revision_upgrade.business")
                        .query(Long.class).single()).isEqualTo(3L);
        assertThat(upgrade.sql("""
                        SELECT version
                        FROM v12_revision_upgrade.flyway_schema_history
                        WHERE success = true AND type = 'SQL'
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """)
                .query(String.class).single()).isEqualTo("12");

        // The trigger resolves the revision table through the session search path, which Flyway
        // configured for its own connection only; a plain connection selects the upgraded schema.
        UUID created = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO business(
                        id, slug, display_name, business_type, status, timezone,
                        created_at, updated_at)
                    VALUES (?, ?, 'Upgrade Business', 'OTHER', 'DRAFT', 'Europe/Sofia', ?, ?)
                    """)) {
                insert.setObject(1, created);
                insert.setString(2, "upgrade-" + created);
                insert.setObject(3, CREATED.plusDays(20));
                insert.setObject(4, CREATED.plusDays(20));
                insert.executeUpdate();
            }
        }
        assertThat(upgrade.sql("""
                        SELECT revision FROM v12_revision_upgrade.business_schedule_revision
                        WHERE business_id = :id
                        """)
                .param("id", created)
                .query(Long.class).single()).isZero();
    }

    // ---- new businesses ------------------------------------------------------

    @Test
    void everyNewBusinessReceivesItsRevisionRowAtZeroInTheSameStatement() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES
                            (:first, :firstSlug, 'First', 'OTHER', 'DRAFT', 'Europe/Sofia',
                             :firstCreated, :firstCreated),
                            (:second, :secondSlug, 'Second', 'OTHER', 'ACTIVE', 'Europe/Sofia',
                             :secondCreated, :secondCreated)
                        """)
                .param("first", first)
                .param("firstSlug", "revision-" + first)
                .param("firstCreated", CREATED)
                .param("second", second)
                .param("secondSlug", "revision-" + second)
                .param("secondCreated", CREATED.plusHours(5))
                .update();

        assertThat(revisionOf(first)).isZero();
        assertThat(revisionOf(second)).isZero();
        assertThat(updatedAtOf(first)).isEqualTo(CREATED);
        assertThat(updatedAtOf(second)).isEqualTo(CREATED.plusHours(5));
        assertThat(count("business_schedule_revision")).isEqualTo(count("business")).isEqualTo(2L);
    }

    @Test
    void aRolledBackBusinessInsertLeavesNoRevisionRow() {
        UUID id = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertBusiness(jdbc, "business", id, CREATED);
            assertThat(revisionOf(id)).isZero();
            status.setRollbackOnly();
        });

        assertThat(count("business")).isZero();
        assertThat(count("business_schedule_revision")).isZero();
    }

    // ---- structure and constraints -------------------------------------------

    @Test
    void theTableHasExactlyTheApprovedColumnsTypesAndNullability() {
        var columns = jdbc.sql("""
                        SELECT column_name, data_type, is_nullable, column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'business_schedule_revision'
                        ORDER BY ordinal_position
                        """)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        resultSet.getString("is_nullable"),
                        String.valueOf(resultSet.getString("column_default"))))
                .list();

        assertThat(columns).containsExactly(
                List.of("business_id", "uuid", "NO", "null"),
                List.of("revision", "bigint", "NO", "0"),
                List.of("updated_at", "timestamp with time zone", "NO", "null"));
    }

    @Test
    void theTableHasExactlyThePrimaryKeyTheRestrictiveForeignKeyAndTheNonnegativeCheck() {
        var constraints = jdbc.sql("""
                        SELECT conname, pg_get_constraintdef(oid) AS definition
                        FROM pg_constraint
                        WHERE conrelid = 'public.business_schedule_revision'::regclass
                          AND contype IN ('p', 'f', 'c', 'u', 'x')
                        ORDER BY conname
                        """)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString("conname"), resultSet.getString("definition")))
                .list();

        assertThat(constraints).containsExactly(
                List.of(
                        "business_schedule_revision_business_fk",
                        "FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT"),
                List.of(
                        "business_schedule_revision_nonnegative",
                        "CHECK ((revision >= 0))"),
                List.of(
                        "business_schedule_revision_pkey",
                        "PRIMARY KEY (business_id)"));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_indexes
                        WHERE schemaname = 'public' AND tablename = 'business_schedule_revision'
                        """)
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void theInitializationTriggerIsAnEnabledAfterInsertRowTriggerOnBusiness() {
        var triggers = jdbc.sql("""
                        SELECT tgname, tgenabled, pg_get_triggerdef(oid) AS definition
                        FROM pg_trigger
                        WHERE tgrelid = 'public.business'::regclass AND NOT tgisinternal
                        """)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString("tgname"),
                        resultSet.getString("tgenabled"),
                        resultSet.getString("definition")))
                .list();

        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).get(0)).isEqualTo("business_create_schedule_revision");
        assertThat(triggers.get(0).get(1)).isEqualTo("O");
        assertThat(triggers.get(0).get(2))
                .contains("AFTER INSERT ON public.business")
                .contains("FOR EACH ROW")
                .contains("create_business_schedule_revision()");
    }

    @Test
    void theDatabaseRejectsEveryInvalidRevisionRowWithItsOwnConstraint() {
        UUID business = UUID.randomUUID();
        insertBusiness(jdbc, "business", business, CREATED);

        assertViolation(
                "INSERT INTO business_schedule_revision(business_id, revision, updated_at) "
                        + "VALUES ('" + business + "', 0, now())",
                UNIQUE_VIOLATION,
                "business_schedule_revision_pkey");
        assertViolation(
                "UPDATE business_schedule_revision SET revision = -1 WHERE business_id = '"
                        + business + "'",
                CHECK_VIOLATION,
                "business_schedule_revision_nonnegative");
        assertViolation(
                "INSERT INTO business_schedule_revision(business_id, revision, updated_at) "
                        + "VALUES ('" + UUID.randomUUID() + "', 0, now())",
                FOREIGN_KEY_VIOLATION,
                "business_schedule_revision_business_fk");
        assertViolation(
                "UPDATE business_schedule_revision SET revision = NULL WHERE business_id = '"
                        + business + "'",
                NOT_NULL_VIOLATION,
                null);
        assertViolation(
                "UPDATE business_schedule_revision SET updated_at = NULL WHERE business_id = '"
                        + business + "'",
                NOT_NULL_VIOLATION,
                null);
        assertViolation(
                "DELETE FROM business WHERE id = '" + business + "'",
                RESTRICT_VIOLATION,
                "business_schedule_revision_business_fk");
        assertThat(revisionOf(business)).isZero();
    }

    // ---- helpers -------------------------------------------------------------

    private void assertViolation(String sql, String sqlState, String constraint) {
        Throwable failure = catchThrowable(() -> jdbc.sql(sql).update());
        Throwable current = failure;
        while (current != null && !(current instanceof PSQLException)) {
            current = current.getCause();
        }
        assertThat(current).as("a PostgreSQL error is expected for: " + sql).isNotNull();
        PSQLException postgres = (PSQLException) current;
        assertThat(postgres.getSQLState()).isEqualTo(sqlState);
        if (constraint != null) {
            assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        }
    }

    private long revisionOf(UUID businessId) {
        return jdbc.sql("SELECT revision FROM business_schedule_revision WHERE business_id = :id")
                .param("id", businessId)
                .query(Long.class)
                .single();
    }

    private OffsetDateTime updatedAtOf(UUID businessId) {
        return jdbc.sql("SELECT updated_at FROM business_schedule_revision WHERE business_id = :id")
                .param("id", businessId)
                .query((resultSet, rowNumber) ->
                        resultSet.getObject("updated_at", OffsetDateTime.class)
                                .withOffsetSameInstant(ZoneOffset.UTC))
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static void insertBusiness(
            JdbcClient client, String table, UUID id, OffsetDateTime createdAt) {
        client.sql("INSERT INTO " + table + """
                        (id, slug, display_name, business_type, status, timezone,
                         created_at, updated_at)
                        VALUES (:id, :slug, 'Revision Business', 'OTHER', 'ACTIVE',
                                'Europe/Sofia', :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("slug", "revision-" + id)
                .param("createdAt", createdAt)
                .update();
    }

    private static String sha256(String file) {
        try (InputStream stream = ScheduleRevisionSchemaIntegrationTests.class
                .getResourceAsStream("/db/migration/" + file)) {
            assertThat(stream).as(file).isNotNull();
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record BackfilledRow(
            UUID businessId, long revision, Instant updatedAt, Instant createdAt) {
    }
}
