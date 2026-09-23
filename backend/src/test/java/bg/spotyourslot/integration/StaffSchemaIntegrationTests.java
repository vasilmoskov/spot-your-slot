package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StaffSchemaIntegrationTests extends PostgresIntegrationTest {
    @Autowired
    JdbcClient jdbc;

    @Test
    void generatedDisplayNameUsesApprovedNormalizationAndCollation() {
        var metadata = jdbc.sql("""
                        SELECT a.attgenerated,
                               c.collname,
                               pg_catalog.pg_get_expr(d.adbin, d.adrelid)
                                   AS expression
                        FROM pg_catalog.pg_attribute a
                        JOIN pg_catalog.pg_class t ON t.oid = a.attrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                        JOIN pg_catalog.pg_collation c ON c.oid = a.attcollation
                        JOIN pg_catalog.pg_attrdef d
                          ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                        WHERE n.nspname = 'public'
                          AND t.relname = 'staff_member'
                          AND a.attname = 'normalized_display_name'
                        """)
                .query((resultSet, rowNumber) -> new GeneratedColumnMetadata(
                        resultSet.getString("attgenerated"),
                        resultSet.getString("collname"),
                        resultSet.getString("expression")))
                .single();

        assertThat(metadata.generatedKind()).isEqualTo("s");
        assertThat(metadata.collation()).isEqualTo("pg_unicode_fast");
        assertThat(metadata.expression())
                .contains("\"normalize\"(")
                .contains("casefold(")
                .contains("COLLATE pg_unicode_fast")
                .contains("regexp_replace(")
                .contains("NFKC");
    }

    @Test
    void duplicateNormalizedDisplayNamesAreAllowed() {
        UUID businessId = createBusiness();
        UUID first = insertStaff(businessId, "Straße", true, null, null, 0);
        UUID second = insertStaff(businessId, "STRASSE", true, null, null, 0);

        List<String> normalized = jdbc.sql("""
                        SELECT normalized_display_name
                        FROM staff_member
                        WHERE id IN (:first, :second)
                        ORDER BY id
                        """)
                .param("first", first)
                .param("second", second)
                .query(String.class)
                .list();

        assertThat(normalized).containsExactly("strasse", "strasse");
    }

    @Test
    void displayNameMustBeCanonicalNonblankAndWithinTwoHundredCharacters() {
        UUID businessId = createBusiness();

        insertStaff(businessId, "я".repeat(200), true, null, null, 0);
        assertRejected(() -> insertStaff(
                businessId, "я".repeat(201), true, null, null, 0));
        assertRejected(() -> insertStaff(businessId, "", true, null, null, 0));
        assertRejected(() -> insertStaff(businessId, " Име", true, null, null, 0));
        assertRejected(() -> insertStaff(
                businessId, "Две   имена", true, null, null, 0));
        assertRejected(() -> insertStaff(businessId, "ＡＢＣ", true, null, null, 0));
    }

    @Test
    void canonicalOptionalContactsAreStoredAndMayBeDuplicated() {
        UUID businessId = createBusiness();
        String email = "team@example.invalid";
        String phone = "+359 (2) 123-45-67";

        insertStaff(businessId, "Първи", true, email, phone, 0);
        insertStaff(businessId, "Втори", true, email, phone, 0);

        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM staff_member
                        WHERE business_id = :businessId
                          AND contact_email = :email
                          AND contact_phone = :phone
                        """)
                .param("businessId", businessId)
                .param("email", email)
                .param("phone", phone)
                .query(Long.class)
                .single())
                .isEqualTo(2);
    }

    @Test
    void databaseRejectsNoncanonicalEmailAndInvalidPhoneStorage() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertStaff(
                businessId, "Имейл", true, "Team@example.invalid", null, 0));
        assertRejected(() -> insertStaff(
                businessId, "Интервал", true, " team@example.invalid", null, 0));
        assertRejected(() -> insertStaff(
                businessId, "Пълна ширина", true, "ｔｅａｍ@example.invalid", null, 0));
        assertRejected(() -> insertStaff(
                businessId, "Телефон букви", true, null, "+359 abc", 0));
        assertRejected(() -> insertStaff(
                businessId, "Кратък телефон", true, null, "+12", 0));
        assertRejected(() -> insertStaff(
                businessId, "Дълъг телефон", true, null, "1".repeat(21), 0));
        assertRejected(() -> insertStaff(
                businessId, "Интервал телефон", true, null, " +359123", 0));
    }

    @Test
    void defaultsTimestampsAndNonnegativeVersionAreEnforced() {
        UUID businessId = createBusiness();
        UUID staffId = UUID.randomUUID();
        OffsetDateTime now = now();

        jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, created_at, updated_at)
                        VALUES (:id, :businessId, 'Член на екипа', :now, :now)
                        """)
                .param("id", staffId)
                .param("businessId", businessId)
                .param("now", now)
                .update();

        var defaults = jdbc.sql("""
                        SELECT active, version
                        FROM staff_member
                        WHERE id = :id
                        """)
                .param("id", staffId)
                .query((resultSet, rowNumber) -> new StaffDefaults(
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version")))
                .single();

        assertThat(defaults).isEqualTo(new StaffDefaults(true, 0));
        assertRejected(() -> insertStaff(
                businessId, "Невалидна версия", true, null, null, -1));
        assertRejected(() -> jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, created_at, updated_at)
                        VALUES (:id, :businessId, 'Без време', NULL, NULL)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .update());
    }

    @Test
    void compositeForeignKeysRejectCrossBusinessAssignments() {
        UUID firstBusiness = createBusiness();
        UUID secondBusiness = createBusiness();
        UUID firstStaff = insertStaff(firstBusiness, "Първи екип", true, null, null, 0);
        UUID firstService = insertService(firstBusiness, "Първа услуга", true);
        UUID secondStaff = insertStaff(secondBusiness, "Втори екип", true, null, null, 0);
        UUID secondService = insertService(secondBusiness, "Втора услуга", true);

        insertAssignment(firstBusiness, firstStaff, firstService);
        assertRejected(() -> insertAssignment(firstBusiness, firstStaff, secondService));
        assertRejected(() -> insertAssignment(secondBusiness, firstStaff, secondService));
        assertRejected(() -> insertAssignment(firstBusiness, secondStaff, firstService));
    }

    @Test
    void duplicateStaffServiceRelationshipsAreRejected() {
        UUID businessId = createBusiness();
        UUID staffId = insertStaff(businessId, "Екип", true, null, null, 0);
        UUID serviceId = insertService(businessId, "Услуга", true);

        insertAssignment(businessId, staffId, serviceId);

        assertRejected(() -> insertAssignment(businessId, staffId, serviceId));
    }

    @Test
    void inactiveStaffAndServicesMayRetainAssignments() {
        UUID businessId = createBusiness();
        UUID staffId = insertStaff(businessId, "Неактивен екип", false, null, null, 0);
        UUID serviceId = insertService(businessId, "Неактивна услуга", false);

        insertAssignment(businessId, staffId, serviceId);

        assertThat(assignmentCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT active FROM staff_member WHERE id = :id")
                        .param("id", staffId)
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("SELECT active FROM service WHERE id = :id")
                        .param("id", serviceId)
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    @Test
    void assignedStaffServiceAndBusinessDeletionAreRestrictive() {
        UUID businessId = createBusiness();
        UUID staffId = insertStaff(businessId, "Защитен екип", true, null, null, 0);
        UUID serviceId = insertService(businessId, "Защитена услуга", true);
        insertAssignment(businessId, staffId, serviceId);

        assertRejected(() -> jdbc.sql("DELETE FROM staff_member WHERE id = :id")
                .param("id", staffId)
                .update());
        assertRejected(() -> jdbc.sql("DELETE FROM service WHERE id = :id")
                .param("id", serviceId)
                .update());
        assertRejected(() -> jdbc.sql("DELETE FROM business WHERE id = :id")
                .param("id", businessId)
                .update());
    }

    @Test
    void staffMemberColumnsMatchApprovedSchema() {
        List<ColumnMetadata> columns = columns("staff_member");

        assertThat(columns).containsExactly(
                column("id", "uuid", "uuid", null, null, null, "NO", null, "NEVER", null),
                column("business_id", "uuid", "uuid", null, null, null, "NO", null, "NEVER", null),
                column("display_name", "character varying", "varchar", 200, null, null, "NO", null, "NEVER", null),
                column("normalized_display_name", "text", "text", null, null, null, "YES", null, "ALWAYS", "pg_unicode_fast"),
                column("contact_email", "character varying", "varchar", 320, null, null, "YES", null, "NEVER", null),
                column("contact_phone", "character varying", "varchar", 50, null, null, "YES", null, "NEVER", null),
                column("active", "boolean", "bool", null, null, null, "NO", "true", "NEVER", null),
                column("version", "bigint", "int8", null, 64, 0, "NO", "0", "NEVER", null),
                column("created_at", "timestamp with time zone", "timestamptz", null, null, null, "NO", null, "NEVER", null),
                column("updated_at", "timestamp with time zone", "timestamptz", null, null, null, "NO", null, "NEVER", null));
    }

    @Test
    void assignmentColumnsMatchApprovedSchema() {
        assertThat(columns("staff_member_service")).containsExactly(
                column("business_id", "uuid", "uuid", null, null, null, "NO", null, "NEVER", null),
                column("staff_member_id", "uuid", "uuid", null, null, null, "NO", null, "NEVER", null),
                column("service_id", "uuid", "uuid", null, null, null, "NO", null, "NEVER", null));
    }

    @Test
    void constraintsMatchApprovedSchemaWithoutActivityRules() {
        assertThat(constraints("staff_member")).containsExactly(
                "staff_member_business_fk",
                "staff_member_business_id_id_unique",
                "staff_member_contact_email_canonical",
                "staff_member_contact_phone_canonical",
                "staff_member_display_name_canonical",
                "staff_member_display_name_not_blank",
                "staff_member_pkey",
                "staff_member_version_nonnegative");
        assertThat(constraints("staff_member_service")).containsExactly(
                "staff_member_service_pkey",
                "staff_member_service_service_fk",
                "staff_member_service_staff_member_fk");
    }

    @Test
    void indexInventoryMatchesApprovedSchema() {
        assertThat(indexes("staff_member")).containsExactly(
                new IndexMetadata(
                        "staff_member_business_id_id_unique",
                        "CREATE UNIQUE INDEX staff_member_business_id_id_unique "
                                + "ON public.staff_member USING btree (business_id, id)"),
                new IndexMetadata(
                        "staff_member_business_normalized_display_name_id_idx",
                        "CREATE INDEX staff_member_business_normalized_display_name_id_idx "
                                + "ON public.staff_member USING btree "
                                + "(business_id, normalized_display_name, id)"),
                new IndexMetadata(
                        "staff_member_pkey",
                        "CREATE UNIQUE INDEX staff_member_pkey "
                                + "ON public.staff_member USING btree (id)"));
        assertThat(indexes("staff_member_service")).containsExactly(
                new IndexMetadata(
                        "staff_member_service_business_service_staff_idx",
                        "CREATE INDEX staff_member_service_business_service_staff_idx "
                                + "ON public.staff_member_service USING btree "
                                + "(business_id, service_id, staff_member_id)"),
                new IndexMetadata(
                        "staff_member_service_pkey",
                        "CREATE UNIQUE INDEX staff_member_service_pkey "
                                + "ON public.staff_member_service USING btree "
                                + "(business_id, staff_member_id, service_id)"));
    }

    @Test
    void recurringSchedulesAddNoMembershipLinkOrLaterWorkforceTables() {
        assertThat(columns("staff_member"))
                .extracting(ColumnMetadata::name)
                .doesNotContain("membership_id");

        List<String> tables = jdbc.sql("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                        ORDER BY table_name
                        """)
                .query(String.class)
                .list();

        assertThat(tables)
                .contains(
                        "staff_member",
                        "staff_member_service",
                        "staff_working_schedule",
                        "staff_working_period")
                .doesNotContain(
                        "weekly_work_interval",
                        "schedule_break",
                        "time_off",
                        "working_override",
                        "appointment",
                        "customer");
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Staff Schema Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "staff-schema-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertStaff(
            UUID businessId,
            String displayName,
            boolean active,
            String contactEmail,
            String contactPhone,
            long version) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, contact_email, contact_phone,
                            active, version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :displayName, :contactEmail, :contactPhone,
                            :active, :version, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("displayName", displayName)
                .param("contactEmail", contactEmail)
                .param("contactPhone", contactPhone)
                .param("active", active)
                .param("version", version)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertService(UUID businessId, String name, boolean active) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.sql("""
                        INSERT INTO service(
                            id, business_id, name, duration_minutes, price,
                            active, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :name, 30, :price,
                            :active, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", name)
                .param("price", new BigDecimal("20.00"))
                .param("active", active)
                .param("now", now)
                .update();
        return id;
    }

    private void insertAssignment(UUID businessId, UUID staffId, UUID serviceId) {
        jdbc.sql("""
                        INSERT INTO staff_member_service(
                            business_id, staff_member_id, service_id)
                        VALUES (:businessId, :staffId, :serviceId)
                        """)
                .param("businessId", businessId)
                .param("staffId", staffId)
                .param("serviceId", serviceId)
                .update();
    }

    private long assignmentCount() {
        return jdbc.sql("SELECT count(*) FROM staff_member_service")
                .query(Long.class)
                .single();
    }

    private List<ColumnMetadata> columns(String table) {
        return jdbc.sql("""
                        SELECT column_name, data_type, udt_name,
                               character_maximum_length, numeric_precision, numeric_scale,
                               is_nullable, column_default, is_generated, collation_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = :table
                        ORDER BY ordinal_position
                        """)
                .param("table", table)
                .query((resultSet, rowNumber) -> new ColumnMetadata(
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        resultSet.getString("udt_name"),
                        (Integer) resultSet.getObject("character_maximum_length"),
                        (Integer) resultSet.getObject("numeric_precision"),
                        (Integer) resultSet.getObject("numeric_scale"),
                        resultSet.getString("is_nullable"),
                        resultSet.getString("column_default"),
                        resultSet.getString("is_generated"),
                        resultSet.getString("collation_name")))
                .list();
    }

    private List<String> constraints(String table) {
        return jdbc.sql("""
                        SELECT constraint_definition.conname
                        FROM pg_catalog.pg_constraint constraint_definition
                        JOIN pg_catalog.pg_class table_definition
                          ON table_definition.oid = constraint_definition.conrelid
                        JOIN pg_catalog.pg_namespace namespace
                          ON namespace.oid = table_definition.relnamespace
                        WHERE namespace.nspname = 'public'
                          AND table_definition.relname = :table
                          AND constraint_definition.contype <> 'n'
                        ORDER BY constraint_definition.conname
                        """)
                .param("table", table)
                .query(String.class)
                .list();
    }

    private List<IndexMetadata> indexes(String table) {
        return jdbc.sql("""
                        SELECT indexname, indexdef
                        FROM pg_catalog.pg_indexes
                        WHERE schemaname = 'public' AND tablename = :table
                        ORDER BY indexname
                        """)
                .param("table", table)
                .query((resultSet, rowNumber) -> new IndexMetadata(
                        resultSet.getString("indexname"),
                        resultSet.getString("indexdef")))
                .list();
    }

    private void assertRejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static ColumnMetadata column(
            String name,
            String dataType,
            String underlyingType,
            Integer maximumLength,
            Integer numericPrecision,
            Integer numericScale,
            String nullable,
            String defaultValue,
            String generated,
            String collation) {
        return new ColumnMetadata(
                name,
                dataType,
                underlyingType,
                maximumLength,
                numericPrecision,
                numericScale,
                nullable,
                defaultValue,
                generated,
                collation);
    }

    private record GeneratedColumnMetadata(
            String generatedKind,
            String collation,
            String expression) {
    }

    private record StaffDefaults(boolean active, long version) {
    }

    private record IndexMetadata(String name, String definition) {
    }

    private record ColumnMetadata(
            String name,
            String dataType,
            String underlyingType,
            Integer maximumLength,
            Integer numericPrecision,
            Integer numericScale,
            String nullable,
            String defaultValue,
            String generated,
            String collation) {
    }
}
