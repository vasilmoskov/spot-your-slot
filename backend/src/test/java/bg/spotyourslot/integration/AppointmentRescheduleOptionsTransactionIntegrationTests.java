package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.AppointmentRescheduleOptions;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleAvailability;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleOptions;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.BusinessCustomerApiTestSupport.Actor;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The transaction behavior of the reschedule reads on real PostgreSQL, called through the published
 * contract so exactly the service's statements are counted: one read-only REPEATABLE READ transaction;
 * a statement count that depends neither on the number of Service lines, StaffMembers, Appointments,
 * nor slots; no write and no row lock; and snapshot consistency proved by committing a change from
 * another thread at a deterministic point between two statements (joined before the read continues, so
 * no sleep and no timing assumption is involved).
 */
class AppointmentRescheduleOptionsTransactionIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired AppointmentRescheduleOptions reads;

    private Actor owner;
    private AuthenticatedBusinessContext context;
    private Tenant tenant;
    private UUID appointment;

    @BeforeEach
    void seed() {
        owner = api.owner("ACTIVE");
        UUID user = owner.userId();
        UUID business = owner.businessId();
        context = new AuthenticatedBusinessContext() {
            @Override
            public UUID userId() {
                return user;
            }

            @Override
            public Optional<UUID> selectedBusinessId() {
                return Optional.of(business);
            }
        };
        tenant = tenant(business, "Мария Петрова", "Анна Иванова");
        availability.everyDay(business, tenant.staff(), "09:00", "18:00");
        appointment = appointment(tenant, Instant.parse("2026-11-10T07:00:00Z"), 30);
    }

    private <T> List<String> run(Supplier<T> call) {
        CalendarSqlRecording.clear();
        call.get();
        return CalendarSqlRecording.statementsOfCurrentThread();
    }

    private static String table(String sql) {
        String text = CalendarSqlRecording.normalized(sql);
        for (String table : List.of("appointment_service", "appointment", "business", "membership", "staff_member",
                "staff_working_period", "schedule_exception")) {
            if (text.matches(".*\\bfrom " + table + "\\b.*")) {
                return table;
            }
        }
        return "?";
    }

    /** Grows the Business: Service lines on the visit, StaffMembers (all assigned), and busy time. */
    private UUID grow(int lines, int staffMembers, int appointmentsPerStaff) {
        UUID visit = visit(tenant, Instant.parse("2026-11-10T07:00:00Z").plusSeconds(86_400),
                java.util.stream.IntStream.range(0, lines).mapToObj(index -> "Услуга " + index).toArray(String[]::new));
        List<UUID> services = jdbc.sql("SELECT service_id FROM appointment_service WHERE appointment_id = :id")
                .param("id", visit).query(UUID.class).list();
        for (UUID service : services) {
            if (!service.equals(tenant.service())) {
                availability.assign(owner.businessId(), tenant.staff(), service);
            }
        }
        for (int member = 0; member < staffMembers; member++) {
            UUID staff = staff(owner.businessId(), "Служител " + member, true);
            availability.everyDay(owner.businessId(), staff, "09:00", "18:00");
            for (UUID service : services) {
                availability.assign(owner.businessId(), staff, service);
            }
            for (int index = 0; index < appointmentsPerStaff; index++) {
                appointment(withCustomer(withStaff(tenant, staff), customer(owner.businessId(), "Клиент " + member + "-" + index)),
                        Instant.parse("2026-11-10T07:00:00Z").plusSeconds(index * 45L * 60L), 30);
            }
        }
        return visit;
    }

    // ---- statement counts -------------------------------------------------------------------------

    @Test
    void theOptionsUseSixStatementsWhateverTheSizeOfTheBusinessAndTheVisit() {
        List<String> one = run(() -> reads.options(context, appointment));
        assertThat(one).hasSize(6);
        assertThat(one.stream().map(AppointmentRescheduleOptionsTransactionIntegrationTests::table).toList())
                .containsExactly("business", "membership", "appointment", "appointment_service", "staff_member",
                        "staff_member");

        UUID large = grow(5, 12, 6);
        assertThat(run(() -> reads.options(context, large))).hasSize(6);
    }

    @Test
    void theAvailabilityStatementCountIsConstantForTheCurrentStaffMemberAndForAReplacement() {
        UUID replacement = staff(owner.businessId(), "Анна Бойчева", true);
        availability.assign(owner.businessId(), replacement, tenant.service());
        availability.everyDay(owner.businessId(), replacement, "09:00", "18:00");

        List<String> keep = run(() -> reads.availability(context, appointment, tenant.staff(), DAY));
        List<String> replace = run(() -> reads.availability(context, appointment, replacement, DAY));

        // The service's reads (context, owner, Appointment, lines, and for a replacement the
        // intersection), then the engine's four: its Business context, the StaffMember's weekly
        // periods, the schedule exceptions, and the busy Appointments.
        assertThat(keep).hasSize(8);
        assertThat(keep.stream().map(AppointmentRescheduleOptionsTransactionIntegrationTests::table).toList())
                .containsExactly("business", "membership", "appointment", "appointment_service", "business",
                        "staff_member", "schedule_exception", "appointment");
        assertThat(replace).hasSize(9);

        UUID large = grow(5, 12, 6);
        for (UUID service : jdbc.sql("SELECT service_id FROM appointment_service WHERE appointment_id = :id")
                .param("id", large).query(UUID.class).list()) {
            if (!service.equals(tenant.service())) {
                availability.assign(owner.businessId(), replacement, service);
            }
        }
        assertThat(run(() -> reads.availability(context, large, tenant.staff(), DAY))).hasSameSizeAs(keep);
        assertThat(run(() -> reads.availability(context, large, replacement, DAY))).hasSameSizeAs(replace);
        // A closed day and a past day cost the same.
        assertThat(run(() -> reads.availability(context, large, tenant.staff(), LocalDate.of(2026, 9, 30))))
                .hasSameSizeAs(keep);
    }

    @Test
    void everyOperationRunsInOneReadOnlyRepeatableReadTransaction() {
        List<int[]> seen = new ArrayList<>();
        Predicate<String> any = sql -> true;
        for (Runnable call : List.<Runnable>of(
                () -> reads.options(context, appointment),
                () -> reads.availability(context, appointment, tenant.staff(), DAY))) {
            CalendarSqlRecording.clear();
            CalendarSqlRecording.beforeNextStatement(any, () -> seen.add(new int[] {
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? 1 : 0,
                    TransactionSynchronizationManager.isActualTransactionActive() ? 1 : 0}));
            call.run();
        }

        assertThat(seen).hasSize(2);
        for (int[] state : seen) {
            assertThat(state[0]).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(state[1]).isEqualTo(1);
            assertThat(state[2]).isEqualTo(1);
        }
    }

    @Test
    void theReadsWriteNothingAndTakeNoRowLock() {
        String before = jdbc.sql("SELECT string_agg(id::text || ':' || version || ':' || start_at, ',' ORDER BY id) "
                + "FROM appointment").query(String.class).single();
        List<String> all = new ArrayList<>(run(() -> reads.options(context, appointment)));
        all.addAll(run(() -> reads.availability(context, appointment, tenant.staff(), DAY)));

        assertThat(all).noneMatch(sql -> {
            String text = CalendarSqlRecording.normalized(sql);
            return text.startsWith("insert") || text.startsWith("update") || text.startsWith("delete")
                    || text.contains("for share") || text.contains("for update");
        });
        assertThat(jdbc.sql("SELECT string_agg(id::text || ':' || version || ':' || start_at, ',' ORDER BY id) "
                + "FROM appointment").query(String.class).single()).isEqualTo(before);
    }

    // ---- snapshot consistency ---------------------------------------------------------------------

    private static void commitConcurrently(Runnable change) {
        try {
            CompletableFuture.runAsync(change).get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("the concurrent commit failed", failure);
        }
    }

    @Test
    void theOptionsComeFromTheSnapshotOfTheirFirstStatement() {
        UUID late = staff(owner.businessId(), "Късно добавен", true);
        jdbc.sql("DELETE FROM staff_member_service WHERE staff_member_id = :id").param("id", late).update();
        CalendarSqlRecording.clear();
        // After the Appointment and its lines were read and before the replacement intersection,
        // another transaction commits a new eligible StaffMember and deactivates the current one.
        CalendarSqlRecording.beforeNextStatement(
                sql -> "staff_member".equals(table(sql)),
                () -> commitConcurrently(() -> {
                    availability.assign(owner.businessId(), late, tenant.service());
                    availability.setStaffMemberActive(tenant.staff(), false);
                }));

        RescheduleOptions snapshot = reads.options(context, appointment);

        assertThat(snapshot.replacements()).isEmpty();
        assertThat(snapshot.currentStaffMember().available()).isTrue();
        RescheduleOptions next = reads.options(context, appointment);
        assertThat(next.replacements()).hasSize(1);
        assertThat(next.currentStaffMember().available()).isFalse();
    }

    @Test
    void theOfferedStartsComeFromTheSnapshotEvenWhenAnotherAppointmentCommitsMidRead() {
        UUID visit = grow(1, 0, 0);
        CalendarSqlRecording.clear();
        // The engine's busy read is the last statement; a different Appointment commits just before it.
        CalendarSqlRecording.beforeNextStatement(
                sql -> "appointment".equals(table(sql)) && CalendarSqlRecording.normalized(sql).contains("occupied_until"),
                () -> commitConcurrently(
                        () -> appointment(tenant, Instant.parse("2026-11-10T08:00:00Z"), 60)));

        RescheduleAvailability snapshot = reads.availability(context, appointment, tenant.staff(), DAY);

        assertThat(snapshot.starts().stream().map(start -> start.start().toString()))
                .contains("2026-11-10T08:00:00Z", "2026-11-10T08:30:00Z");
        RescheduleAvailability next = reads.availability(context, appointment, tenant.staff(), DAY);
        assertThat(next.starts().stream().map(start -> start.start().toString()))
                .doesNotContain("2026-11-10T08:00:00Z", "2026-11-10T08:30:00Z");
        assertThat(visit).isNotNull();
    }

    @Test
    void theStoredAppointmentIsTheSnapshotsWhenItIsCancelledMidRead() {
        CalendarSqlRecording.clear();
        // After the Appointment was read as movable, another transaction cancels it; the read still
        // answers from its snapshot (advisory), and a new read reports the state conflict.
        CalendarSqlRecording.beforeNextStatement(
                sql -> "staff_member".equals(table(sql)),
                () -> commitConcurrently(() -> fixtures.cancel(appointment)));

        RescheduleOptions snapshot = reads.options(context, appointment);

        assertThat(snapshot.durationMinutes()).isEqualTo(30);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reads.options(context, appointment))
                .isInstanceOf(bg.spotyourslot.booking.AppointmentRescheduleOptionsException.StateConflict.class);
    }

    @Test
    void theReadsNeverWaitForARowLockAWriterHolds() throws Exception {
        var holding = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var writer = CompletableFuture.runAsync(() -> new org.springframework.transaction.support.TransactionTemplate(
                transactionManager).executeWithoutResult(status -> {
            // The locks of a reschedule or reassignment, and of a schedule change: all held exclusively.
            jdbc.sql("SELECT 1 FROM appointment WHERE business_id = :b FOR UPDATE").param("b", owner.businessId()).query().listOfRows();
            jdbc.sql("SELECT 1 FROM staff_member WHERE business_id = :b FOR UPDATE").param("b", owner.businessId()).query().listOfRows();
            jdbc.sql("SELECT 1 FROM business WHERE id = :b FOR UPDATE").param("b", owner.businessId()).query().listOfRows();
            holding.countDown();
            try {
                if (!release.await(60, TimeUnit.SECONDS)) {
                    throw new AssertionError("the writer was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }));
        try {
            assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();
            // The exclusive locks are really held: a locking read of the same row would have to wait.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc
                    .sql("SELECT 1 FROM appointment WHERE id = :id FOR UPDATE NOWAIT").param("id", appointment)
                    .query().listOfRows()).hasMessageContaining("could not obtain lock");

            CompletableFuture<Object> read = CompletableFuture.supplyAsync(() -> {
                reads.options(context, appointment);
                return reads.availability(context, appointment, tenant.staff(), DAY);
            });
            // Both reads complete while the writer still holds every lock: they take and wait for none.
            assertThat(read.get(20, TimeUnit.SECONDS)).isNotNull();
            assertThat(writer.isDone()).isFalse();
            assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single()).isZero();
        } finally {
            release.countDown();
            writer.get(20, TimeUnit.SECONDS);
        }
    }
}
