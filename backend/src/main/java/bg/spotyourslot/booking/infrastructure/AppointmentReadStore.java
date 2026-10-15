package bg.spotyourslot.booking.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Internal, read-only, lock-free Appointment reads for the Business calendar (ADR-0028 sections 11
 * and 12). It owns the read statements and returns plain rows, never persistence entities and never
 * domain objects. It is not a published contract and it opens no transaction: it joins the
 * caller's, so every statement of one operation belongs to one snapshot.
 *
 * <p>Every statement filters by {@code business_id}. A window read includes an Appointment when its
 * occupied time overlaps the half-open window {@code [from, to)}:
 * {@code start_at < to AND occupied_until > from}, with the prefilter
 * {@code start_at >= from - 480 minutes} (480 is the checked maximum duration, so no overlapping
 * Appointment starts earlier) that gives a bounded range for an index on {@code (business_id,
 * start_at, id)} to scan. Both statuses are returned. Collections have a total order: the window by
 * {@code (start_at, id)}, the Customer history by {@code (start_at DESC, id DESC)}, the lines by
 * {@code (appointment_id, position)}. A failure surfaces as a plain {@link DataAccessException};
 * the service replaces it with a fixed, cause-free exception.
 */
@Repository
public class AppointmentReadStore {
    /** The checked maximum visit duration in minutes ({@code appointment_duration_minutes_range}). */
    static final long MAX_DURATION_MINUTES = 480;

    private static final String WINDOW_PREDICATE = """
            a.business_id = :businessId
              AND a.start_at >= :earliestStart
              AND a.start_at < :to
              AND a.occupied_until > :from
            """;

    private static final String LIST_COLUMNS = """
            a.id, a.status, a.source, a.start_at, a.end_at, a.staff_member_id,
            a.staff_display_name, a.customer_id, a.duration_minutes
            """;

    private final JdbcClient jdbc;

    public AppointmentReadStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of a calendar or agenda list. */
    public record ListRow(
            UUID id,
            String status,
            String source,
            Instant startAt,
            Instant endAt,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            int durationMinutes) {
    }

    /** One row of a Customer history. */
    public record HistoryRow(
            UUID id, String status, String source, Instant startAt, Instant endAt,
            String staffDisplayName) {
    }

    /** The complete row of an Appointment detail, including the private facts. */
    public record DetailRow(
            UUID id,
            String publicReference,
            String status,
            String source,
            Instant startAt,
            Instant endAt,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            int durationMinutes,
            BigDecimal priceEur,
            String customerNote,
            String internalNote,
            Instant cancelledAt,
            String cancelledBy,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record LineRow(
            UUID appointmentId, int position, String serviceName, int durationMinutes, BigDecimal priceEur) {
    }

    // ---- statements, exposed so the EXPLAIN evidence runs the production text -------------------

    static String windowListSql(boolean filteredByStaff, boolean paged) {
        return "SELECT " + LIST_COLUMNS + "FROM appointment a\nWHERE " + WINDOW_PREDICATE
                + (filteredByStaff ? "  AND a.staff_member_id = :staffMemberId\n" : "")
                + "ORDER BY a.start_at ASC, a.id ASC\n"
                + (paged ? "LIMIT :size OFFSET :offset\n" : "");
    }

    static String windowCountSql(boolean filteredByStaff) {
        return "SELECT count(*) FROM appointment a\nWHERE " + WINDOW_PREDICATE
                + (filteredByStaff ? "  AND a.staff_member_id = :staffMemberId\n" : "");
    }

    static String historyListSql() {
        return """
                SELECT a.id, a.status, a.source, a.start_at, a.end_at, a.staff_display_name
                FROM appointment a
                WHERE a.business_id = :businessId
                  AND a.customer_id = :customerId
                ORDER BY a.start_at DESC, a.id DESC
                LIMIT :size OFFSET :offset
                """;
    }

    static String historyCountSql() {
        return """
                SELECT count(*)
                FROM appointment a
                WHERE a.business_id = :businessId
                  AND a.customer_id = :customerId
                """;
    }

    static String linesSql() {
        return """
                SELECT l.appointment_id, l.position, l.service_name, l.duration_minutes, l.price_eur
                FROM appointment_service l
                WHERE l.business_id = :businessId
                  AND l.appointment_id = ANY (:appointmentIds)
                ORDER BY l.appointment_id ASC, l.position ASC
                """;
    }

    // ---- window reads (calendar and agenda) -----------------------------------------------------

    /** One statement: the whole window, ordered by {@code (start_at, id)}. */
    public List<ListRow> findWindow(UUID businessId, Instant from, Instant to, UUID staffMemberIdOrNull) {
        return windowQuery(windowListSql(staffMemberIdOrNull != null, false), businessId, from, to,
                staffMemberIdOrNull)
                .query(this::listRow)
                .list();
    }

    /** One statement: the number of Appointments in the window. */
    public long countWindow(UUID businessId, Instant from, Instant to, UUID staffMemberIdOrNull) {
        return windowQuery(windowCountSql(staffMemberIdOrNull != null), businessId, from, to,
                staffMemberIdOrNull)
                .query(Long.class)
                .single();
    }

    /** One statement: one page of the window, ordered by {@code (start_at, id)}. */
    public List<ListRow> findWindowPage(
            UUID businessId, Instant from, Instant to, UUID staffMemberIdOrNull, long offset, int size) {
        return windowQuery(windowListSql(staffMemberIdOrNull != null, true), businessId, from, to,
                staffMemberIdOrNull)
                .param("offset", offset)
                .param("size", size)
                .query(this::listRow)
                .list();
    }

    private JdbcClient.StatementSpec windowQuery(
            String sql, UUID businessId, Instant from, Instant to, UUID staffMemberIdOrNull) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("businessId", businessId)
                .param("earliestStart", databaseTime(from.minus(Duration.ofMinutes(MAX_DURATION_MINUTES))))
                .param("from", databaseTime(from))
                .param("to", databaseTime(to));
        if (staffMemberIdOrNull != null) {
            statement = statement.param("staffMemberId", staffMemberIdOrNull);
        }
        return statement;
    }

