package bg.spotyourslot.workforce.infrastructure;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only, lock-free, tenant-scoped query for availability calculation. One
 * joined statement returns every eligible StaffMember with its recurring
 * periods, so the statement count does not depend on the number of StaffMembers.
 */
@Repository
public class StaffAvailabilityReadStore {
    private final JdbcClient jdbc;

    public StaffAvailabilityReadStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EligibleStaffRow> findEligibleForService(UUID businessId, UUID serviceId) {
        return findEligibleForServices(businessId, List.of(serviceId));
    }

    /**
     * The StaffMember must hold an assignment to every requested Service: the number of its
     * assignments inside the requested set equals the set size. The assignment key is
     * (StaffMember, Service), so each counted row is a distinct Service.
     */
    public List<EligibleStaffRow> findEligibleForServices(
            UUID businessId, Collection<UUID> serviceIds) {
        Set<UUID> distinct = new HashSet<>(serviceIds);
        if (distinct.isEmpty() || distinct.size() != serviceIds.size()) {
            throw new IllegalArgumentException("Service identifiers must be non-empty and distinct");
        }
        List<PeriodRow> rows;
        try {
            rows = jdbc.sql("""
                            SELECT m.id AS staff_member_id,
                                   p.weekday, p.start_time, p.end_time
                            FROM staff_member m
                            LEFT JOIN staff_working_period p
                              ON p.business_id = m.business_id
                             AND p.staff_member_id = m.id
                            WHERE m.business_id = :businessId
                              AND m.active
                              AND (SELECT count(*)
                                   FROM staff_member_service a
                                   WHERE a.business_id = m.business_id
                                     AND a.staff_member_id = m.id
                                     AND a.service_id IN (:serviceIds)) = :serviceCount
                            ORDER BY m.id ASC, p.weekday ASC, p.start_time ASC, p.end_time ASC
                            """)
                    .param("businessId", businessId)
                    .param("serviceIds", distinct)
                    .param("serviceCount", distinct.size())
                    .query(this::periodRow)
                    .list();
        } catch (DataAccessException exception) {
            throw new UnexpectedFailure(exception);
        }

        Map<UUID, List<WorkingPeriod>> byStaffMember = new LinkedHashMap<>();
        for (PeriodRow row : rows) {
            List<WorkingPeriod> periods = byStaffMember.computeIfAbsent(
                    row.staffMemberId(), id -> new ArrayList<>());
            if (row.period() != null) {
                periods.add(row.period());
            }
        }
        List<EligibleStaffRow> result = new ArrayList<>(byStaffMember.size());
        byStaffMember.forEach((id, periods) -> result.add(new EligibleStaffRow(id, periods)));
        return List.copyOf(result);
    }

