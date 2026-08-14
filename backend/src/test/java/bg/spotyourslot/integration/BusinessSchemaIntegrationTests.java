package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;

@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BusinessSchemaIntegrationTests extends PostgresIntegrationTest {
    private static final String V1_SHA_256 =
            "68cb25d6ccfd4e5aca12ec0b0199f13f3d0dd35418e45e1d7d2ef74a6832dc49";
    private static final String V2_SHA_256 =
            "c1b62d1fed08138a937f281d4e3952942cc0a5d5cbaf7ed4803dc030a60527f8";

    @Autowired JdbcClient jdbc;

    @Test
    void flywayAppliesAllThreeMigrationsFromAnEmptyDatabase() {
        var versions = jdbc.sql("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE type = 'SQL' AND success = true
                        ORDER BY installed_rank
                        """)
                .query(String.class)
                .list();

        assertThat(versions).containsExactly("1", "2", "3");
    }

    @Test
    void profileColumnsHaveApprovedBoundsAndRemainNullable() {
        var columns = jdbc.sql("""
                        SELECT column_name, character_maximum_length, is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'business'
                          AND column_name IN ('description', 'address', 'phone', 'contact_email')
                        ORDER BY column_name
                        """)
                .query((resultSet, rowNumber) -> new ColumnMetadata(
                        resultSet.getString("column_name"),
                        resultSet.getInt("character_maximum_length"),
                        resultSet.getString("is_nullable")))
                .list();

        assertThat(columns)
                .containsExactly(
                        new ColumnMetadata("address", 500, "YES"),
                        new ColumnMetadata("contact_email", 320, "YES"),
                        new ColumnMetadata("description", 2000, "YES"),
                        new ColumnMetadata("phone", 50, "YES"));
    }

    @Test
    void existingBusinessShapeRemainsValidWithNullProfileFields() {
        UUID businessId = createBusiness();

        var profile = jdbc.sql("""
                        SELECT description, address, phone, contact_email
                        FROM business
                        WHERE id = :id
                        """)
                .param("id", businessId)
                .query((resultSet, rowNumber) -> new BusinessProfile(
                        resultSet.getString("description"),
                        resultSet.getString("address"),
                        resultSet.getString("phone"),
                        resultSet.getString("contact_email")))
                .single();

        assertThat(profile)
                .isEqualTo(new BusinessProfile(null, null, null, null));
    }

    @Test
    void validProfileValuesPersist() {
        UUID businessId = createBusiness();

        jdbc.sql("""
                        UPDATE business
                        SET description = :description,
                            address = :address,
                            phone = :phone,
                            contact_email = :contactEmail
                        WHERE id = :id
                        """)
                .param("description", "Малък бизнес за услуги с предварително записване.")
                .param("address", "ул. Примерна 1, София")
                .param("phone", "+359 2 000 0000")
                .param("contactEmail", "contact@example.invalid")
                .param("id", businessId)
                .update();

        var profile = jdbc.sql("""
                        SELECT description, address, phone, contact_email
                        FROM business
                        WHERE id = :id
                        """)
                .param("id", businessId)
                .query((resultSet, rowNumber) -> new BusinessProfile(
                        resultSet.getString("description"),
                        resultSet.getString("address"),
                        resultSet.getString("phone"),
                        resultSet.getString("contact_email")))
                .single();

        assertThat(profile)
                .isEqualTo(new BusinessProfile(
                        "Малък бизнес за услуги с предварително записване.",
                        "ул. Примерна 1, София",
                        "+359 2 000 0000",
                        "contact@example.invalid"));
    }

    @ParameterizedTest
    @EnumSource(ProfileColumn.class)
    void rejectsOversizedProfileValues(ProfileColumn column) {
        UUID businessId = createBusiness();
        String oversized = "a".repeat(column.maximumLength() + 1);

        assertThatThrownBy(() -> updateColumn(businessId, column, oversized))
                .isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @MethodSource("whitespaceProfileValues")
    void rejectsBlankNonNullProfileValues(ProfileColumn column, String whitespace) {
        UUID businessId = createBusiness();

        assertThatThrownBy(() -> updateColumn(businessId, column, whitespace))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsUppercaseContactEmail() {
        UUID businessId = createBusiness();

        assertThatThrownBy(() -> updateColumn(
                        businessId, ProfileColumn.CONTACT_EMAIL, "Contact@example.invalid"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void acceptsLowercaseContactEmail() {
        UUID businessId = createBusiness();

        updateColumn(businessId, ProfileColumn.CONTACT_EMAIL, "contact@example.invalid");

        assertThat(jdbc.sql("SELECT contact_email FROM business WHERE id = :id")
                        .param("id", businessId)
                        .query(String.class)
                        .single())
                .isEqualTo("contact@example.invalid");
    }

    @Test
    void migrationIntroducesNoLaterPhaseTables() {
        var tables = jdbc.sql("""
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                        ORDER BY table_name
                        """)
                .query(String.class)
                .list();

        assertThat(tables)
                .doesNotContain(
                        "service",
                        "staff_member",
                        "customer",
                        "appointment",
                        "weekly_work_interval",
                        "schedule_break",
                        "time_off",
                        "working_override");
    }

    @Test
    void previousMigrationResourcesRemainByteForByteUnchanged() {
        assertThat(resourceSha256("db/migration/V1__identity_and_tenancy.sql"))
                .isEqualTo(V1_SHA_256);
        assertThat(resourceSha256("db/migration/V2__enforce_single_active_identity_tokens.sql"))
                .isEqualTo(V2_SHA_256);
    }

    private UUID createBusiness() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, 'Business Schema Test', 'OTHER', 'DRAFT',
                            'Europe/Sofia', :now, :now)
                        """)
                .param("id", id)
                .param("slug", "business-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private void updateColumn(UUID businessId, ProfileColumn column, String value) {
        jdbc.sql("UPDATE business SET " + column.columnName() + " = :value WHERE id = :id")
                .param("value", value)
                .param("id", businessId)
                .update();
    }

    private static Stream<Arguments> whitespaceProfileValues() {
        var whitespaceValues = List.of("", "   ", "\t", "\n", " \t\n ");
        return Stream.of(ProfileColumn.values())
                .flatMap(column -> whitespaceValues.stream()
                        .map(whitespace -> Arguments.of(column, whitespace)));
    }

    private String resourceSha256(String path) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Migration resource is missing: " + path);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Migration resource could not be verified", exception);
        }
    }

    private enum ProfileColumn {
        DESCRIPTION("description", 2000),
        ADDRESS("address", 500),
        PHONE("phone", 50),
        CONTACT_EMAIL("contact_email", 320);

        private final String columnName;
        private final int maximumLength;

        ProfileColumn(String columnName, int maximumLength) {
            this.columnName = columnName;
            this.maximumLength = maximumLength;
        }

        String columnName() {
            return columnName;
        }

        int maximumLength() {
            return maximumLength;
        }
    }

    private record ColumnMetadata(String name, int maximumLength, String nullable) {
    }

    private record BusinessProfile(
            String description,
            String address,
            String phone,
            String contactEmail) {
    }
}
