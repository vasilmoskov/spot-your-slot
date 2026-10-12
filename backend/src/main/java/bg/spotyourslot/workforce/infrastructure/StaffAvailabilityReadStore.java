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
