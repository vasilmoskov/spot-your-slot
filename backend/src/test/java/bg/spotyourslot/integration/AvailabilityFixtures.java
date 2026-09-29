package bg.spotyourslot.integration;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Direct-SQL fixtures for PostgreSQL-backed availability tests on a disposable database. */
public final class AvailabilityFixtures {
    private static final Instant CREATED = Instant.parse("2026-09-29T08:00:00Z");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final JdbcClient jdbc;

    public AvailabilityFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID business(String status, String timezone) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO business(
                            id,slug,display_name,business_type,status,timezone,
                            created_at,updated_at)
                        VALUES (:id,:slug,'Availability Test','OTHER',:status,:timezone,:now,:now)
                        """)
                .param("id", id)
                .param("slug", "availability-" + SEQUENCE.incrementAndGet() + "-" + id)
                .param("status", status)
                .param("timezone", timezone)
                .param("now", databaseNow())
                .update();
        return id;
    }

    public UUID service(UUID businessId, int durationMinutes, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (:id,:businessId,:name,NULL,:minutes,10.00,:active,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("name", "Service " + id)
                .param("minutes", durationMinutes)
                .param("active", active)
                .param("now", databaseNow())
                .update();
        return id;
    }

    /** Creates the StaffMember together with its empty schedule aggregate, as the application does. */
    public UUID staffMember(UUID businessId, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,contact_email,contact_phone,
                            active,version,created_at,updated_at)
                        VALUES (:id,:businessId,'Availability Staff',NULL,NULL,
                                :active,0,:now,:now)
                        """)
                .param("id", id)
                .param("businessId", businessId)
                .param("active", active)
                .param("now", databaseNow())
                .update();
        jdbc.sql("""
                        INSERT INTO staff_working_schedule(
                            business_id,staff_member_id,version,created_at,updated_at)
                        VALUES (:businessId,:id,0,:now,:now)
                        """)
                .param("businessId", businessId)
                .param("id", id)
                .param("now", databaseNow())
                .update();
        return id;
    }

    public void assign(UUID businessId, UUID staffMemberId, UUID serviceId) {
        jdbc.sql("""
                        INSERT INTO staff_member_service(business_id,staff_member_id,service_id)
                        VALUES (:businessId,:staffMemberId,:serviceId)
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .param("serviceId", serviceId)
                .update();
    }

    public void unassign(UUID businessId, UUID staffMemberId, UUID serviceId) {
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

    public void period(
            UUID businessId, UUID staffMemberId, DayOfWeek day, String start, String end) {
        jdbc.sql("""
                        INSERT INTO staff_working_period(
                            business_id,staff_member_id,weekday,start_time,end_time)
                        VALUES (:businessId,:staffMemberId,:weekday,:start,:end)
                        """)
                .param("businessId", businessId)
                .param("staffMemberId", staffMemberId)
                .param("weekday", day.getValue())
                .param("start", LocalTime.parse(start))
                .param("end", LocalTime.parse(end))
                .update();
    }

    /** The same local period on every weekday. */
    public void everyDay(UUID businessId, UUID staffMemberId, String start, String end) {
        for (DayOfWeek day : DayOfWeek.values()) {
            period(businessId, staffMemberId, day, start, end);
        }
    }

    public void setServiceActive(UUID serviceId, boolean active) {
        jdbc.sql("UPDATE service SET active = :active WHERE id = :id")
                .param("active", active)
                .param("id", serviceId)
                .update();
    }

    public void setStaffMemberActive(UUID staffMemberId, boolean active) {
        jdbc.sql("UPDATE staff_member SET active = :active WHERE id = :id")
                .param("active", active)
                .param("id", staffMemberId)
                .update();
    }

    public void setBusinessStatus(UUID businessId, String status) {
        jdbc.sql("UPDATE business SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", businessId)
                .update();
    }

    public static OffsetDateTime databaseNow() {
        return CREATED.atOffset(ZoneOffset.UTC);
    }
}
