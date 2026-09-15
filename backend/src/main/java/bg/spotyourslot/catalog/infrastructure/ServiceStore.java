package bg.spotyourslot.catalog.infrastructure;

import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.NameConflict;
import bg.spotyourslot.catalog.infrastructure.ServicePersistenceException.UnexpectedFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ServiceStore {
    public static final int MAX_PAGE_SIZE = 100;

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final String NAME_CONSTRAINT = "service_business_normalized_name_unique";
    private static final Pattern NAME_CONSTRAINT_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_])" + Pattern.quote(NAME_CONSTRAINT) + "(?![A-Za-z0-9_])");
    private static final String RETURNING_COLUMNS = """
            id, business_id, name, description, duration_minutes, price,
            active, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public ServiceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ServiceRow> list(UUID businessId, int page, int size) {
        validatePage(page, size);
        long offset = Math.multiplyExact((long) page, size);

        return execute(() -> jdbc.sql("""
                        SELECT id, business_id, name, description, duration_minutes, price,
                               active, version, created_at, updated_at
                        FROM service
                        WHERE business_id = :businessId
                        ORDER BY normalized_name ASC, id ASC
                        LIMIT :size OFFSET :offset
                        """)
                .param("businessId", businessId)
                .param("size", size)
                .param("offset", offset)
                .query(this::serviceRow)
                .list());
    }

    public long count(UUID businessId) {
        return execute(() -> jdbc.sql("""
                        SELECT count(*)
                        FROM service
                        WHERE business_id = :businessId
                        """)
                .param("businessId", businessId)
                .query(Long.class)
                .single());
    }

    public Optional<ServiceRow> findByBusinessIdAndId(UUID businessId, UUID serviceId) {
        return execute(() -> jdbc.sql("""
                        SELECT id, business_id, name, description, duration_minutes, price,
                               active, version, created_at, updated_at
                        FROM service
                        WHERE business_id = :businessId
                          AND id = :serviceId
                        """)
                .param("businessId", businessId)
                .param("serviceId", serviceId)
                .query(this::serviceRow)
                .optional());
    }

    public ServiceRow create(NewServiceRow service) {
        return execute(() -> jdbc.sql("""
                        INSERT INTO service(
                            id, business_id, name, description, duration_minutes, price,
                            active, version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :name, :description, :durationMinutes, :price,
                            true, 0, :createdAt, :createdAt)
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("id", service.id())
                .param("businessId", service.businessId())
                .param("name", service.name())
                .param("description", service.description())
                .param("durationMinutes", service.durationMinutes())
                .param("price", service.price())
                .param("createdAt", databaseTime(service.createdAt()))
                .query(this::serviceRow)
                .single());
    }

    public Optional<ServiceRow> update(
            UUID businessId, UUID serviceId, ServiceUpdateRow update) {
        return execute(() -> jdbc.sql("""
                        UPDATE service
                        SET name = :name,
                            description = :description,
                            duration_minutes = :durationMinutes,
                            price = :price,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE business_id = :businessId
                          AND id = :serviceId
                          AND version = :expectedVersion
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("name", update.name())
                .param("description", update.description())
                .param("durationMinutes", update.durationMinutes())
                .param("price", update.price())
                .param("updatedAt", databaseTime(update.updatedAt()))
                .param("businessId", businessId)
                .param("serviceId", serviceId)
                .param("expectedVersion", update.expectedVersion())
                .query(this::serviceRow)
                .optional());
    }

    public Optional<ServiceRow> deactivate(
            UUID businessId, UUID serviceId, long expectedVersion, Instant updatedAt) {
        return transition(businessId, serviceId, expectedVersion, true, false, updatedAt);
    }

    public Optional<ServiceRow> reactivate(
            UUID businessId, UUID serviceId, long expectedVersion, Instant updatedAt) {
        return transition(businessId, serviceId, expectedVersion, false, true, updatedAt);
    }

    private Optional<ServiceRow> transition(
            UUID businessId,
            UUID serviceId,
            long expectedVersion,
            boolean expectedActive,
            boolean targetActive,
            Instant updatedAt) {
        return execute(() -> jdbc.sql("""
                        UPDATE service
                        SET active = :targetActive,
                            version = version + 1,
                            updated_at = :updatedAt
                        WHERE business_id = :businessId
                          AND id = :serviceId
                          AND version = :expectedVersion
                          AND active = :expectedActive
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("targetActive", targetActive)
                .param("updatedAt", databaseTime(updatedAt))
                .param("businessId", businessId)
                .param("serviceId", serviceId)
                .param("expectedVersion", expectedVersion)
                .param("expectedActive", expectedActive)
                .query(this::serviceRow)
                .optional());
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException exception) {
            if (isNameConflict(exception)) {
                throw new NameConflict();
            }
            throw new UnexpectedFailure(exception);
        } catch (DataAccessException exception) {
            throw new UnexpectedFailure(exception);
        }
    }

    static boolean isNameConflict(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sqlException
                    && sqlExceptionContainsNameConflict(sqlException, visited)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean sqlExceptionContainsNameConflict(
            SQLException exception, Set<Throwable> visited) {
        SQLException current = exception;
        while (current != null) {
            if (UNIQUE_VIOLATION_SQL_STATE.equals(current.getSQLState())
                    && containsConstraintToken(current.getMessage())) {
                return true;
            }
            SQLException next = current.getNextException();
            if (next == null || !visited.add(next)) {
                return false;
            }
            current = next;
        }
        return false;
    }

    private static boolean containsConstraintToken(String diagnostic) {
        return diagnostic != null && NAME_CONSTRAINT_TOKEN.matcher(diagnostic).find();
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size is outside the supported range");
        }
    }

    private ServiceRow serviceRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ServiceRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("business_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getInt("duration_minutes"),
                resultSet.getBigDecimal("price"),
                resultSet.getBoolean("active"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
