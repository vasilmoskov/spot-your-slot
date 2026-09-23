package bg.spotyourslot.workforce.infrastructure;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingSchedulePersistenceException.UnexpectedFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StaffWorkingScheduleStore {
    private static final String RETURNING_COLUMNS = """
            business_id, staff_member_id, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public StaffWorkingScheduleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public StaffWorkingScheduleRow create(NewStaffWorkingScheduleRow schedule) {
        return execute(() -> jdbc.sql("""
                        INSERT INTO staff_working_schedule(
                            business_id, staff_member_id, version, created_at, updated_at)
                        VALUES (:businessId, :staffMemberId, 0, :createdAt, :createdAt)
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("businessId", schedule.businessId())
                .param("staffMemberId", schedule.staffMemberId())
                .param("createdAt", databaseTime(schedule.createdAt()))
                .query(this::scheduleRow)
                .single());
    }

    public Optional<StaffWorkingScheduleRow> findByBusinessIdAndStaffMemberId(
            UUID businessId, UUID staffMemberId) {
        return execute(() -> jdbc.sql("""
                        SELECT business_id, staff_member_id, version, created_at, updated_at
                        FROM staff_working_schedule
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .query(this::scheduleRow)
                .optional());
    }

    public List<WorkingPeriod> findPeriods(UUID businessId, UUID staffMemberId) {
        return execute(() -> jdbc.sql("""
                        SELECT weekday, start_time, end_time
                        FROM staff_working_period
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                        ORDER BY weekday ASC, start_time ASC, end_time ASC
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .query(this::workingPeriod)
                .list());
    }

    public Optional<StaffWorkingScheduleRow> advanceScheduleVersion(
            UUID businessId, UUID staffMemberId, long expectedVersion, Instant updatedAt) {
        return execute(() -> jdbc.sql("""
                        UPDATE staff_working_schedule
                        SET version = version + 1,
                            updated_at = :updatedAt
                        WHERE business_id = :businessId
                          AND staff_member_id = :staffMemberId
                          AND version = :expectedVersion
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("updatedAt", databaseTime(updatedAt))
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .param("expectedVersion", expectedVersion)
                .query(this::scheduleRow)
                .optional());
    }

    // Joins an active caller transaction, or starts one, so a standalone call is atomic too.
    @Transactional
    public void replacePeriods(
            UUID businessId, UUID staffMemberId, List<WorkingPeriod> periods) {
        execute(() -> {
            jdbc.sql("""
                            DELETE FROM staff_working_period
                            WHERE business_id = :businessId
                              AND staff_member_id = :staffMemberId
                            """)
                    .param("businessId", businessId)
                    .param("staffMemberId", staffMemberId)
                    .update();
            for (WorkingPeriod period : periods) {
                jdbc.sql("""
                                INSERT INTO staff_working_period(
                                    business_id, staff_member_id, weekday,
                                    start_time, end_time)
                                VALUES (
                                    :businessId, :staffMemberId, :weekday,
                                    :startTime, :endTime)
                                """)
                        .param("businessId", businessId)
                        .param("staffMemberId", staffMemberId)
                        .param("weekday", period.weekday().getValue())
                        .param("startTime", period.startTime())
                        .param("endTime", period.endTime())
                        .update();
            }
            return null;
        });
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            throw new UnexpectedFailure(exception);
        }
    }

    private StaffWorkingScheduleRow scheduleRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StaffWorkingScheduleRow(
                resultSet.getObject("business_id", UUID.class),
                resultSet.getObject("staff_member_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private WorkingPeriod workingPeriod(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new WorkingPeriod(
                DayOfWeek.of(resultSet.getInt("weekday")),
                resultSet.getObject("start_time", LocalTime.class),
                resultSet.getObject("end_time", LocalTime.class));
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
