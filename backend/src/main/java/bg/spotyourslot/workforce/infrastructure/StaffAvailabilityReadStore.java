package bg.spotyourslot.workforce.infrastructure;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffMemberPersistenceException.UnexpectedFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        List<PeriodRow> rows;
        try {
            rows = jdbc.sql("""
                            SELECT m.id AS staff_member_id,
                                   p.weekday, p.start_time, p.end_time
                            FROM staff_member m
                            JOIN staff_member_service a
                              ON a.business_id = m.business_id
                             AND a.staff_member_id = m.id
                            LEFT JOIN staff_working_period p
                              ON p.business_id = m.business_id
                             AND p.staff_member_id = m.id
                            WHERE m.business_id = :businessId
                              AND a.service_id = :serviceId
                              AND m.active
                            ORDER BY m.id ASC, p.weekday ASC, p.start_time ASC, p.end_time ASC
                            """)
                    .param("businessId", businessId)
                    .param("serviceId", serviceId)
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
