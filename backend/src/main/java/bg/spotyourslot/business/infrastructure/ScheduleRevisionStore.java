package bg.spotyourslot.business.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only reader and writer of {@code business_schedule_revision}. One statement per method, each
 * scoped to one Business, with no own transaction.
 */
@Repository
public class ScheduleRevisionStore {
    private final JdbcClient jdbc;

    public ScheduleRevisionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes the exclusive row lock and creates a new row version; empty when the row is missing. */
    public Optional<Long> advance(UUID businessId, Instant updatedAt) {
        return jdbc.sql("""
                        UPDATE business_schedule_revision
                        SET revision = revision + 1,
                            updated_at = :updatedAt
                        WHERE business_id = :businessId
                        RETURNING revision
                        """)
                .param("updatedAt", OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
                .param("businessId", businessId)
                .query(Long.class)
                .optional();
    }

    /** Takes the shared row lock; empty when the row is missing. */
    public Optional<Long> lockShared(UUID businessId) {
        return jdbc.sql("""
                        SELECT revision
                        FROM business_schedule_revision
                        WHERE business_id = :businessId
                        FOR SHARE
                        """)
                .param("businessId", businessId)
                .query(Long.class)
                .optional();
    }
}
