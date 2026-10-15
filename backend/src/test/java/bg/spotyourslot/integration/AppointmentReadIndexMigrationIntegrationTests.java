package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The {@code V15} read indexes (issue #21 Phase 3) on real PostgreSQL: V1 to V14 stay byte for
 * byte and V15 is pinned, the migration adds exactly the two justified indexes and nothing else,
 * the table file and every row are untouched by the upgrade, and an upgraded schema equals a fresh
 * installation. Every schema is disposable; nothing touches the development database.
 */
class AppointmentReadIndexMigrationIntegrationTests extends PostgresIntegrationTest {
    private static final Map<String, String> PINNED = pinned();
    private static final String V15_FILE = "V15__add_appointment_read_indexes.sql";
    private static final String V15_SHA_256 =
            "f1da2f5e12dc17ead9f8bd99b3d677f0a5011be1dc09052cb535c6059dac46a6";

    private static Map<String, String> pinned() {
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
        files.put("V14__add_appointment_administration.sql",
                "5ad82495e8804188337cf28668715e88964e6edf97cd964282f6ee8cc845ced3");
        return files;
    }

    @Test
    void v1ThroughV14RemainByteForByteUnchangedAndV15IsPinned() {
        PINNED.forEach((file, expected) -> assertThat(sha256(file)).as(file).isEqualTo(expected));
        assertThat(PINNED).hasSize(14);
        assertThat(sha256(V15_FILE)).isEqualTo(V15_SHA_256);
    }

    @Test
    void theMigrationCreatesExactlyTheTwoApprovedIndexesAndNothingElse() {
        String schema = "v15_indexes";
        PGSimpleDataSource dataSource = dataSource(schema);
        migrate(dataSource, schema, "14");
        JdbcClient db = JdbcClient.create(dataSource);
        List<String> before = indexes(db, schema);

        migrate(dataSource, schema, null);
        List<String> after = indexes(db, schema);

        List<String> added = new ArrayList<>(after);
        added.removeAll(before);
        assertThat(after).containsAll(before);
        assertThat(added).containsExactlyInAnyOrder(
                "appointment_business_start_idx|CREATE INDEX appointment_business_start_idx ON SCHEMA.appointment "
                        + "USING btree (business_id, start_at, id)",
                "appointment_business_customer_start_idx|CREATE INDEX appointment_business_customer_start_idx "
                        + "ON SCHEMA.appointment USING btree (business_id, customer_id, start_at DESC, id DESC)");
        assertThat(db.sql("""
                        SELECT version FROM %s.flyway_schema_history
                        WHERE success = true AND type = 'SQL' ORDER BY installed_rank DESC LIMIT 1
                        """.formatted(schema)).query(String.class).single()).isEqualTo("15");
    }

    @Test
    void upgradingV14LeavesTheTableFileAndEveryRowUntouched() {
        String schema = "v15_untouched";
        PGSimpleDataSource dataSource = dataSource(schema);
        migrate(dataSource, schema, "14");
        JdbcClient db = JdbcClient.create(dataSource);
        UUID business = UUID.randomUUID();
        db.sql("""
                        INSERT INTO %s.business(id,slug,display_name,business_type,status,timezone,created_at,updated_at)
                        VALUES (:id,:slug,'Upgrade Business','OTHER','ACTIVE','Europe/Sofia',now(),now())
                        """.formatted(schema)).param("id", business).param("slug", "up-" + business).update();
        long fileBefore = relfilenode(db, schema);
        String businessBefore = db.sql("SELECT to_jsonb(b)::text || b.xmin::text FROM %s.business b".formatted(schema))
                .query(String.class).single();

        migrate(dataSource, schema, null);

        assertThat(relfilenode(db, schema)).as("CREATE INDEX does not replace the table file").isEqualTo(fileBefore);
        assertThat(db.sql("SELECT to_jsonb(b)::text || b.xmin::text FROM %s.business b".formatted(schema))
                .query(String.class).single()).isEqualTo(businessBefore);
        assertThat(db.sql("SELECT count(*) FROM %s.appointment".formatted(schema)).query(Long.class).single()).isZero();
    }

    @Test
    void anUpgradedSchemaEqualsAFreshInstallation() {
        PGSimpleDataSource upgraded = dataSource("v15_equal_upgraded");
        migrate(upgraded, "v15_equal_upgraded", "14");
        migrate(upgraded, "v15_equal_upgraded", null);
        migrate(dataSource("v15_equal_fresh"), "v15_equal_fresh", null);
        JdbcClient catalog = JdbcClient.create(dataSource(null));

        assertThat(indexes(catalog, "v15_equal_upgraded")).isNotEmpty()
                .isEqualTo(indexes(catalog, "v15_equal_fresh"));
    }

    private static List<String> indexes(JdbcClient db, String schema) {
        return db.sql("""
                        SELECT indexname || '|' || replace(indexdef, :prefix, 'SCHEMA.')
                        FROM pg_indexes WHERE schemaname = :schema AND tablename = 'appointment'
                        ORDER BY indexname
                        """).param("schema", schema).param("prefix", schema + ".")
                .query(String.class).list();
    }

    private static long relfilenode(JdbcClient db, String schema) {
        return db.sql("SELECT relfilenode::bigint FROM pg_class WHERE oid = CAST(:name AS regclass)")
                .param("name", schema + ".business").query(Long.class).single();
    }

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
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static String sha256(String file) {
        try (InputStream stream = AppointmentReadIndexMigrationIntegrationTests.class
                .getResourceAsStream("/db/migration/" + file)) {
            assertThat(stream).as(file).isNotNull();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
