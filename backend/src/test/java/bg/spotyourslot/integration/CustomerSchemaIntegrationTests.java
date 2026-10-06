package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String RESTRICT_VIOLATION = "23001";
    private static final String VALUE_TOO_LONG = "22001";

    private static final List<String> MIGRATION_SHA_256 = List.of(
            "68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49",
            "c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8",
            "655d22a52c06eb41996a75c100c1bfab853c907b9e0e076f128ba2ab8be674a2",
            "aa48255701e3ce6999073801ca0ed37e292b9ada545e3fe4a50221ada596cb98",
            "e2221627ed52ceb951881648d9738d213544ad4c8d79aa35e1e79c0896e958d3",
            "73e89120c0163d6ea79ee28f0d59ae02c055df85066234a3688f525ada16ff1b",
            "d9a8184b7c7c856064426d79dd375e7b4fb7f55a1fec389b7c391512286339b2",
            "2f9fb21a06d4f42e5a3f9b4f479a2bbae2470e4fad0800247aed816d624668e8",
            "9f0560e4daeb139eafe611b4e890a8505ca02ccffa30a293fef24a2a2fd182dd");
    private static final List<String> MIGRATION_FILES = List.of(
            "V1__identity_and_tenancy.sql",
            "V2__enforce_single_active_identity_tokens.sql",
            "V3__add_business_profile_fields.sql",
            "V4__structure_business_address.sql",
            "V5__add_business_services.sql",
            "V6__add_staff_members_and_service_assignments.sql",
            "V7__add_recurring_staff_working_schedules.sql",
            "V8__canonicalize_staff_member_contact_phone.sql",
            "V9__add_schedule_exceptions.sql");
    private static final String V10_SHA_256 = "a80c976b011bdfffc0948d252cf158ce9b75d8c5341b5dc31c36c94a94c3cfaa";

    private static final String PHONE = "+359895555777";
    private static final String OTHER_PHONE = "+359888123456";
    private static final String EMAIL = "ime@primer.bg";
    private static final String OTHER_EMAIL = "druga@primer.bg";

    @Autowired
    JdbcClient jdbc;

    // ---- migration integrity -------------------------------------------------

    @Test
    void migrationsFromEmptyAreExactlyV1ThroughV11() {
        List<String> versions = jdbc.sql("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE type = 'SQL' AND success = true
                        ORDER BY installed_rank
                        """)
                .query(String.class)
                .list();

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(MIGRATION_FILES).hasSize(9);
    }

    @Test
    void v1ThroughV9RemainByteForByteUnchangedAndV10IsPinned() {
        for (int index = 0; index < MIGRATION_FILES.size(); index++) {
            assertThat(sha256(MIGRATION_FILES.get(index)))
                    .as(MIGRATION_FILES.get(index))
                    .isEqualTo(MIGRATION_SHA_256.get(index));
        }
        assertThat(sha256("V10__add_customers.sql")).isEqualTo(V10_SHA_256);
    }

    @Test
    void upgradingV9PreservesExistingDataAndAddsAnEmptyUsableCustomerTable() throws Exception {
        String schema = "v10_customer_upgrade";
        var dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .target("9")
                .load()
                .migrate();

        UUID businessId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T08:00:00Z");
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var business = connection.prepareStatement("""
                    INSERT INTO v10_customer_upgrade.business(
                        id, slug, display_name, business_type, status, timezone,
                        created_at, updated_at)
                    VALUES (?, ?, 'Upgrade Business', 'OTHER', 'ACTIVE',
                            'Europe/Sofia', ?, ?)
                    """)) {
                business.setObject(1, businessId);
                business.setString(2, "upgrade-" + businessId);
                business.setObject(3, createdAt);
                business.setObject(4, createdAt);
                business.executeUpdate();
            }
            try (var staff = connection.prepareStatement("""
                    INSERT INTO v10_customer_upgrade.staff_member(
                        id, business_id, display_name, contact_phone, active, version,
                        created_at, updated_at)
                    VALUES (?, ?, 'Съществуващ', '+359895555777', true, 0, ?, ?)
                    """)) {
                staff.setObject(1, staffId);
                staff.setObject(2, businessId);
                staff.setObject(3, createdAt);
                staff.setObject(4, createdAt);
                staff.executeUpdate();
            }
            try (var service = connection.prepareStatement("""
                    INSERT INTO v10_customer_upgrade.service(
                        id, business_id, name, duration_minutes, price, active, version,
                        created_at, updated_at)
                    VALUES (?, ?, 'Съществуваща услуга', 30, 20.00, true, 0, ?, ?)
                    """)) {
                service.setObject(1, serviceId);
                service.setObject(2, businessId);
                service.setObject(3, createdAt);
                service.setObject(4, createdAt);
                service.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.prepareStatement("""
                            SELECT (SELECT count(*) FROM v10_customer_upgrade.business),
                                   (SELECT count(*) FROM v10_customer_upgrade.staff_member),
                                   (SELECT contact_phone FROM v10_customer_upgrade.staff_member),
                                   (SELECT count(*) FROM v10_customer_upgrade.service),
                                   (SELECT count(*) FROM v10_customer_upgrade.customer),
                                   (SELECT max(version::integer)
                                    FROM v10_customer_upgrade.flyway_schema_history
                                    WHERE type = 'SQL')
                            """);
                    var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
                assertThat(result.getLong(2)).isEqualTo(1);
                assertThat(result.getString(3)).isEqualTo(PHONE);
                assertThat(result.getLong(4)).isEqualTo(1);
                assertThat(result.getLong(5)).isZero();
                assertThat(result.getInt(6)).isEqualTo(11);
            }
            try (var customer = connection.prepareStatement("""
                    INSERT INTO v10_customer_upgrade.customer(
                        id, business_id, display_name, phone, version, created_at, updated_at)
                    VALUES (?, ?, 'Клиент', '+359888123456', 0, ?, ?)
                    """)) {
                customer.setObject(1, UUID.randomUUID());
                customer.setObject(2, businessId);
                customer.setObject(3, createdAt);
                customer.setObject(4, createdAt);
                assertThat(customer.executeUpdate()).isEqualTo(1);
            }
        }
    }

    // ---- schema shape --------------------------------------------------------

    @Test
    void customerColumnsMatchTheApprovedSchemaAndNothingMore() {
        List<String> columns = jdbc.sql("""
                        SELECT column_name || ':' || data_type
                               || ':' || coalesce(character_maximum_length::text, '')
                               || ':' || is_nullable
                               || ':' || coalesce(column_default, '')
                               || ':' || is_generated
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'customer'
                        ORDER BY ordinal_position
                        """)
                .query(String.class)
                .list();

        assertThat(columns).containsExactly(
                "id:uuid::NO::NEVER",
                "business_id:uuid::NO::NEVER",
                "display_name:character varying:200:NO::NEVER",
                "normalized_display_name:text::YES::ALWAYS",
                "phone:character varying:16:YES::NEVER",
                "email:character varying:320:YES::NEVER",
                "version:bigint::NO:0:NEVER",
                "created_at:timestamp with time zone::NO::NEVER",
                "updated_at:timestamp with time zone::NO::NEVER");
    }

    @Test
    void customerHasNoLifecycleNoteAccountMembershipAppointmentOrRawContactColumn() {
        List<String> columns = jdbc.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'customer'
                        """)
                .query(String.class)
                .list();

        assertThat(columns).noneMatch(column -> column.matches(
                ".*(status|active|blocked|archiv|deleted|note|membership|user|account|"
                        + "appointment|original|raw|anonymi).*"));
    }

    @Test
    void customerReferencesOnlyItsBusinessAndIsReferencedOnlyByTheAppointmentTable() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                        """)
                .query(String.class)
                .list();
        List<String> foreignKeys = jdbc.sql("""
                        SELECT conname || '->' || confrelid::regclass::text
                        FROM pg_catalog.pg_constraint
                        WHERE contype = 'f'
                          AND (conrelid = 'public.customer'::regclass
                               OR confrelid = 'public.customer'::regclass)
                        """)
                .query(String.class)
                .list();

        assertThat(tables).contains("customer", "appointment");
        assertThat(foreignKeys).containsExactlyInAnyOrder(
                "customer_business_fk->business", "appointment_customer_fk->customer");
    }

    @Test
    void generatedNormalizedNameUsesTheApprovedExpressionAndCollation() {
        var metadata = jdbc.sql("""
                        SELECT a.attgenerated,
                               c.collname,
                               pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS expression
                        FROM pg_catalog.pg_attribute a
                        JOIN pg_catalog.pg_class t ON t.oid = a.attrelid
                        JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace
                        JOIN pg_catalog.pg_collation c ON c.oid = a.attcollation
                        JOIN pg_catalog.pg_attrdef d
                          ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                        WHERE n.nspname = 'public'
                          AND t.relname = 'customer'
                          AND a.attname = 'normalized_display_name'
                        """)
                .query((resultSet, rowNumber) -> new String[] {
                    resultSet.getString("attgenerated"),
                    resultSet.getString("collname"),
                    resultSet.getString("expression")})
                .single();
        String staffExpression = jdbc.sql("""
                        SELECT pg_catalog.pg_get_expr(d.adbin, d.adrelid)
                        FROM pg_catalog.pg_attribute a
                        JOIN pg_catalog.pg_attrdef d
                          ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                        WHERE a.attrelid = 'public.staff_member'::regclass
                          AND a.attname = 'normalized_display_name'
                        """)
                .query(String.class)
                .single();

        assertThat(metadata[0]).isEqualTo("s");
        assertThat(metadata[1]).isEqualTo("pg_unicode_fast");
        assertThat(metadata[2])
                .contains("\"normalize\"(")
                .contains("casefold(")
                .contains("COLLATE pg_unicode_fast")
                .contains("regexp_replace(")
                .contains("NFKC")
                .contains("display_name")
                // The exact expression of the accepted StaffMember schema, with the same column.
                .isEqualTo(staffExpression);
    }

    @Test
    void generatedNormalizedNameMatchesTheJavaCanonicalizationAndCasefolds() {
        UUID businessId = createBusiness();
        record Sample(String name, String normalized) {
        }
        List<Sample> samples = List.of(
                new Sample("Straße", "strasse"),
                new Sample("STRASSE", "strasse"),
                new Sample("Анна Иванова", "анна иванова"),
                new Sample("ΣΊΣΥΦΟΣ", "σίσυφοσ"),
                new Sample("Ana İvanova", "ana i̇vanova"),
                new Sample("ﬃ é", "ffi é"));
        for (Sample sample : samples) {
            String canonical = bg.spotyourslot.shared.contact.ContactTextCanonicalizer
                    .canonicalDisplayName(sample.name());
            UUID id = insertCustomer(businessId, canonical, null, uniqueEmail());

            assertThat(jdbc.sql("SELECT normalized_display_name FROM customer WHERE id = :id")
                            .param("id", id)
                            .query(String.class)
                            .single())
                    .as(sample.name())
                    .isEqualTo(sample.normalized());
        }
    }

    @Test
    void constraintNamesAndDefinitionsAreExactlyTheApprovedSet() {
        List<String> constraints = jdbc.sql("""
                        SELECT conname || ' ' || contype::text
                        FROM pg_catalog.pg_constraint
                        WHERE conrelid = 'public.customer'::regclass
                          AND contype <> 'n'
                        ORDER BY conname
                        """)
                .query(String.class)
                .list();

        // NOT NULL constraints are catalogued separately (contype 'n') from PostgreSQL 18.
        assertThat(constraints).containsExactly(
                "customer_business_email_unique u",
                "customer_business_fk f",
                "customer_business_id_id_unique u",
                "customer_business_phone_unique u",
                "customer_contact_present c",
                "customer_display_name_canonical c",
                "customer_display_name_not_blank c",
                "customer_email_canonical c",
                "customer_phone_canonical c",
                "customer_pkey p",
                "customer_timestamps_finite_ordered c",
                "customer_version_nonnegative c");
        assertThat(definition("customer_business_phone_unique"))
                .isEqualTo("UNIQUE (business_id, phone)");
        assertThat(definition("customer_business_email_unique"))
                .isEqualTo("UNIQUE (business_id, email)");
        assertThat(definition("customer_business_id_id_unique"))
                .isEqualTo("UNIQUE (business_id, id)");
        assertThat(definition("customer_business_fk"))
                .isEqualTo("FOREIGN KEY (business_id) REFERENCES business(id) ON DELETE RESTRICT");
        assertThat(definition("customer_contact_present"))
                .isEqualTo("CHECK (((phone IS NOT NULL) OR (email IS NOT NULL)))");
        assertThat(definition("customer_version_nonnegative"))
                .isEqualTo("CHECK ((version >= 0))");
        assertThat(definition("customer_phone_canonical"))
                .contains("^\\+[1-9][0-9]{7,14}$");
        assertThat(definition("customer_timestamps_finite_ordered"))
                .contains("isfinite(created_at)")
                .contains("isfinite(updated_at)")
                .contains("updated_at >= created_at");
    }

    @Test
    void indexesAreExactlyTheConstraintIndexesPlusTheDefaultListIndex() {
        List<String> indexes = jdbc.sql("""
                        SELECT indexname || ' ' || indexdef
                        FROM pg_catalog.pg_indexes
                        WHERE schemaname = 'public' AND tablename = 'customer'
                        ORDER BY indexname
                        """)
                .query(String.class)
                .list();

        assertThat(indexes).hasSize(5);
        assertThat(indexes).anyMatch(index -> index.startsWith("customer_pkey ")
                && index.contains("(id)"));
        assertThat(indexes).anyMatch(index -> index.startsWith("customer_business_phone_unique ")
                && index.contains("UNIQUE") && index.contains("(business_id, phone)"));
        assertThat(indexes).anyMatch(index -> index.startsWith("customer_business_email_unique ")
                && index.contains("UNIQUE") && index.contains("(business_id, email)"));
        assertThat(indexes).anyMatch(index -> index.startsWith("customer_business_id_id_unique ")
                && index.contains("UNIQUE") && index.contains("(business_id, id)"));
        assertThat(indexes).anyMatch(
                index -> index.startsWith("customer_business_normalized_display_name_id_idx ")
                        && !index.contains("UNIQUE")
                        && index.contains("(business_id, normalized_display_name, id)"));
    }

    // ---- data rules ----------------------------------------------------------

    @Test
    void aCustomerMayHoldOnePhoneOnlyOneEmailOnlyOrBothButNeverNeither() {
        UUID businessId = createBusiness();

        insertCustomer(businessId, "Само телефон", PHONE, null);
        insertCustomer(businessId, "Само имейл", null, EMAIL);
        insertCustomer(businessId, "И двете", OTHER_PHONE, OTHER_EMAIL);

        assertConstraint(
                () -> insertCustomer(businessId, "Без контакт", null, null),
                CHECK_VIOLATION,
                "customer_contact_present");
    }

    @Test
    void aPhoneOrEmailIsUniqueWithinOneBusinessButReusableAtAnother() {
        UUID first = createBusiness();
        UUID second = createBusiness();
        insertCustomer(first, "Първи", PHONE, EMAIL);

        assertConstraint(
                () -> insertCustomer(first, "Дубликат на телефон", PHONE, null),
                UNIQUE_VIOLATION,
                "customer_business_phone_unique");
        assertConstraint(
                () -> insertCustomer(first, "Дубликат на имейл", null, EMAIL),
                UNIQUE_VIOLATION,
                "customer_business_email_unique");
        insertCustomer(second, "Същият човек другаде", PHONE, EMAIL);

        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE phone = :phone")
                        .param("phone", PHONE)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    void nullContactValuesAreDistinctSoManyCustomersMayLackEitherIdentifier() {
        UUID businessId = createBusiness();

        insertCustomer(businessId, "Едно", PHONE, null);
        insertCustomer(businessId, "Две", OTHER_PHONE, null);
        insertCustomer(businessId, "Три", null, EMAIL);
        insertCustomer(businessId, "Четири", null, OTHER_EMAIL);

        assertThat(jdbc.sql("SELECT count(*) FROM customer WHERE business_id = :id")
                        .param("id", businessId)
                        .query(Long.class)
                        .single())
                .isEqualTo(4L);
    }

    @Test
    void namesAreNotUniqueAndNeverIdentifiers() {
        UUID businessId = createBusiness();

        insertCustomer(businessId, "Анна Иванова", PHONE, null);
        insertCustomer(businessId, "Анна Иванова", OTHER_PHONE, null);
        insertCustomer(businessId, "АННА ИВАНОВА", null, EMAIL);

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM customer
                        WHERE business_id = :id AND normalized_display_name = 'анна иванова'
                        """)
                        .param("id", businessId)
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
    }

    @Test
    void displayNameAcceptsTwoHundredCharactersAndRejectsTwoHundredOne() {
        UUID businessId = createBusiness();

        insertCustomer(businessId, "я".repeat(200), PHONE, null);
        insertCustomer(businessId, "😀".repeat(200), OTHER_PHONE, null);
        assertRejectedWith(
                () -> insertCustomer(businessId, "я".repeat(201), "+359885555777", null),
                VALUE_TOO_LONG);
    }

    @Test
    void displayNameMustBeCanonicalAndNonblank() {
        UUID businessId = createBusiness();

        assertConstraint(() -> insertCustomer(businessId, "", PHONE, null),
                CHECK_VIOLATION, "customer_display_name_not_blank");
        assertConstraint(() -> insertCustomer(businessId, " Име", PHONE, null),
                CHECK_VIOLATION, "customer_display_name_canonical");
        assertConstraint(() -> insertCustomer(businessId, "Име ", PHONE, null),
                CHECK_VIOLATION, "customer_display_name_canonical");
        assertConstraint(() -> insertCustomer(businessId, "Две   имена", PHONE, null),
                CHECK_VIOLATION, "customer_display_name_canonical");
        assertConstraint(() -> insertCustomer(businessId, "ＡＢＣ", PHONE, null),
                CHECK_VIOLATION, "customer_display_name_canonical");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0895555777", "+359 895 555 777", "+0895555777", "+35921",
        "359895555777", "+359895555777x5", "abc", "+359895555777 "})
    void phoneMustBeCompactCanonicalE164(String phone) {
        UUID businessId = createBusiness();

        assertConstraint(
                () -> insertCustomer(businessId, "Анна", phone, null),
                CHECK_VIOLATION,
                "customer_phone_canonical");
    }

    @Test
    void phoneLongerThanSixteenCharactersIsRejectedAsTooLong() {
        UUID businessId = createBusiness();

        assertRejectedWith(
                () -> insertCustomer(businessId, "Анна", "+3598951234567890", null), VALUE_TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ime@primer.bg", " ime@primer.bg", "ime@primer.bg ", "", "ｉｍｅ@primer.bg"})
    void emailMustBeTheLowercaseCanonicalValue(String email) {
        UUID businessId = createBusiness();

        assertConstraint(
                () -> insertCustomer(businessId, "Анна", null, email),
                CHECK_VIOLATION,
                "customer_email_canonical");
    }

    @Test
    void emailAcceptsThreeHundredTwentyCharactersAndRejectsMore() {
        UUID businessId = createBusiness();
        String label = "b".repeat(63);
        String atLimit = "a".repeat(64) + "@" + String.join(".", label, label, label, label);

        assertThat(atLimit).hasSize(320);
        insertCustomer(businessId, "Анна", null, atLimit);
        assertRejectedWith(
                () -> insertCustomer(businessId, "Борис", null, "c" + atLimit), VALUE_TOO_LONG);
    }

    @Test
    void versionAndTimestampsMustBeNonnegativeFiniteAndOrdered() {
        UUID businessId = createBusiness();
        OffsetDateTime created = OffsetDateTime.parse("2026-10-01T08:00:00Z");

        assertConstraint(
                () -> insertCustomerRow(businessId, "Анна", PHONE, null, -1, created, created),
                CHECK_VIOLATION,
                "customer_version_nonnegative");
        assertConstraint(
                () -> insertCustomerRow(businessId, "Анна", PHONE, null, 0, created, created.minusSeconds(1)),
                CHECK_VIOLATION,
                "customer_timestamps_finite_ordered");
        assertConstraint(
                () -> jdbc.sql("""
                                INSERT INTO customer(
                                    id, business_id, display_name, phone, version, created_at, updated_at)
                                VALUES (:id, :business, 'Анна', :phone, 0, 'infinity', 'infinity')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("business", businessId)
                        .param("phone", PHONE)
                        .update(),
                CHECK_VIOLATION,
                "customer_timestamps_finite_ordered");
        insertCustomerRow(businessId, "Анна", PHONE, null, 7, created, created.plusSeconds(1));
    }

    @Test
    void businessOwnershipIsRestrictiveAndReferencesMustExist() {
        UUID businessId = createBusiness();
        insertCustomer(businessId, "Анна", PHONE, null);

        assertRejectedWith(
                () -> jdbc.sql("DELETE FROM business WHERE id = :id").param("id", businessId).update(),
                RESTRICT_VIOLATION);
        assertConstraint(
                () -> insertCustomer(UUID.randomUUID(), "Сирак", OTHER_PHONE, null),
                FOREIGN_KEY_VIOLATION,
                "customer_business_fk");
    }

    @Test
    void theCustomerIdIsUniqueWithItsBusinessForFutureSameBusinessReferences() {
        UUID first = createBusiness();
        UUID second = createBusiness();
        UUID customerId = insertCustomer(first, "Анна", PHONE, null);

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM customer WHERE business_id = :business AND id = :id
                        """)
                        .param("business", first)
                        .param("id", customerId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM customer WHERE business_id = :business AND id = :id
                        """)
                        .param("business", second)
                        .param("id", customerId)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ---- helpers -------------------------------------------------------------

    private String definition(String constraint) {
        return jdbc.sql("""
                        SELECT pg_catalog.pg_get_constraintdef(oid)
                        FROM pg_catalog.pg_constraint
                        WHERE conrelid = 'public.customer'::regclass AND conname = :name
                        """)
                .param("name", constraint)
                .query(String.class)
                .single();
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Customer Schema Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "customer-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@primer.bg";
    }

    private UUID insertCustomer(UUID businessId, String name, String phone, String email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return insertCustomerRow(businessId, name, phone, email, 0, now, now);
    }

    private UUID insertCustomerRow(
            UUID businessId,
            String name,
            String phone,
            String email,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer(
                            id, business_id, display_name, phone, email,
                            version, created_at, updated_at)
                        VALUES (:id, :business, :name, :phone, :email,
                                :version, :createdAt, :updatedAt)
                        """)
                .param("id", id)
                .param("business", businessId)
                .param("name", name)
                .param("phone", phone)
                .param("email", email)
                .param("version", version)
                .param("createdAt", createdAt)
                .param("updatedAt", updatedAt)
                .update();
        return id;
    }

    private void assertConstraint(Runnable operation, String sqlState, String constraint) {
        PSQLException failure = failureOf(operation);
        assertThat(failure.getSQLState()).isEqualTo(sqlState);
        assertThat(failure.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    }

    private void assertRejectedWith(Runnable operation, String sqlState) {
        assertThat(failureOf(operation).getSQLState()).isEqualTo(sqlState);
    }

    private PSQLException failureOf(Runnable operation) {
        Throwable thrown = catchThrowable(operation);
        assertThat(thrown).isInstanceOf(DataAccessException.class);
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof PSQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isNotNull();
        return (PSQLException) cause;
    }

    private static Throwable catchThrowable(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    private String sha256(String migrationFile) {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("db/migration/" + migrationFile)) {
            if (input == null) {
                throw new IllegalStateException("Migration resource is missing: " + migrationFile);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Migration resource could not be verified", exception);
        }
    }
}
