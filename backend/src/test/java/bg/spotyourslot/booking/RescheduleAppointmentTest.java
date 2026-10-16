package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared base of the reschedule and reassignment integration tests: the owner-created Appointment
 * fixtures of {@link ManualAppointmentTest} (real PostgreSQL, the real administration, a controllable
 * clock, Tuesday 2026-09-29 11:00 in Sofia) plus a second assigned StaffMember working 09:00 to 12:00
 * and helpers for the {@code reschedule} command, named attempt threads, and byte-level row
 * comparison. Appointments under test are created through the real owner creation, so they carry
 * real lines, snapshots, and version 0.
 */
public abstract class RescheduleAppointmentTest extends ManualAppointmentTest {
    protected UUID second;

    @BeforeEach
    void setUpRescheduleFixtures() {
        second = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), second, "09:00", "12:00");
        jdbc.sql("UPDATE staff_member SET display_name = 'Втори Служител' WHERE id = :id")
                .param("id", second).update();
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** An owner-created Appointment of the Tenant's StaffMember at the given Thursday time. */
    protected CreatedAppointment existing(String time) {
        return create(cmd().start(time));
    }

    /** An owner-created Appointment of the given StaffMember at the given Thursday time. */
    protected CreatedAppointment existingFor(UUID staff, String time) {
        return create(cmd().staff(staff).start(time));
    }

    // ---- commands ------------------------------------------------------------------------------

    protected final class Move {
        UUID id;
        long version;
        Instant start;
        UUID staff;

        public Move version(long value) {
            version = value;
            return this;
        }

        public Move start(String time) {
            start = at(time);
            return this;
        }

        public Move start(Instant value) {
            start = value;
            return this;
        }

        public Move staff(UUID value) {
            staff = value;
            return this;
        }

        public Move id(UUID value) {
            id = value;
            return this;
        }

        public RescheduleAppointmentCommand build() {
            return new RescheduleAppointmentCommand(id, version, start, staff);
        }
    }

    /** A move of the Appointment at its current version that names nothing yet. */
    protected Move move(CreatedAppointment appointment) {
        Move move = new Move();
        move.id = appointment.id();
        move.version = appointment.version();
        return move;
    }

    protected Move move(UUID id, long version) {
        Move move = new Move();
        move.id = id;
        move.version = version;
        return move;
    }

    // ---- execution -----------------------------------------------------------------------------

    protected CompletableFuture<RescheduledAppointment> submitMove(String label, Move move) {
        return submitMove(label, ownerContext, move.build());
    }

    protected CompletableFuture<RescheduledAppointment> submitMove(
            String label, AuthenticatedBusinessContext context, RescheduleAppointmentCommand command) {
        return onBookingThread(label, () -> administration.reschedule(context, command));
    }

    protected RescheduledAppointment reschedule(Move move) {
        return completed(submitMove("main", move));
    }

    protected Throwable rejectionOf(Move move) {
        return failureOf(submitMove("main", move));
    }

    // ---- observation ---------------------------------------------------------------------------

    /** Every column of the row plus its tuple version, for byte-level before/after comparison. */
    protected Map<String, Object> fullRow(UUID id) {
        Map<String, Object> row = new LinkedHashMap<>(jdbc.sql(
                        "SELECT a.*, a.xmin::text AS xmin_text FROM appointment a WHERE a.id = :id")
                .param("id", id).query().singleRow());
        return row;
    }

    /** The names of the columns whose value differs between two snapshots of one row. */
    protected static Set<String> changedColumns(Map<String, Object> before, Map<String, Object> after) {
        Set<String> changed = new TreeSet<>();
        for (String column : before.keySet()) {
            if (!Objects.equals(before.get(column), after.get(column))) {
                changed.add(column);
            }
        }
        return changed;
    }

    /** The columns a successful reschedule or reassignment may change, and nothing else. */
    protected static final Set<String> MOVE_COLUMNS = Set.of(
            "start_at", "end_at", "occupied_until", "version", "updated_at", "xmin_text");
    protected static final Set<String> REASSIGN_COLUMNS = Set.of(
            "staff_member_id", "staff_display_name", "version", "updated_at", "xmin_text");

    protected Set<String> columns(Set<String> first, String... more) {
        Set<String> all = new TreeSet<>(first);
        all.addAll(Set.of(more));
        return all;
    }

    protected long clockReads() {
        return clock.reads();
    }

    /** A user with a Membership of the given role in the Business (active or not). */
    protected AuthenticatedBusinessContext memberOf(UUID business, String role, boolean active) {
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,
                            password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Member','hash',:now,:now,:now)
                        """)
                .param("id", user)
                .param("email", user + "@example.invalid")
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:businessId,:userId,:role,:active,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", business)
                .param("userId", user)
                .param("role", role)
                .param("active", active)
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        return new OwnerContext(user, business);
    }

    protected static Instant instant(String isoWithOffset) {
        return java.time.OffsetDateTime.parse(isoWithOffset).toInstant();
    }

    protected static Instant instantOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((java.time.OffsetDateTime) value).toInstant();
    }
}
