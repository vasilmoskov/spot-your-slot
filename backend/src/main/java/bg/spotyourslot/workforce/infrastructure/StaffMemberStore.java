package bg.spotyourslot.workforce.infrastructure;

import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StaffMemberStore {
    public static final int MAX_PAGE_SIZE = 100;

    private static final String RETURNING_COLUMNS = """
            id, business_id, display_name, contact_email, contact_phone,
            active, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public StaffMemberStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<StaffMemberRow> list(UUID businessId, int page, int size) {
        validatePage(page, size);
        long offset = Math.multiplyExact((long) page, size);

        return execute(() -> jdbc.sql("""
                        SELECT id, business_id, display_name, contact_email, contact_phone,
                               active, version, created_at, updated_at
                        FROM staff_member
                        WHERE business_id = :businessId
                        ORDER BY normalized_display_name ASC, id ASC
                        LIMIT :size OFFSET :offset
                        """)
                .param("businessId", businessId)
                .param("size", size)
                .param("offset", offset)
                .query(this::staffMemberRow)
                .list());
    }

    public long count(UUID businessId) {
        return execute(() -> jdbc.sql("""
                        SELECT count(*)
                        FROM staff_member
                        WHERE business_id = :businessId
                        """)
                .param("businessId", businessId)
                .query(Long.class)
                .single());
    }

    public Optional<StaffMemberRow> findByBusinessIdAndId(
            UUID businessId, UUID staffMemberId) {
        return execute(() -> jdbc.sql("""
                        SELECT id, business_id, display_name, contact_email, contact_phone,
                               active, version, created_at, updated_at
                        FROM staff_member
                        WHERE business_id = :businessId
                          AND id = :staffMemberId
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .query(this::staffMemberRow)
                .optional());
    }

    public StaffMemberRow create(NewStaffMemberRow staffMember) {
        return execute(() -> jdbc.sql("""
                        INSERT INTO staff_member(
                            id, business_id, display_name, contact_email, contact_phone,
                            active, version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :displayName, :contactEmail, :contactPhone,
                            true, 0, :createdAt, :createdAt)
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("id", staffMember.id())
                .param("businessId", staffMember.businessId())
                .param("displayName", staffMember.displayName())
                .param("contactEmail", staffMember.contactEmail())
                .param("contactPhone", staffMember.contactPhone())
                .param("createdAt", databaseTime(staffMember.createdAt()))
                .query(this::staffMemberRow)
                .single());
    }

    public Optional<StaffMemberRow> updateProfile(
            UUID businessId,
            UUID staffMemberId,
            StaffMemberProfileUpdateRow update) {
        return execute(() -> jdbc.sql("""
                        UPDATE staff_member
                        SET display_name = :displayName,
                            contact_email = :contactEmail,
                            contact_phone = :contactPhone,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE business_id = :businessId
                          AND id = :staffMemberId
                          AND version = :expectedVersion
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("displayName", update.displayName())
                .param("contactEmail", update.contactEmail())
                .param("contactPhone", update.contactPhone())
                .param("updatedAt", databaseTime(update.updatedAt()))
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .param("expectedVersion", update.expectedVersion())
                .query(this::staffMemberRow)
                .optional());
    }

    public Optional<StaffMemberRow> deactivate(
            UUID businessId,
            UUID staffMemberId,
            long expectedVersion,
            Instant updatedAt) {
        return transition(
                businessId, staffMemberId, expectedVersion, true, false, updatedAt);
    }

    public Optional<StaffMemberRow> reactivate(
            UUID businessId,
            UUID staffMemberId,
            long expectedVersion,
            Instant updatedAt) {
        return transition(
                businessId, staffMemberId, expectedVersion, false, true, updatedAt);
    }

    public List<UUID> listAssignedServiceIds(UUID businessId, UUID staffMemberId) {
        return execute(() -> jdbc.sql("""
                        SELECT service_id
                        FROM staff_member_service
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        ORDER BY service_id ASC
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .query(UUID.class)
                .list());
    }

    public Optional<StaffMemberRow> advanceAssignmentVersion(
            UUID businessId,
            UUID staffMemberId,
            long expectedVersion,
            Instant updatedAt) {
        return execute(() -> jdbc.sql("""
                        UPDATE staff_member
                        SET version = version + 1,
                            updated_at = :updatedAt
                        WHERE business_id = :businessId
                          AND id = :staffMemberId
                          AND version = :expectedVersion
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("updatedAt", databaseTime(updatedAt))
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .param("expectedVersion", expectedVersion)
                .query(this::staffMemberRow)
                .optional());
    }

    public void reconcileServiceAssignments(
            UUID businessId,
            UUID staffMemberId,
            Collection<UUID> removals,
            Collection<UUID> additions) {
        execute(() -> {
            for (UUID serviceId : removals) {
                jdbc.sql("""
                                DELETE FROM staff_member_service
                                WHERE business_id = :businessId
                                  AND staff_member_id = :staffMemberId
                                  AND service_id = :serviceId
                                """)
                        .param("businessId", businessId)
                        .param("staffMemberId", staffMemberId)
                        .param("serviceId", serviceId)
                        .update();
            }
            for (UUID serviceId : additions) {
                jdbc.sql("""
                                INSERT INTO staff_member_service(
                                    business_id, staff_member_id, service_id)
                                VALUES (:businessId, :staffMemberId, :serviceId)
                                """)
                        .param("businessId", businessId)
                        .param("staffMemberId", staffMemberId)
                        .param("serviceId", serviceId)
                        .update();
            }
            return null;
        });
    }

    private Optional<StaffMemberRow> transition(
            UUID businessId,
            UUID staffMemberId,
            long expectedVersion,
            boolean expectedActive,
            boolean targetActive,
            Instant updatedAt) {
        return execute(() -> jdbc.sql("""
                        UPDATE staff_member
                        SET active = :targetActive,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE business_id = :businessId
                          AND id = :staffMemberId
                          AND version = :expectedVersion
                          AND active = :expectedActive
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("targetActive", targetActive)
                .param("updatedAt", databaseTime(updatedAt))
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .param("expectedVersion", expectedVersion)
                .param("expectedActive", expectedActive)
                .query(this::staffMemberRow)
                .optional());
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            throw new UnexpectedFailure(exception);
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size is outside the supported range");
        }
    }

    private StaffMemberRow staffMemberRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StaffMemberRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("business_id", UUID.class),
                resultSet.getString("display_name"),
                resultSet.getString("contact_email"),
                resultSet.getString("contact_phone"),
                resultSet.getBoolean("active"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