    // ---- Customer history -----------------------------------------------------------------------

    public long countHistory(UUID businessId, UUID customerId) {
        return jdbc.sql(historyCountSql())
                .param("businessId", businessId)
                .param("customerId", customerId)
                .query(Long.class)
                .single();
    }

    public List<HistoryRow> findHistoryPage(UUID businessId, UUID customerId, long offset, int size) {
        return jdbc.sql(historyListSql())
                .param("businessId", businessId)
                .param("customerId", customerId)
                .param("offset", offset)
                .param("size", size)
                .query((resultSet, rowNumber) -> new HistoryRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getString("source"),
                        instant(resultSet, "start_at"),
                        instant(resultSet, "end_at"),
                        resultSet.getString("staff_display_name")))
                .list();
    }

    // ---- detail and lines -----------------------------------------------------------------------

    /** One statement; a foreign-Business identifier behaves exactly like a missing one. */
    public Optional<DetailRow> findDetail(UUID businessId, UUID appointmentId) {
        return jdbc.sql("""
                        SELECT a.id, a.public_reference, a.status, a.source, a.start_at, a.end_at,
                               a.staff_member_id, a.staff_display_name, a.customer_id,
                               a.duration_minutes, a.price_eur, a.customer_note, a.internal_note,
                               a.cancelled_at, a.cancelled_by, a.version, a.created_at, a.updated_at
                        FROM appointment a
                        WHERE a.business_id = :businessId
                          AND a.id = :appointmentId
                        """)
                .param("businessId", businessId)
                .param("appointmentId", appointmentId)
                .query((resultSet, rowNumber) -> new DetailRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("public_reference"),
                        resultSet.getString("status"),
                        resultSet.getString("source"),
                        instant(resultSet, "start_at"),
                        instant(resultSet, "end_at"),
                        resultSet.getObject("staff_member_id", UUID.class),
                        resultSet.getString("staff_display_name"),
                        resultSet.getObject("customer_id", UUID.class),
                        resultSet.getInt("duration_minutes"),
                        resultSet.getBigDecimal("price_eur"),
                        resultSet.getString("customer_note"),
                        resultSet.getString("internal_note"),
                        optionalInstant(resultSet, "cancelled_at"),
                        resultSet.getString("cancelled_by"),
                        resultSet.getLong("version"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "updated_at")))
                .optional();
    }

    /**
     * One statement whatever the number of Appointments: the Service lines of the requested
     * Appointments of the Business in {@code (appointment_id, position)} order. An empty request
     * issues no statement.
     */
    public List<LineRow> findLines(UUID businessId, Collection<UUID> appointmentIds) {
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>(appointmentIds);
        if (distinct.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(linesSql())
                .param("businessId", businessId)
                .param("appointmentIds", distinct.toArray(UUID[]::new))
                .query((resultSet, rowNumber) -> new LineRow(
                        resultSet.getObject("appointment_id", UUID.class),
                        resultSet.getInt("position"),
                        resultSet.getString("service_name"),
                        resultSet.getInt("duration_minutes"),
                        resultSet.getBigDecimal("price_eur")))
                .list();
    }

    // ---- mapping --------------------------------------------------------------------------------

    private ListRow listRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ListRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("status"),
                resultSet.getString("source"),
                instant(resultSet, "start_at"),
                instant(resultSet, "end_at"),
                resultSet.getObject("staff_member_id", UUID.class),
                resultSet.getString("staff_display_name"),
                resultSet.getObject("customer_id", UUID.class),
                resultSet.getInt("duration_minutes"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant optionalInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
