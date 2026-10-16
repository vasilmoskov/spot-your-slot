package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentCreationOptions;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationAvailability;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationOptions;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
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
 * The transaction behavior of the manual-create reads on real PostgreSQL, called through the
 * published contract so exactly the service's statements are counted: one read-only REPEATABLE READ
 * transaction; a statement count that depends neither on the number of Services, StaffMembers,
 * Appointments, nor slots; and snapshot consistency proved by committing a change from another
 * thread at a deterministic point between two statements (joined before the read continues, so no
 * sleep and no timing assumption is involved).
 */
class AppointmentCreationOptionsTransactionIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired AppointmentCreationOptions creation;

    private Actor owner;
    private AuthenticatedBusinessContext context;
    private Tenant tenant;

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
    }

    private <T> List<String> run(Supplier<T> call) {
        CalendarSqlRecording.clear();
        call.get();
        return CalendarSqlRecording.statementsOfCurrentThread();
    }

    private List<String> optionsStatements(List<UUID> services) {
        return run(() -> creation.options(context, services));
    }

    private List<String> availabilityStatements(List<UUID> services, UUID staff) {
        return run(() -> creation.availability(context, services, staff, DAY));
    }

    /** Grows the Business: more Services, StaffMembers (all assigned to every Service), and busy time. */
    private List<UUID> grow(int services, int staffMembers, int appointmentsPerStaff) {
        List<UUID> ids = new ArrayList<>(List.of(tenant.service()));
        for (int index = 1; index < services; index++) {
            UUID service = availability.service(owner.businessId(), 20, true);
            availability.assign(owner.businessId(), tenant.staff(), service);
            ids.add(service);
        }
        for (int member = 0; member < staffMembers; member++) {
            UUID staff = staff(owner.businessId(), "Служител " + member, true);
            availability.everyDay(owner.businessId(), staff, "09:00", "18:00");
            for (UUID service : ids) {
                availability.assign(owner.businessId(), staff, service);
            }
            for (int index = 0; index < appointmentsPerStaff; index++) {
                appointment(withCustomer(withStaff(tenant, staff), customer(owner.businessId(), "Клиент " + member + "-" + index)),
                        Instant.parse("2026-11-10T07:00:00Z").plusSeconds(index * 45L * 60L), 30);
            }
        }
        return ids;
    }

    // ---- statement counts -------------------------------------------------------------------------

    @Test
    void theOptionsUseFourStatementsWhateverTheSizeOfTheBusinessAndTheSelection() {
        List<String> one = optionsStatements(List.of(tenant.service()));
        assertThat(one).hasSize(4);
        assertThat(CalendarSqlRecording.normalized(one.get(0))).contains("from business");
        assertThat(CalendarSqlRecording.normalized(one.get(1))).contains("from membership");
        assertThat(CalendarSqlRecording.normalized(one.get(2))).contains("from service");
        assertThat(CalendarSqlRecording.normalized(one.get(3))).contains("staff_member");

        List<UUID> five = grow(5, 12, 6);
        assertThat(optionsStatements(five)).hasSize(4);
    }

    @Test
    void theAvailabilityStatementCountIsTheSameForOneServiceAndFiveForOneStaffMemberAndManyAndForNoAppointmentsAndManyOnes() {
        List<String> small = availabilityStatements(List.of(tenant.service()), tenant.staff());
        // The service's four reads, then the engine's four: its Business context, the StaffMember's
        // weekly periods, the schedule exceptions, and the busy Appointments.
        assertThat(small).hasSize(8);
        List<String> tables = new ArrayList<>();
        for (String sql : small) {
            String text = CalendarSqlRecording.normalized(sql);
            for (String table : List.of("business", "membership", "service", "staff_member",
                    "schedule_exception", "appointment")) {
                if (text.matches(".*\\bfrom " + table + "\\b.*")) {
                    tables.add(table);
                    break;
                }
            }
        }
        assertThat(tables).containsExactly("business", "membership", "service", "staff_member", "business",
                "staff_member", "schedule_exception", "appointment");

        List<UUID> five = grow(5, 12, 6);
        List<String> large = availabilityStatements(five, tenant.staff());

        assertThat(large).hasSameSizeAs(small);
        // And an explicit StaffMember with a busy day, a closed day, and a past day cost the same.
        assertThat(availabilityStatements(five, tenant.staff())).hasSameSizeAs(small);
        assertThat(run(() -> creation.availability(context, five, tenant.staff(), LocalDate.of(2026, 9, 30))))
                .hasSameSizeAs(small);
    }

    @Test
    void everyOperationRunsInOneReadOnlyRepeatableReadTransaction() {
        List<int[]> seen = new ArrayList<>();
        Predicate<String> any = sql -> true;
        for (Runnable call : List.<Runnable>of(
                () -> creation.options(context, List.of(tenant.service())),
                () -> creation.availability(context, List.of(tenant.service()), tenant.staff(), DAY))) {
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
        long before = jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
        List<String> all = new ArrayList<>(optionsStatements(List.of(tenant.service())));
        all.addAll(availabilityStatements(List.of(tenant.service()), tenant.staff()));

        assertThat(all).noneMatch(sql -> {
            String text = CalendarSqlRecording.normalized(sql);
            return text.startsWith("insert") || text.startsWith("update") || text.startsWith("delete")
                    || text.contains("for share") || text.contains("for update");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single()).isEqualTo(before);
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
        // After the Service durations were read and before the StaffMember intersection, another
        // transaction commits a new eligible StaffMember and a longer Service duration.
        CalendarSqlRecording.beforeNextStatement(
                sql -> CalendarSqlRecording.normalized(sql).contains("staff_member"),
                () -> commitConcurrently(() -> {
                    availability.assign(owner.businessId(), late, tenant.service());
                    jdbc.sql("UPDATE service SET duration_minutes = 45 WHERE id = :id")
                            .param("id", tenant.service()).update();
                }));

        CreationOptions snapshot = creation.options(context, List.of(tenant.service()));

        assertThat(snapshot.durationMinutes()).isEqualTo(30);
        assertThat(snapshot.staffMembers()).hasSize(1);
        CreationOptions next = creation.options(context, List.of(tenant.service()));
        assertThat(next.durationMinutes()).isEqualTo(45);
        assertThat(next.staffMembers()).hasSize(2);
    }

    @Test
    void theOfferedStartsComeFromTheSnapshotEvenWhenAnAppointmentCommitsMidRead() {
        CalendarSqlRecording.clear();
        CalendarSqlRecording.beforeNextStatement(
                sql -> CalendarSqlRecording.normalized(sql).contains("from appointment"),
                () -> commitConcurrently(
                        () -> appointment(tenant, Instant.parse("2026-11-10T07:00:00Z"), 60)));

        CreationAvailability snapshot = creation.availability(context, List.of(tenant.service()), tenant.staff(), DAY);

        assertThat(snapshot.starts().stream().map(start -> start.start().toString()))
                .contains("2026-11-10T07:00:00Z");
        CreationAvailability next = creation.availability(context, List.of(tenant.service()), tenant.staff(), DAY);
        assertThat(next.starts().stream().map(start -> start.start().toString()))
                .doesNotContain("2026-11-10T07:00:00Z", "2026-11-10T07:15:00Z");
    }
}
