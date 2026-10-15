package bg.spotyourslot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentCalendar;
import bg.spotyourslot.booking.AppointmentCalendarRecords.AgendaPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.AppointmentDetail;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarView;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CustomerHistoryPage;
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
 * The transaction behavior of the calendar reads on real PostgreSQL, called through the published
 * contract so exactly the service's statements are counted: one read-only REPEATABLE READ
 * transaction; the maximum statement count of each operation, independent of the number of
 * Appointments, Services, Customers, and StaffMembers; and snapshot consistency proved by
 * committing a change from another transaction at a deterministic point between two statements of
 * the same read. The commit runs on another thread and is joined before the read continues, so no
 * sleep and no timing assumption is involved.
 */
class BusinessCalendarTransactionIntegrationTests extends BusinessCalendarApiIntegrationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired AppointmentCalendar calendar;

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

    private static List<String> tables(List<String> statements) {
        List<String> tables = new ArrayList<>();
        for (String sql : statements) {
            String text = CalendarSqlRecording.normalized(sql);
            for (String table : List.of("appointment_service", "appointment", "schedule_exception",
                    "staff_member", "membership", "customer", "business")) {
                if (text.matches(".*\\bfrom " + table + "\\b.*")) {
                    tables.add(table);
                    break;
                }
            }
        }
        return tables;
    }

    private <T> List<String> run(Supplier<T> call) {
        CalendarSqlRecording.clear();
        call.get();
        return CalendarSqlRecording.statementsOfCurrentThread();
    }

    private void fill(int staffMembers, int appointmentsPerStaff, int lines) {
        for (int s = 0; s < staffMembers; s++) {
            UUID staff = staff(owner.businessId(), "Служител " + s, true);
            for (int a = 0; a < appointmentsPerStaff; a++) {
                Tenant data = withCustomer(withStaff(tenant, staff), customer(owner.businessId(), "Клиент " + s + "-" + a));
                String[] names = new String[lines];
                for (int line = 0; line < lines; line++) {
                    names[line] = "Услуга " + line;
                }
                visit(data, Instant.parse("2026-11-10T00:00:00Z").plusSeconds(a * 3L * 3600L), names);
            }
        }
    }

    // ---- statement counts -------------------------------------------------------------------------

    @Test
    void theCalendarUsesAtMostSevenStatementsInTheDocumentedOrderAndFiveWhenEmpty() {
        List<String> empty = run(() -> calendar.calendar(context, DAY, DAY, null));
        assertThat(empty).hasSize(5);
        assertThat(tables(empty)).containsExactly(
                "business", "membership", "appointment", "staff_member", "schedule_exception");

        appointment(tenant, NOV_10, 30);
        List<String> full = run(() -> calendar.calendar(context, DAY, DAY, null));
        assertThat(full).hasSize(7);
        assertThat(tables(full)).containsExactly(
                "business", "membership", "appointment", "staff_member", "schedule_exception",
                "appointment_service", "customer");

        List<String> filtered = run(() -> calendar.calendar(context, DAY, DAY, tenant.staff()));
        assertThat(filtered).hasSize(7);
        assertThat(tables(filtered)).containsExactly(
                "business", "membership", "staff_member", "schedule_exception", "appointment",
                "appointment_service", "customer");
    }

    @Test
    void theAgendaUsesAtMostSixOrSevenStatementsAndThreeOrFourWhenEmptyOrPastTheEnd() {
        assertThat(run(() -> calendar.agenda(context, DAY, DAY, null, null, null))).hasSize(3);
        assertThat(run(() -> calendar.agenda(context, DAY, DAY, tenant.staff(), null, null))).hasSize(4);

        appointment(tenant, NOV_10, 30);
        List<String> full = run(() -> calendar.agenda(context, DAY, DAY, null, null, null));
        assertThat(full).hasSize(6);
        assertThat(tables(full)).containsExactly(
                "business", "membership", "appointment", "appointment", "appointment_service", "customer");
        assertThat(run(() -> calendar.agenda(context, DAY, DAY, tenant.staff(), null, null))).hasSize(7);
        assertThat(run(() -> calendar.agenda(context, DAY, DAY, null, 5, 10))).hasSize(3);
    }

    @Test
    void theDetailUsesAtMostFiveStatementsAndThreeWhenMissing() {
        UUID id = appointment(tenant, NOV_10, 30);

        List<String> found = run(() -> calendar.detail(context, id));
        assertThat(found).hasSize(5);
        assertThat(tables(found)).containsExactly(
                "business", "membership", "appointment", "appointment_service", "customer");
        List<String> missing = new ArrayList<>();
        CalendarSqlRecording.clear();
        try {
            calendar.detail(context, UUID.randomUUID());
        } catch (RuntimeException expected) {
            missing.addAll(CalendarSqlRecording.statementsOfCurrentThread());
        }
        assertThat(missing).hasSize(3);
    }

    @Test
    void theCustomerHistoryUsesAtMostSixStatementsAndFourWhenEmptyOrPastTheEnd() {
        UUID none = customer(owner.businessId(), "Без часове");
        assertThat(run(() -> calendar.customerHistory(context, none, null, null))).hasSize(4);

        appointment(tenant, NOV_10, 30);
        List<String> full = run(() -> calendar.customerHistory(context, tenant.customer(), null, null));
        assertThat(full).hasSize(6);
        assertThat(tables(full)).containsExactly(
                "business", "membership", "customer", "appointment", "appointment", "appointment_service");
        assertThat(run(() -> calendar.customerHistory(context, tenant.customer(), 9, 10))).hasSize(4);
    }

    @Test
    void theCountsDoNotDependOnTheNumberOfAppointmentsServicesCustomersOrStaffMembers() {
        UUID first = appointment(tenant, NOV_10.minusSeconds(3600), 30);
        int calendarOne = run(() -> calendar.calendar(context, DAY, DAY, null)).size();
        int agendaOne = run(() -> calendar.agenda(context, DAY, DAY, null, null, 50)).size();
        int detailOne = run(() -> calendar.detail(context, first)).size();
        int historyOne = run(() -> calendar.customerHistory(context, tenant.customer(), null, 50)).size();

        fill(6, 6, 5);   // 36 five-line visits, 36 Customers, 7 StaffMembers

        assertThat(run(() -> calendar.calendar(context, DAY, DAY, null))).hasSize(calendarOne).hasSize(7);
        assertThat(run(() -> calendar.agenda(context, DAY, DAY, null, null, 50))).hasSize(agendaOne).hasSize(6);
        assertThat(run(() -> calendar.detail(context, first))).hasSize(detailOne).hasSize(5);
        assertThat(run(() -> calendar.customerHistory(context, tenant.customer(), null, 50)))
                .hasSize(historyOne).hasSize(6);
        CalendarView view = calendar.calendar(context, DAY, DAY, null);
        assertThat(view.appointments()).hasSizeGreaterThan(30);
        assertThat(view.staffMembers()).hasSize(7);
    }

    @Test
    void everyOperationRunsInOneReadOnlyRepeatableReadTransaction() {
        UUID id = appointment(tenant, NOV_10, 30);
        List<int[]> seen = new ArrayList<>();
        Predicate<String> any = sql -> true;
        for (Runnable call : List.<Runnable>of(
                () -> calendar.calendar(context, DAY, DAY, null),
                () -> calendar.agenda(context, DAY, DAY, null, null, null),
                () -> calendar.detail(context, id),
                () -> calendar.customerHistory(context, tenant.customer(), null, null))) {
            CalendarSqlRecording.clear();
            CalendarSqlRecording.beforeNextStatement(any, () -> seen.add(new int[] {
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? 1 : 0,
                    TransactionSynchronizationManager.isActualTransactionActive() ? 1 : 0}));
            call.run();
        }

        assertThat(seen).hasSize(4);
        for (int[] state : seen) {
            assertThat(state[0]).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(state[1]).isEqualTo(1);
            assertThat(state[2]).isEqualTo(1);
        }
    }

    // ---- snapshot consistency ---------------------------------------------------------------------

    /** Runs {@code change} on another thread and waits for it, i.e. commits it before the read goes on. */
    private static void commitConcurrently(Runnable change) {
        try {
            CompletableFuture.runAsync(change).get(20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("the concurrent commit failed", failure);
        }
    }

    private static Predicate<String> reads(String table, String... fragments) {
        return sql -> {
            String text = CalendarSqlRecording.normalized(sql);
            if (!text.matches(".*\\bfrom " + table + "\\b.*")) {
                return false;
            }
            for (String fragment : fragments) {
                if (!text.contains(fragment)) {
                    return false;
                }
            }
            return true;
        };
    }

    @Test
    void theAgendaCountAndRowsComeFromOneSnapshotEvenWhenACommitLandsBetweenThem() {
        List<UUID> before = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            before.add(appointment(withStaff(tenant, staff(owner.businessId(), "С" + index, true)), NOV_10, 30));
        }
        CalendarSqlRecording.clear();
        // After the count and before the page: three new Appointments and one cancellation commit.
        CalendarSqlRecording.beforeNextStatement(reads("appointment", "limit"), () -> commitConcurrently(() -> {
            for (int index = 0; index < 3; index++) {
                appointment(withStaff(tenant, staff(owner.businessId(), "Н" + index, true)), NOV_10, 30);
            }
            fixtures.cancel(before.get(0));
        }));

        AgendaPage page = calendar.agenda(context, DAY, DAY, null, 0, 10);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items()).extracting(item -> item.id()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.status().name()).isEqualTo("CONFIRMED"));
        // The next read sees the committed state: nothing was lost, only deferred to the next snapshot.
        AgendaPage later = calendar.agenda(context, DAY, DAY, null, 0, 10);
        assertThat(later.total()).isEqualTo(6);
        assertThat(later.items()).extracting(item -> item.status().name()).contains("CANCELLED");
    }

    @Test
    void theCalendarRowsNamesAndStaffComeFromOneSnapshot() {
        UUID id = appointment(tenant, NOV_10, 30);
        UUID late = staff(owner.businessId(), "Закъснял", true);
        CalendarSqlRecording.clear();
        // Between the Appointments and the Customer names: rename the Customer, add an Appointment.
        CalendarSqlRecording.beforeNextStatement(reads("customer", "id in"), () -> commitConcurrently(() -> {
            jdbc.sql("UPDATE customer SET display_name = 'Преименуван' WHERE id = :id")
                    .param("id", tenant.customer()).update();
            appointment(withStaff(tenant, late), NOV_10, 30);
        }));

        CalendarView view = calendar.calendar(context, DAY, DAY, null);

        assertThat(view.appointments()).extracting(row -> row.id()).containsExactly(id);
        assertThat(view.appointments().get(0).customerDisplayName()).isEqualTo("Анна Иванова");
        // The late StaffMember is read afterwards but has no Appointment in this snapshot, and is active.
        assertThat(view.staffMembers()).extracting(member -> member.id()).contains(late);
        assertThat(calendar.calendar(context, DAY, DAY, null).appointments()).hasSize(2);
        assertThat(calendar.calendar(context, DAY, DAY, null).appointments().get(0).customerDisplayName())
                .isEqualTo("Преименуван");
    }

    @Test
    void theStaffFactsAndTheWorkingTimeComeFromTheSnapshotOfTheFirstStatement() {
        appointment(tenant, NOV_10, 30);
        CalendarSqlRecording.clear();
        // Before the StaffMember read: rename and deactivate the StaffMember and add a time-off day.
        CalendarSqlRecording.beforeNextStatement(reads("staff_member"), () -> commitConcurrently(() -> {
            jdbc.sql("UPDATE staff_member SET display_name = 'Преименуван', active = false WHERE id = :id")
                    .param("id", tenant.staff()).update();
        }));

        CalendarView view = calendar.calendar(context, DAY, DAY, null);

        assertThat(view.staffMembers()).hasSize(1);
        assertThat(view.staffMembers().get(0).displayName()).isEqualTo("Мария Петрова");
        assertThat(view.staffMembers().get(0).active()).isTrue();
        assertThat(view.staffMembers().get(0).workingIntervals()).hasSize(1);
        assertThat(calendar.calendar(context, DAY, DAY, null).staffMembers().get(0).displayName())
                .isEqualTo("Преименуван");
    }

    @Test
    void theDetailLinesAndContactComeFromOneSnapshot() {
        UUID id = appointment(tenant, NOV_10, 30);
        CalendarSqlRecording.clear();
        CalendarSqlRecording.beforeNextStatement(reads("customer"), () -> commitConcurrently(() ->
                jdbc.sql("UPDATE customer SET display_name = 'Преименуван', phone = '+359888999000' WHERE id = :id")
                        .param("id", tenant.customer()).update()));

        AppointmentDetail detail = calendar.detail(context, id);

        assertThat(detail.customer().displayName()).isEqualTo("Анна Иванова");
        assertThat(detail.customer().phone()).isNotEqualTo("+359888999000");
        assertThat(calendar.detail(context, id).customer().phone()).isEqualTo("+359888999000");
    }

    @Test
    void theCustomerHistoryReferenceCountRowsAndLinesComeFromOneSnapshot() {
        UUID first = appointment(tenant, NOV_10, 30);
        CalendarSqlRecording.clear();
        // After the reference check and before the count, and again before the lines.
        CalendarSqlRecording.beforeNextStatement(reads("appointment", "count(*)"), () -> commitConcurrently(() -> {
            appointment(withStaff(tenant, staff(owner.businessId(), "Нов 1", true)), NOV_10.plusSeconds(86_400L), 30);
        }));

        CustomerHistoryPage page = calendar.customerHistory(context, tenant.customer(), 0, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(first);
        assertThat(calendar.customerHistory(context, tenant.customer(), 0, 10).total()).isEqualTo(2);

        CalendarSqlRecording.clear();
        CalendarSqlRecording.beforeNextStatement(reads("appointment_service"), () -> commitConcurrently(() ->
                appointment(withStaff(tenant, staff(owner.businessId(), "Нов 2", true)), NOV_10.plusSeconds(2 * 86_400L), 30)));
        CustomerHistoryPage second = calendar.customerHistory(context, tenant.customer(), 0, 10);

        assertThat(second.total()).isEqualTo(2);
        assertThat(second.items()).hasSize(2);
        assertThat(second.items()).allSatisfy(item -> assertThat(item.serviceNames()).hasSize(1));
        assertThat(calendar.customerHistory(context, tenant.customer(), 0, 10).total()).isEqualTo(3);
    }
}
