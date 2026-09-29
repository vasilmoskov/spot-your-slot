package bg.spotyourslot.scheduling.infrastructure;

import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.NewScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException.ConcurrentWriteConflict;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException.InvalidReference;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException.OverlapConflict;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionPersistenceException.UnexpectedFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped persistence of schedule exception aggregates. It carries no
 * authorization, Business lifecycle, or StaffMember active-state behavior and
 * acquires no Business, Membership, or StaffMember locks; a later application
 * layer owns those.
 *
 * <p>{@link #replace} and {@link #delete} are conditional on Business, id, and
 * expected version. An empty result or {@code false} means only that no row
 * matched all of them: the aggregate may have been missing, stale, or deleted
 * concurrently. The store deliberately does not distinguish these cases.
 */
@Repository
public class ScheduleExceptionStore {
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String DEADLOCK_DETECTED = "40P01";
    private static final String SERIALIZATION_FAILURE = "40001";

    private static final String SELECT_AGGREGATES = """
            SELECT e.id, e.business_id, e.staff_member_id, e.kind, e.first_date,
                   e.last_date, e.all_day, e.version, e.created_at, e.updated_at,
                   p.start_time, p.end_time
            FROM schedule_exception e
            LEFT JOIN schedule_exception_period p
              ON p.business_id = e.business_id
             AND p.exception_id = e.id
            """;

    private static final String DETERMINISTIC_ORDER = """
            ORDER BY e.first_date ASC, e.last_date ASC, e.kind ASC,
                     e.staff_member_id ASC NULLS FIRST, e.id ASC,
                     p.start_time ASC, p.end_time ASC
            """;

    private static final String OVERLAPS_WINDOW = """
            e.date_range && pg_catalog.daterange(
                CAST(:fromDate AS date), CAST(:toDate AS date), '[]')
            """;

    private final JdbcClient jdbc;

    public ScheduleExceptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Joins an active caller transaction, or starts one, so a standalone call is atomic too.
    @Transactional
    public ScheduleException insert(NewScheduleException exception) {
        return execute(() -> {
            ScheduleExceptionContent content = exception.content();
            AggregateState state = jdbc.sql("""
                            INSERT INTO schedule_exception(
                                id, business_id, staff_member_id, kind, first_date,
                                last_date, all_day, version, created_at, updated_at)
                            VALUES (
                                :id, :businessId, :staffMemberId, :kind, :firstDate,
                                :lastDate, :allDay, 0, :createdAt, :createdAt)
                            RETURNING version, created_at, updated_at
                            """)
                    .param("id", exception.id())
                    .param("businessId", exception.businessId())
                    .param("staffMemberId", content.staffMemberId())
                    .param("kind", content.kind().name())
                    .param("firstDate", content.firstDate())
                    .param("lastDate", content.lastDate())
                    .param("allDay", content.allDay())
                    .param("createdAt", databaseTime(exception.createdAt()))
                    .query(this::aggregateState)
                    .single();
            insertPeriods(exception.businessId(), exception.id(), content.periods());
            return aggregate(exception.id(), exception.businessId(), content, state);
        });
    }

    public Optional<ScheduleException> findByBusinessIdAndId(UUID businessId, UUID id) {
        return execute(() -> assemble(jdbc.sql(SELECT_AGGREGATES + """
                        WHERE e.business_id = :businessId
                          AND e.id = :id
                        """ + DETERMINISTIC_ORDER)
                .param("businessId", businessId)
                .param("id", id)
                .query(this::flatRow)
                .list())
                .stream()
                .findFirst());
    }

    /** Lists every exception whose inclusive date range touches {@code from} through {@code to}. */
    public List<ScheduleException> findOverlapping(
            UUID businessId, LocalDate from, LocalDate to) {
        requireWindow(from, to);
        return execute(() -> assemble(jdbc.sql(SELECT_AGGREGATES + """
                        WHERE e.business_id = :businessId
                          AND\s""" + OVERLAPS_WINDOW + DETERMINISTIC_ORDER)
                .param("businessId", businessId)
                .param("fromDate", from)
                .param("toDate", to)
                .query(this::flatRow)
                .list()));
    }

    /**
     * Lists Business closures plus the exceptions of the given StaffMembers whose
     * inclusive date range touches {@code from} through {@code to}.
     */
    public List<ScheduleException> findOverlappingForStaff(
            UUID businessId, LocalDate from, LocalDate to, Collection<UUID> staffMemberIds) {
        requireWindow(from, to);
        Objects.requireNonNull(staffMemberIds, "staffMemberIds");
        if (staffMemberIds.isEmpty()) {
            return execute(() -> assemble(jdbc.sql(SELECT_AGGREGATES + """
                            WHERE e.business_id = :businessId
                              AND e.staff_member_id IS NULL
                              AND\s""" + OVERLAPS_WINDOW + DETERMINISTIC_ORDER)
                    .param("businessId", businessId)
                    .param("fromDate", from)
                    .param("toDate", to)
                    .query(this::flatRow)
                    .list()));
        }
        return execute(() -> assemble(jdbc.sql(SELECT_AGGREGATES + """
                        WHERE e.business_id = :businessId
                          AND (e.staff_member_id IS NULL
                               OR e.staff_member_id IN (:staffMemberIds))
                          AND\s""" + OVERLAPS_WINDOW + DETERMINISTIC_ORDER)
                .param("businessId", businessId)
                .param("staffMemberIds", List.copyOf(staffMemberIds))
                .param("fromDate", from)
                .param("toDate", to)
                .query(this::flatRow)
                .list()));
    }

    /**
     * Atomically replaces dates, shape, and periods, advancing the version and
     * update time exactly once. {@code kind} and StaffMember are immutable, so
     * they are part of the match predicate rather than assigned.
     *
     * @return the new aggregate, or empty when no row matches Business, id,
     *         expected version, kind, and StaffMember
     */
    // Joins an active caller transaction, or starts one, so a standalone call is atomic too.
    @Transactional
    public Optional<ScheduleException> replace(
            UUID businessId,
            UUID id,
            long expectedVersion,
            ScheduleExceptionContent content,
            Instant updatedAt) {
        return execute(() -> {
            Optional<AggregateState> state = jdbc.sql("""
                            UPDATE schedule_exception
                            SET first_date = :firstDate,
                                last_date = :lastDate,
                                all_day = :allDay,
                                version = version + 1,
                                updated_at = :updatedAt
                            WHERE business_id = :businessId
                              AND id = :id
                              AND version = :expectedVersion
                              AND kind = :kind
                              AND staff_member_id IS NOT DISTINCT FROM
                                  CAST(:staffMemberId AS uuid)
                            RETURNING version, created_at, updated_at
                            """)
                    .param("firstDate", content.firstDate())
                    .param("lastDate", content.lastDate())
                    .param("allDay", content.allDay())
                    .param("updatedAt", databaseTime(updatedAt))
                    .param("businessId", businessId)
                    .param("id", id)
                    .param("expectedVersion", expectedVersion)
                    .param("kind", content.kind().name())
                    .param("staffMemberId", content.staffMemberId())
                    .query(this::aggregateState)
                    .optional();
            if (state.isEmpty()) {
                return Optional.empty();
            }
            jdbc.sql("""
                            DELETE FROM schedule_exception_period
                            WHERE business_id = :businessId
                              AND exception_id = :id
                            """)
                    .param("businessId", businessId)
                    .param("id", id)
                    .update();
            insertPeriods(businessId, id, content.periods());
            return Optional.of(aggregate(id, businessId, content, state.get()));
        });
    }

    /**
     * Hard-deletes the aggregate and, by cascade, its periods in one statement.
     *
     * @return whether a row matched Business, id, and expected version
     */
    public boolean delete(UUID businessId, UUID id, long expectedVersion) {
        return execute(() -> jdbc.sql("""
                        DELETE FROM schedule_exception
                        WHERE business_id = :businessId
                          AND id = :id
                          AND version = :expectedVersion
                        """)
                .param("businessId", businessId)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1);
    }

    private void insertPeriods(UUID businessId, UUID exceptionId, List<LocalPeriod> periods) {
        for (LocalPeriod period : periods) {
            jdbc.sql("""
                            INSERT INTO schedule_exception_period(
                                business_id, exception_id, start_time, end_time)
                            VALUES (:businessId, :exceptionId, :startTime, :endTime)
                            """)
                    .param("businessId", businessId)
                    .param("exceptionId", exceptionId)
                    .param("startTime", period.start())
                    .param("endTime", period.end())
                    .update();
        }
    }

    private static void requireWindow(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Window end must not precede its start");
        }
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    private static ScheduleExceptionPersistenceException translate(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return switch (sqlException.getSQLState()) {
                    case EXCLUSION_VIOLATION -> new OverlapConflict(exception);
                    case FOREIGN_KEY_VIOLATION -> new InvalidReference(exception);
                    case DEADLOCK_DETECTED, SERIALIZATION_FAILURE ->
                            new ConcurrentWriteConflict(exception);
                    default -> new UnexpectedFailure(exception);
                };
            }
        }
        return new UnexpectedFailure(exception);
    }

    private static List<ScheduleException> assemble(List<FlatRow> rows) {
        Map<UUID, Assembly> byId = new LinkedHashMap<>();
        for (FlatRow row : rows) {
            Assembly assembly = byId.computeIfAbsent(row.id(), id -> new Assembly(row));
            if (row.startTime() != null) {
                assembly.periods.add(new LocalPeriod(row.startTime(), row.endTime()));
            }
        }
        List<ScheduleException> result = new ArrayList<>(byId.size());
        for (Assembly assembly : byId.values()) {
            FlatRow head = assembly.head;
            result.add(new ScheduleException(
                    head.id(),
                    head.businessId(),
                    new ScheduleExceptionContent(
                            head.kind(),
                            head.staffMemberId(),
                            head.firstDate(),
                            head.lastDate(),
                            head.allDay(),
                            assembly.periods),
                    head.version(),
                    head.createdAt(),
                    head.updatedAt()));
        }
        return List.copyOf(result);
    }

    private static ScheduleException aggregate(
            UUID id, UUID businessId, ScheduleExceptionContent content, AggregateState state) {
        return new ScheduleException(
                id, businessId, content, state.version(), state.createdAt(), state.updatedAt());
    }

    private AggregateState aggregateState(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AggregateState(
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private FlatRow flatRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FlatRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("business_id", UUID.class),
                resultSet.getObject("staff_member_id", UUID.class),
                ScheduleExceptionKind.valueOf(resultSet.getString("kind")),
                resultSet.getObject("first_date", LocalDate.class),
                resultSet.getObject("last_date", LocalDate.class),
                resultSet.getBoolean("all_day"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("start_time", LocalTime.class),
                resultSet.getObject("end_time", LocalTime.class));
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record AggregateState(long version, Instant createdAt, Instant updatedAt) {
    }

    private record FlatRow(
            UUID id,
            UUID businessId,
            UUID staffMemberId,
            ScheduleExceptionKind kind,
            LocalDate firstDate,
            LocalDate lastDate,
            boolean allDay,
            long version,
            Instant createdAt,
            Instant updatedAt,
            LocalTime startTime,
            LocalTime endTime) {
    }

    private static final class Assembly {
        private final FlatRow head;
        private final List<LocalPeriod> periods = new ArrayList<>();

        private Assembly(FlatRow head) {
            this.head = head;
        }
    }
}