    /**
     * One joined statement: the active StaffMember of the Business with its recurring periods,
     * with no assignment predicate. A missing, foreign, or inactive StaffMember gives empty.
     */
    public Optional<EligibleStaffRow> findActiveWithPeriods(UUID businessId, UUID staffMemberId) {
        List<PeriodRow> rows;
        try {
            rows = jdbc.sql("""
                            SELECT m.id AS staff_member_id,
                                   p.weekday, p.start_time, p.end_time
                            FROM staff_member m
                            LEFT JOIN staff_working_period p
                              ON p.business_id = m.business_id
                             AND p.staff_member_id = m.id
                            WHERE m.business_id = :businessId
                              AND m.id = :staffMemberId
                              AND m.active
                            ORDER BY p.weekday ASC, p.start_time ASC, p.end_time ASC
                            """)
                    .param("businessId", businessId)
                    .param("staffMemberId", staffMemberId)
                    .query(this::periodRow)
                    .list();
        } catch (DataAccessException exception) {
            throw new UnexpectedFailure(exception);
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<WorkingPeriod> periods = new ArrayList<>();
        for (PeriodRow row : rows) {
            if (row.period() != null) {
                periods.add(row.period());
            }
        }
        return Optional.of(new EligibleStaffRow(rows.get(0).staffMemberId(), periods));
    }

    /**
     * One joined statement for the Business calendar: every StaffMember of the Business (or only
     * the requested one) in the deterministic order {@code normalized_display_name, id}. Recurring
     * periods are joined for active StaffMembers only, and only when requested.
     */
    public List<CalendarStaffRow> findCalendarStaff(
            UUID businessId, UUID staffMemberIdOrNull, boolean withWeeklyPeriods) {
        String periodColumns = withWeeklyPeriods
                ? "p.weekday, p.start_time, p.end_time"
                : "NULL::smallint AS weekday, NULL::time AS start_time, NULL::time AS end_time";
        String periodJoin = withWeeklyPeriods
                ? """
                  LEFT JOIN staff_working_period p
                    ON p.business_id = m.business_id
                   AND p.staff_member_id = m.id
                   AND m.active
                  """
                : "";
        String periodOrder = withWeeklyPeriods
                ? ", p.weekday ASC, p.start_time ASC, p.end_time ASC"
                : "";
        String filter = staffMemberIdOrNull == null ? "" : "AND m.id = :staffMemberId\n";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT m.id AS staff_member_id, m.display_name, m.active,
                        """ + periodColumns + "\n" + """
                        FROM staff_member m
                        """ + periodJoin + """
                        WHERE m.business_id = :businessId
                        """ + filter + """
                        ORDER BY m.normalized_display_name ASC, m.id ASC
                        """ + periodOrder)
                .param("businessId", businessId);
        if (staffMemberIdOrNull != null) {
            statement = statement.param("staffMemberId", staffMemberIdOrNull);
        }
        JdbcClient.StatementSpec prepared = statement;
        List<CalendarRow> rows;
        try {
            rows = prepared.query((resultSet, rowNumber) -> {
                int weekday = resultSet.getInt("weekday");
                WorkingPeriod period = resultSet.wasNull()
                        ? null
                        : new WorkingPeriod(
                                DayOfWeek.of(weekday),
                                resultSet.getObject("start_time", LocalTime.class),
                                resultSet.getObject("end_time", LocalTime.class));
                return new CalendarRow(
                        resultSet.getObject("staff_member_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getBoolean("active"),
                        period);
            }).list();
        } catch (DataAccessException exception) {
            throw new UnexpectedFailure(exception);
        }
        Map<UUID, CalendarStaffBuilder> byStaff = new LinkedHashMap<>();
        for (CalendarRow row : rows) {
            CalendarStaffBuilder builder = byStaff.computeIfAbsent(
                    row.id(), id -> new CalendarStaffBuilder(row.displayName(), row.active()));
            if (row.period() != null) {
                builder.periods.add(row.period());
            }
        }
        List<CalendarStaffRow> result = new ArrayList<>(byStaff.size());
        byStaff.forEach((id, builder) ->
                result.add(new CalendarStaffRow(id, builder.displayName, builder.active, builder.periods)));
        return List.copyOf(result);
    }

    private record CalendarRow(UUID id, String displayName, boolean active, WorkingPeriod period) {
    }

    private static final class CalendarStaffBuilder {
        private final String displayName;
        private final boolean active;
        private final List<WorkingPeriod> periods = new ArrayList<>();

        private CalendarStaffBuilder(String displayName, boolean active) {
            this.displayName = displayName;
            this.active = active;
        }
    }

    private PeriodRow periodRow(ResultSet resultSet, int rowNumber) throws SQLException {
        int weekday = resultSet.getInt("weekday");
        WorkingPeriod period = resultSet.wasNull()
                ? null
                : new WorkingPeriod(
                        DayOfWeek.of(weekday),
                        resultSet.getObject("start_time", LocalTime.class),
                        resultSet.getObject("end_time", LocalTime.class));
        return new PeriodRow(resultSet.getObject("staff_member_id", UUID.class), period);
    }

    private record PeriodRow(UUID staffMemberId, WorkingPeriod period) {
    }
}
