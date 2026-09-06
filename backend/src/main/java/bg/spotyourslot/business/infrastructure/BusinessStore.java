package bg.spotyourslot.business.infrastructure;

import bg.spotyourslot.business.domain.BusinessSlug;
import bg.spotyourslot.business.domain.BusinessStatus;
import bg.spotyourslot.business.domain.BusinessTimezone;
import bg.spotyourslot.business.domain.BusinessType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessStore {
    public static final int MAX_PAGE_SIZE = 100;

    private static final String RETURNING_COLUMNS = """
            id, slug, display_name, business_type, status, timezone,
            description, city, postal_code, street, street_number, address_details,
            phone, contact_email, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public BusinessStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BusinessRow> list(int page, int size) {
        validatePage(page, size);
        long offset = Math.multiplyExact((long) page, size);

        return jdbc.sql("""
                        SELECT id, slug, display_name, business_type, status, timezone,
                               description, city, postal_code, street, street_number,
                               address_details, phone, contact_email, version,
                               created_at, updated_at
                        FROM business
                        ORDER BY created_at DESC, id DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("size", size)
                .param("offset", offset)
                .query(this::businessRow)
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM business")
                .query(Long.class)
                .single();
    }

    public Optional<BusinessRow> findById(UUID businessId) {
        return jdbc.sql("""
                        SELECT id, slug, display_name, business_type, status, timezone,
                               description, city, postal_code, street, street_number,
                               address_details, phone, contact_email, version,
                               created_at, updated_at
                        FROM business
                        WHERE id = :businessId
                        """)
                .param("businessId", businessId)
                .query(this::businessRow)
                .optional();
    }

    public BusinessRow create(NewBusinessRow business) {
        return jdbc.sql("""
                        INSERT INTO business(
                            id, slug, display_name, business_type, status, timezone,
                            description, city, postal_code, street, street_number,
                            address_details, phone, contact_email, version,
                            created_at, updated_at)
                        VALUES (
                            :id, :slug, :displayName, :businessType, 'DRAFT', :timezone,
                            :description, :city, :postalCode, :street, :streetNumber,
                            :addressDetails, :phone, :contactEmail, 0,
                            :createdAt, :createdAt)
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("id", business.id())
                .param("slug", business.slug().value())
                .param("displayName", business.displayName())
                .param("businessType", business.businessType().name())
                .param("timezone", business.timezone().value())
                .param("description", business.description())
                .param("city", business.city())
                .param("postalCode", business.postalCode())
                .param("street", business.street())
                .param("streetNumber", business.streetNumber())
                .param("addressDetails", business.addressDetails())
                .param("phone", business.phone())
                .param("contactEmail", business.contactEmail())
                .param("createdAt", databaseTime(business.createdAt()))
                .query(this::businessRow)
                .single();
    }

    public Optional<BusinessRow> updateProfile(
            UUID businessId, BusinessProfileUpdateRow update) {
        return jdbc.sql("""
                        UPDATE business
                        SET slug = :slug,
                            display_name = :displayName,
                            business_type = :businessType,
                            timezone = :timezone,
                            description = :description,
                            city = :city,
                            postal_code = :postalCode,
                            street = :street,
                            street_number = :streetNumber,
                            address_details = :addressDetails,
                            phone = :phone,
                            contact_email = :contactEmail,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE id = :businessId
                          AND version = :expectedVersion
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("slug", update.slug().value())
                .param("displayName", update.displayName())
                .param("businessType", update.businessType().name())
                .param("timezone", update.timezone().value())
                .param("description", update.description())
                .param("city", update.city())
                .param("postalCode", update.postalCode())
                .param("street", update.street())
                .param("streetNumber", update.streetNumber())
                .param("addressDetails", update.addressDetails())
                .param("phone", update.phone())
                .param("contactEmail", update.contactEmail())
                .param("updatedAt", databaseTime(update.updatedAt()))
                .param("businessId", businessId)
                .param("expectedVersion", update.expectedVersion())
                .query(this::businessRow)
                .optional();
    }

    public Optional<BusinessRow> transition(
            UUID businessId,
            BusinessStatus expectedStatus,
            BusinessStatus targetStatus,
            long expectedVersion,
            Instant updatedAt) {
        if (!expectedStatus.canTransitionTo(targetStatus)) {
            throw new IllegalArgumentException("Business lifecycle transition is not allowed");
        }

        return jdbc.sql("""
                        UPDATE business
                        SET status = :targetStatus,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE id = :businessId
                          AND status = :expectedStatus
                          AND version = :expectedVersion
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("targetStatus", targetStatus.name())
                .param("updatedAt", databaseTime(updatedAt))
                .param("businessId", businessId)
                .param("expectedStatus", expectedStatus.name())
                .param("expectedVersion", expectedVersion)
                .query(this::businessRow)
                .optional();
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size is outside the supported range");
        }
    }

    private BusinessRow businessRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new BusinessRow(
                resultSet.getObject("id", UUID.class),
                new BusinessSlug(resultSet.getString("slug")),
                resultSet.getString("display_name"),
                BusinessType.valueOf(resultSet.getString("business_type")),
                BusinessStatus.valueOf(resultSet.getString("status")),
                new BusinessTimezone(resultSet.getString("timezone")),
                resultSet.getString("description"),
                resultSet.getString("city"),
                resultSet.getString("postal_code"),
                resultSet.getString("street"),
                resultSet.getString("street_number"),
                resultSet.getString("address_details"),
                resultSet.getString("phone"),
                resultSet.getString("contact_email"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
