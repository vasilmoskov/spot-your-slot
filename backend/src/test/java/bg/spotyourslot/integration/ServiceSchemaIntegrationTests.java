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
class ServiceSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final int[] APPROVED_WHITESPACE_CODE_POINTS = {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0,
        0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
        0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
        0x3000
    };

    @Autowired
    JdbcClient jdbc;

    @Test
    void generatedColumnUsesTheApprovedExpressionAndCollation() {
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
                          AND t.relname = 'service'
                          AND a.attname = 'normalized_name'
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
                .contains("btrim(")
                .contains("regexp_replace(")
                .contains("NFKC");
    }

    @Test
    void generatedExpressionFunctionsAreImmutableInPostgres18Catalog() {
        var functions = jdbc.sql("""
                        SELECT candidate.signature, function.provolatile
                        FROM (VALUES
                            ('normalize(text,text)'),
                            ('casefold(text)'),
                            ('btrim(text)'),
                            ('regexp_replace(text,text,text,text)')
                        ) AS candidate(signature)
                        JOIN pg_catalog.pg_proc function
                          ON function.oid = pg_catalog.to_regprocedure(
                              'pg_catalog.' || candidate.signature)
                        ORDER BY candidate.signature
                        """)
                .query((resultSet, rowNumber) -> new FunctionVolatility(
                        resultSet.getString("signature"),
                        resultSet.getString("provolatile")))
                .list();

        assertThat(functions)
                .containsExactly(
                        new FunctionVolatility("btrim(text)", "i"),
                        new FunctionVolatility("casefold(text)", "i"),
                        new FunctionVolatility("normalize(text,text)", "i"),
                        new FunctionVolatility("regexp_replace(text,text,text,text)", "i"));
    }

    @Test
    void pgUnicodeFastIsAvailableAndDeterministic() {
        var collation = jdbc.sql("""
                        SELECT collprovider, collisdeterministic
                        FROM pg_catalog.pg_collation
                        WHERE collname = 'pg_unicode_fast'
                          AND collnamespace = 'pg_catalog'::regnamespace
                        """)
                .query((resultSet, rowNumber) -> new CollationMetadata(
                        resultSet.getString("collprovider"),
                        resultSet.getBoolean("collisdeterministic")))
                .single();

        assertThat(collation.provider()).isEqualTo("b");
        assertThat(collation.deterministic()).isTrue();
    }

    @Test
    void normalizedNameCannotBeSuppliedDirectly() {
        UUID businessId = createBusiness();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO service(
                            id, business_id, name, normalized_name, description,
                            duration_minutes, price, created_at, updated_at)
                        VALUES (
                            :id, :businessId, 'Услуга', 'supplied', NULL,
                            30, 20.00, :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void nfkcEquivalentNamesConflictWithinOneBusiness() {
        UUID businessId = createBusiness();
        String firstCanonicalName = canonicalDisplayName("ＡＢＣ");
        String secondCanonicalName = canonicalDisplayName("ABC");

        insertService(businessId, firstCanonicalName, true);

        assertThat(firstCanonicalName).isEqualTo("ABC");
        assertThat(secondCanonicalName).isEqualTo("ABC");
        assertDuplicateNameRejected(businessId, secondCanonicalName, true);
    }

    @Test
    void cyrillicCaseVariantsConflictWithinOneBusiness() {
        UUID businessId = createBusiness();
        insertService(businessId, "Подстригване", true);

        assertDuplicateNameRejected(businessId, "ПОДСТРИГВАНЕ", true);
    }

    @Test
    void unicodeCaseFoldingMakesSharpSConflictWithSs() {
        UUID businessId = createBusiness();
        insertService(businessId, "Straße", true);

        assertDuplicateNameRejected(businessId, "STRASSE", true);
    }

    @Test
    void everyApprovedBoundaryWhitespaceCodePointIsRemoved() {
        for (int codePoint : APPROVED_WHITESPACE_CODE_POINTS) {
            String whitespace = new String(Character.toChars(codePoint));

            assertThat(normalizedName(whitespace + "Име" + whitespace))
                    .as("U+%04X", codePoint)
                    .isEqualTo("име");
        }
    }

    @Test
    void everyApprovedInternalWhitespaceCodePointCollapsesToAsciiSpace() {
        for (int codePoint : APPROVED_WHITESPACE_CODE_POINTS) {
            String whitespace = new String(Character.toChars(codePoint));

            assertThat(normalizedName("Мъжко" + whitespace.repeat(3) + "подстригване"))
                    .as("U+%04X", codePoint)
                    .isEqualTo("мъжко подстригване");
        }
    }

    @Test
    void sameNormalizedNameIsAllowedInSeparateBusinesses() {
        UUID firstBusinessId = createBusiness();
        UUID secondBusinessId = createBusiness();

        insertService(firstBusinessId, "Подстригване", true);
        insertService(secondBusinessId, "ПОДСТРИГВАНЕ", true);

        assertThat(serviceCount()).isEqualTo(2);
    }

    @Test
    void inactiveServiceContinuesToReserveItsName() {
        UUID businessId = createBusiness();
        insertService(businessId, "Подстригване", false);

        assertDuplicateNameRejected(businessId, "ПОДСТРИГВАНЕ", true);
    }

    @Test
    void directNoncanonicalStoredNamesAreRejected() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertService(businessId, " Подстригване", true));
        assertRejected(() -> insertService(businessId, "Мъжко   подстригване", true));
        assertRejected(() -> insertService(businessId, "ＡＢＣ", true));
    }

    @Test
    void blankCanonicalNamesAreRejected() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertService(businessId, "", true));
        assertRejected(() -> insertService(businessId, " ", true));
    }

    @Test
    void nullAndCanonicalDescriptionsAreAcceptedAndPreserved() {
        UUID businessId = createBusiness();
        UUID nullDescriptionServiceId = insertService(businessId, "Без описание", true);
        String canonicalDescription = "Първи ред\nВтори  ред\tс отстъп";
        UUID describedServiceId = insertServiceWithDescription(
                businessId, "С описание", canonicalDescription);

        assertThat(descriptionOf(nullDescriptionServiceId)).isNull();
        assertThat(descriptionOf(describedServiceId)).isEqualTo(canonicalDescription);
    }

    @Test
    void emptyAndApprovedWhitespaceOnlyDescriptionsAreRejected() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertServiceWithDescription(businessId, "Празно", ""));
        for (int codePoint : APPROVED_WHITESPACE_CODE_POINTS) {
            String whitespace = new String(Character.toChars(codePoint));

            assertThatThrownBy(() -> insertServiceWithDescription(
                            businessId, "Само интервал", whitespace.repeat(3)))
                    .as("U+%04X", codePoint)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void everyApprovedBoundaryWhitespaceCodePointIsRejectedForDescriptions() {
        UUID businessId = createBusiness();
        for (int codePoint : APPROVED_WHITESPACE_CODE_POINTS) {
            String whitespace = new String(Character.toChars(codePoint));

            assertThatThrownBy(() -> insertServiceWithDescription(
                            businessId, "Водещ интервал", whitespace + "Описание"))
                    .as("leading U+%04X", codePoint)
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertServiceWithDescription(
                            businessId, "Завършващ интервал", "Описание" + whitespace))
                    .as("trailing U+%04X", codePoint)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void nonNfkcDescriptionIsRejected() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertServiceWithDescription(
                businessId, "Неканонично описание", "Пълна ширина Ａ"));
    }

    @Test
    void nameLengthUsesPostgresCharactersAndEnforcesTwoHundredCharacterLimit() {
        UUID businessId = createBusiness();
        String twoHundredCyrillicCharacters = "я".repeat(200);
        UUID serviceId = insertService(businessId, twoHundredCyrillicCharacters, true);

        var lengths = jdbc.sql("""
                        SELECT pg_catalog.char_length(name), pg_catalog.octet_length(name)
                        FROM service
                        WHERE id = :id
                        """)
                .param("id", serviceId)
                .query((resultSet, rowNumber) -> new TextLengths(
                        resultSet.getInt(1), resultSet.getInt(2)))
                .single();

        assertThat(lengths).isEqualTo(new TextLengths(200, 400));
        assertRejected(() -> insertService(
                businessId, "я".repeat(201), true));
    }

    @Test
    void descriptionLengthUsesPostgresCharactersAndEnforcesTwoThousandCharacterLimit() {
        UUID businessId = createBusiness();
        String twoThousandCyrillicCharacters = "я".repeat(2000);
        UUID serviceId = insertServiceWithDescription(
                businessId, "Дълго описание", twoThousandCyrillicCharacters);

        var lengths = jdbc.sql("""
                        SELECT pg_catalog.char_length(description),
                               pg_catalog.octet_length(description)
                        FROM service
                        WHERE id = :id
                        """)
                .param("id", serviceId)
                .query((resultSet, rowNumber) -> new TextLengths(
                        resultSet.getInt(1), resultSet.getInt(2)))
                .single();

        assertThat(lengths).isEqualTo(new TextLengths(2000, 4000));
        assertRejected(() -> insertServiceWithDescription(
                businessId, "Прекалено дълго описание", "я".repeat(2001)));
    }

    @Test
    void durationOutsideApprovedRangeIsRejected() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertService(businessId, "Нулева", 0, BigDecimal.ZERO, 0));
        assertRejected(() -> insertService(businessId, "Твърде дълга", 481, BigDecimal.ZERO, 0));

        insertService(businessId, "Една минута", 1, BigDecimal.ZERO, 0);
        insertService(businessId, "Осем часа", 480, BigDecimal.ZERO, 0);
    }

    @Test
    void negativePriceAndNumericOverflowAreRejected() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertService(
                businessId, "Отрицателна цена", 30, new BigDecimal("-0.01"), 0));
        assertRejected(() -> insertService(
                businessId, "Препълване", 30, new BigDecimal("10000000000.00"), 0));
    }

    @Test
    void postgresRoundsPricesWithMoreThanTwoFractionalDigits() {
        UUID businessId = createBusiness();
        UUID serviceId = insertService(
                businessId, "Закръгляне", 30, new BigDecimal("12.345"), 0);

        BigDecimal storedPrice = jdbc.sql("SELECT price FROM service WHERE id = :id")
                .param("id", serviceId)
                .query(BigDecimal.class)
                .single();

        assertThat(storedPrice).isEqualByComparingTo("12.35");
    }

    @Test
    void negativeVersionIsRejected() {
        UUID businessId = createBusiness();

        assertRejected(() -> insertService(
                businessId, "Невалидна версия", 30, BigDecimal.ZERO, -1));
    }

    @Test
    void missingBusinessIsRejectedByForeignKey() {
        assertRejected(() -> insertService(
                UUID.randomUUID(), "Липсващ бизнес", 30, BigDecimal.ZERO, 0));
    }

    @Test
    void businessDeletionIsRestrictedWhileServicesExist() {
        UUID businessId = createBusiness();
        insertService(businessId, "Услуга", true);

        assertRejected(() -> jdbc.sql("DELETE FROM business WHERE id = :id")
                .param("id", businessId)
                .update());
    }

    @Test
    void defaultsAndRequiredTimestampsAreEnforced() {
        UUID businessId = createBusiness();
        UUID serviceId = insertServiceUsingDefaults(businessId, "Услуга");

        var defaults = jdbc.sql("""
                        SELECT active, version
                        FROM service
                        WHERE id = :id
                        """)
                .param("id", serviceId)
                .query((resultSet, rowNumber) -> new ServiceDefaults(
                        resultSet.getBoolean("active"),
                        resultSet.getLong("version")))
                .single();

        assertThat(defaults).isEqualTo(new ServiceDefaults(true, 0));
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO service(
                            id, business_id, name, duration_minutes, price,
                            created_at, updated_at)
                        VALUES (:id, :businessId, 'Без време', 30, 0, NULL, NULL)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", businessId)
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void columnTypesBoundsDefaultsAndNullabilityMatchApprovedSchema() {
        var columns = jdbc.sql("""
                        SELECT column_name, data_type, udt_name,
                               character_maximum_length, numeric_precision, numeric_scale,
                               is_nullable, column_default, is_generated, collation_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'service'
                        ORDER BY ordinal_position
                        """)
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

        assertThat(columns).containsExactly(
                column("id", "uuid", "uuid", null, null, null, "NO", null, "NEVER", null),
                column("business_id", "uuid", "uuid", null, null, null, "NO", null, "NEVER", null),
                column("name", "character varying", "varchar", 200, null, null, "NO", null, "NEVER", null),
                column("normalized_name", "text", "text", null, null, null, "YES", null, "ALWAYS", "pg_unicode_fast"),
                column("description", "character varying", "varchar", 2000, null, null, "YES", null, "NEVER", null),
                column("duration_minutes", "integer", "int4", null, 32, 0, "NO", null, "NEVER", null),
                column("price", "numeric", "numeric", null, 12, 2, "NO", null, "NEVER", null),
                column("active", "boolean", "bool", null, null, null, "NO", "true", "NEVER", null),
                column("version", "bigint", "int8", null, 64, 0, "NO", "0", "NEVER", null),
                column("created_at", "timestamp with time zone", "timestamptz", null, null, null, "NO", null, "NEVER", null),
                column("updated_at", "timestamp with time zone", "timestamptz", null, null, null, "NO", null, "NEVER", null));
    }

    @Test
    void constraintNamesAndDefinitionsMatchApprovedSchema() {
        var constraints = jdbc.sql("""
                        SELECT service_constraint.conname
                        FROM pg_catalog.pg_constraint service_constraint
                        JOIN pg_catalog.pg_class table_definition
                          ON table_definition.oid = service_constraint.conrelid
                        JOIN pg_catalog.pg_namespace namespace
                          ON namespace.oid = table_definition.relnamespace
                        WHERE namespace.nspname = 'public'
                          AND table_definition.relname = 'service'
                          AND service_constraint.contype <> 'n'
                        ORDER BY service_constraint.conname
                        """)
                .query(String.class)
                .list();

        assertThat(constraints).containsExactly(
                "service_business_fk",
                "service_business_id_id_unique",
                "service_business_normalized_name_unique",
                "service_description_canonical",
                "service_duration_minutes_range",
                "service_name_canonical",
                "service_name_not_blank",
                "service_pkey",
                "service_price_nonnegative",
                "service_version_nonnegative");
    }

    @Test
    void onlyApprovedPrimaryAndUniqueConstraintIndexesExist() {
        var indexes = jdbc.sql("""
                        SELECT indexname, indexdef
                        FROM pg_catalog.pg_indexes
                        WHERE schemaname = 'public' AND tablename = 'service'
                        ORDER BY indexname
                        """)
                .query((resultSet, rowNumber) -> new IndexMetadata(
                        resultSet.getString("indexname"),
                        resultSet.getString("indexdef")))
                .list();

        assertThat(indexes).containsExactly(
                new IndexMetadata(
                        "service_business_id_id_unique",
                        "CREATE UNIQUE INDEX service_business_id_id_unique "
                                + "ON public.service USING btree (business_id, id)"),
                new IndexMetadata(
                        "service_business_normalized_name_unique",
                        "CREATE UNIQUE INDEX service_business_normalized_name_unique "
                                + "ON public.service USING btree (business_id, normalized_name)"),
                new IndexMetadata(
                        "service_pkey",
                        "CREATE UNIQUE INDEX service_pkey "
                                + "ON public.service USING btree (id)"));
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Service Schema Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "service-schema-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertService(UUID businessId, String name, boolean active) {
        UUID serviceId = insertService(businessId, name, 30, new BigDecimal("20.00"), 0);
        if (!active) {
            jdbc.sql("UPDATE service SET active = false WHERE id = :id")
                    .param("id", serviceId)
                    .update();
        }
        return serviceId;
    }

    private UUID insertService(
            UUID businessId,
            String name,
            int durationMinutes,
            BigDecimal price,
            long version) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO service(
                            id, business_id, name, description, duration_minutes,
                            price, active, version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :name, NULL, :durationMinutes,
                            :price, true, :version, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", name)
                .param("durationMinutes", durationMinutes)
                .param("price", price)
                .param("version", version)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertServiceUsingDefaults(UUID businessId, String name) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO service(
                            id, business_id, name, duration_minutes, price,
                            created_at, updated_at)
                        VALUES (:id, :businessId, :name, 30, 20.00, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", name)
                .param("now", now)
                .update();
        return id;
    }

    private UUID insertServiceWithDescription(
            UUID businessId,
            String name,
            String description) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO service(
                            id, business_id, name, description, duration_minutes,
                            price, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :name, :description, 30,
                            20.00, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", name)
                .param("description", description)
                .param("now", now)
                .update();
        return id;
    }

    private String descriptionOf(UUID serviceId) {
        return jdbc.sql("SELECT description FROM service WHERE id = :id")
                .param("id", serviceId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String canonicalDisplayName(String value) {
        return jdbc.sql("""
                        SELECT pg_catalog.btrim(
                            pg_catalog.regexp_replace(
                                pg_catalog.normalize(:value, 'NFKC'),
                                U&'[\\0009-\\000D\\0020\\0085\\00A0\\1680\\2000-\\200A\\2028\\2029\\202F\\205F\\3000]+',
                                ' ',
                                'g'
                            )
                        )
                        """)
                .param("value", value)
                .query(String.class)
                .single();
    }

    private String normalizedName(String value) {
        return jdbc.sql("""
                        SELECT pg_catalog.normalize(
                            pg_catalog.casefold(
                                pg_catalog.btrim(
                                    pg_catalog.regexp_replace(
                                        pg_catalog.normalize(:value, 'NFKC'),
                                        U&'[\\0009-\\000D\\0020\\0085\\00A0\\1680\\2000-\\200A\\2028\\2029\\202F\\205F\\3000]+',
                                        ' ',
                                        'g'
                                    )
                                ) COLLATE pg_catalog.pg_unicode_fast
                            ),
                            'NFKC'
                        )
                        """)
                .param("value", value)
                .query(String.class)
                .single();
    }

    private void assertDuplicateNameRejected(UUID businessId, String name, boolean active) {
        assertRejected(() -> insertService(businessId, name, active));
    }

    private void assertRejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(DataAccessException.class);
    }

    private long serviceCount() {
        return jdbc.sql("SELECT count(*) FROM service")
                .query(Long.class)
                .single();
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

    private record FunctionVolatility(String signature, String volatility) {
    }

    private record CollationMetadata(String provider, boolean deterministic) {
    }

    private record ServiceDefaults(boolean active, long version) {
    }

    private record IndexMetadata(String name, String definition) {
    }

    private record TextLengths(int characters, int bytes) {
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
