package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentServiceLine;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.BlockingWindow;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.OverlapConflict;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.UnexpectedFailure;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.UnknownReference;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Appointment store writes and reads the Service lines of a visit (ADR-0027, phase M1) against
 * real PostgreSQL: the unchanged single-Service path, a multi-Service visit in execution order, the
 * largest possible aggregate price, whole-visit overlap, atomic rollback, Business scoping, and a
 * stored line count that disagrees with the Appointment. All values are synthetic.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentServiceStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final String MAX_SERVICE_PRICE = "9999999999.99";

    @Autowired
    AppointmentStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
    }

    // ---- the single-Service path is unchanged --------------------------------

    @Test
    void aSingleServiceInsertWritesOneLineAndReadsItBackIdentically() {
        NewAppointment request = AppointmentFixtures.online(tenant, START, 45);

        Appointment stored = insertCommitted(request);

        assertThat(stored.serviceCount()).isEqualTo(1);
        assertThat(stored.serviceLines()).containsExactly(new AppointmentServiceLine(
                1, tenant.service(), AppointmentFixtures.SERVICE_NAME, 45, new BigDecimal("25.00")));
        assertThat(store.find(tenant.business(), stored.id())).contains(stored);
        assertThat(store.findByAttemptHash(tenant.business(), stored.attempt().attemptHash()))
                .contains(stored);
        assertThat(jdbc.sql("SELECT service_count FROM appointment WHERE id = :id")
                .param("id", stored.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(count("appointment_service")).isEqualTo(1L);
    }

    // ---- a multi-Service visit -----------------------------------------------

    @Test
    void aVisitOfThreeServicesIsStoredAndReadInExecutionOrderWithItsTotals() {
        List<AppointmentServiceLine> lines = lines(3, 20, "10.10");
        NewAppointment request = visit(START, lines, AppointmentSource.ONLINE, AppointmentFixtures.attempt());

        Appointment stored = insertCommitted(request);

        assertThat(stored.serviceCount()).isEqualTo(3);
        assertThat(stored.serviceLines()).containsExactlyElementsOf(lines);
        assertThat(stored.durationMinutes()).isEqualTo(60);
        assertThat(stored.priceEur()).isEqualTo(new BigDecimal("30.30"));
        assertThat(stored.serviceId()).isEqualTo(lines.get(0).serviceId());
        assertThat(stored.serviceName()).isEqualTo(lines.get(0).serviceName());
        assertThat(stored.endAt()).isEqualTo(START.plusSeconds(3600));
        assertThat(store.find(tenant.business(), stored.id())).contains(stored);
        assertThat(store.findByAttemptHash(tenant.business(), stored.attempt().attemptHash()))
                .contains(stored);
        assertThat(jdbc.sql("SELECT service_count FROM appointment WHERE id = :id")
                .param("id", stored.id()).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void theExecutionOrderIsThePositionNotTheOrderOfTheServiceIdentifiers() {
        // The same three Services in two visits, in opposite execution orders (two StaffMembers).
        List<AppointmentServiceLine> forward = lines(3, 30, "5.00");
        List<AppointmentServiceLine> reversed = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            AppointmentServiceLine source = forward.get(2 - index);
            reversed.add(new AppointmentServiceLine(
                    index + 1, source.serviceId(), source.serviceName(), 30, new BigDecimal("5.00")));
        }
        UUID otherStaff = fixtures.staffMember(tenant.business(), tenant.service());
        Tenant forOther = new Tenant(tenant.business(), tenant.service(), otherStaff, tenant.customer());

        Appointment first = insertCommitted(visit(START, forward, AppointmentSource.MANUAL, null));
        Appointment second = insertCommitted(visitFor(forOther, START, reversed, AppointmentSource.MANUAL, null));

        assertThat(store.find(tenant.business(), first.id()).orElseThrow().serviceLines())
                .containsExactlyElementsOf(forward);
        assertThat(store.find(tenant.business(), second.id()).orElseThrow().serviceLines())
                .containsExactlyElementsOf(reversed);
        assertThat(second.serviceId()).isEqualTo(forward.get(2).serviceId());
    }

    @Test
    void aVisitBlocksItsStaffMemberForTheWholeSummedDuration() {
        Appointment stored = insertCommitted(visit(START, lines(3, 30, "5.00"), AppointmentSource.MANUAL, null));

        List<BlockingWindow> windows = store.findBlockingWindows(
                tenant.business(), List.of(tenant.staff()), START.minusSeconds(3600), START.plusSeconds(7200));

        assertThat(windows).containsExactly(new BlockingWindow(
                tenant.staff(), START, START.plusSeconds(90 * 60L)));
        assertThat(stored.occupiedUntil()).isEqualTo(START.plusSeconds(90 * 60L));
    }

    // ---- the largest aggregate price -----------------------------------------

    @Test
    void fiveServicesOfTheLargestPriceAreStoredAndReadBackWithTheExactTotal() {
        List<AppointmentServiceLine> lines = lines(5, 30, MAX_SERVICE_PRICE);

        Appointment stored = insertCommitted(visit(START, lines, AppointmentSource.ONLINE, AppointmentFixtures.attempt()));

        assertThat(stored.priceEur()).isEqualTo(new BigDecimal("49999999999.95"));
        assertThat(stored.priceEur().scale()).isEqualTo(2);
        Appointment read = store.find(tenant.business(), stored.id()).orElseThrow();
        assertThat(read).isEqualTo(stored);
        assertThat(read.priceEur()).isEqualTo(new BigDecimal("49999999999.95"));
        assertThat(read.serviceLines()).allSatisfy(line ->
                assertThat(line.priceEur()).isEqualTo(new BigDecimal(MAX_SERVICE_PRICE)));
        assertThat(jdbc.sql("SELECT price_eur::text FROM appointment WHERE id = :id")
                .param("id", stored.id()).query(String.class).single()).isEqualTo("49999999999.95");
    }

    // ---- whole-visit overlap, adjacency, cancellation ------------------------

    @Test
    void anOverlappingVisitIsTheTypedConflictAndWritesNoLines() {
        insertCommitted(visit(START, lines(3, 30, "5.00"), AppointmentSource.MANUAL, null));
        long linesBefore = count("appointment_service");

        Throwable failure = catchThrowable(() -> insertCommitted(visit(
                START.plusSeconds(45 * 60L), lines(2, 30, "5.00"), AppointmentSource.MANUAL, null)));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(count("appointment")).isEqualTo(1L);
        assertThat(count("appointment_service")).isEqualTo(linesBefore);
    }

    @Test
    void adjacentVisitsAreAllowedAndACancelledVisitStopsBlocking() {
        Appointment first = insertCommitted(visit(START, lines(2, 30, "5.00"), AppointmentSource.MANUAL, null));
        // Adjacent after the first visit's end (half-open bounds).
        insertCommitted(visit(first.endAt(), lines(2, 30, "5.00"), AppointmentSource.MANUAL, null));
        assertThat(count("appointment")).isEqualTo(2L);

        jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE id = :id").param("id", first.id()).update();
        insertCommitted(visit(START.plusSeconds(15 * 60L), lines(1, 30, "5.00"), AppointmentSource.MANUAL, null));

        assertThat(count("appointment")).isEqualTo(3L);
    }

    // ---- atomicity -----------------------------------------------------------

    @Test
    void aRollbackAfterTheVisitWasWrittenLeavesNeitherTheAppointmentNorAnyLine() {
        NewAppointment request = visit(START, lines(3, 30, "5.00"), AppointmentSource.ONLINE,
                AppointmentFixtures.attempt());

        Throwable failure = catchThrowable(() -> transaction().execute(status -> {
            store.insert(request);
            throw new IllegalStateException("abort after the write");
        }));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(tenant.business(), request.id())).isEmpty();
        assertThat(count("appointment")).isZero();
        assertThat(count("appointment_service")).isZero();
    }

    @Test
    void aLineThatFailsAfterTheAppointmentRowWasWrittenRollsTheWholeVisitBack() {
        Tenant other = fixtures.tenant();
        // Line 2 names a Service of another Business: the Appointment row succeeds, the lines fail.
        List<AppointmentServiceLine> lines = new ArrayList<>(lines(1, 30, "5.00"));
        lines.add(new AppointmentServiceLine(2, other.service(), "Чужда услуга", 30, new BigDecimal("5.00")));
        NewAppointment request = visit(START, lines, AppointmentSource.MANUAL, null);

        Throwable failure = catchThrowable(() -> insertCommitted(request));

        assertThat(failure).isInstanceOf(UnknownReference.class);
        assertThat(failure.getMessage()).isEqualTo("Appointment reference does not exist in the Business");
        assertThat(failure.getCause()).isNull();
        assertThat(count("appointment")).isZero();
        assertThat(count("appointment_service")).isZero();
    }

    // ---- Business scoping ----------------------------------------------------

    @Test
    void aForeignBusinessNeverReadsAVisitOrItsLines() {
        Tenant other = fixtures.tenant();
        Appointment stored = insertCommitted(visit(START, lines(3, 30, "5.00"), AppointmentSource.ONLINE,
                AppointmentFixtures.attempt()));

        assertThat(store.find(other.business(), stored.id())).isEmpty();
        assertThat(store.findByAttemptHash(other.business(), stored.attempt().attemptHash())).isEmpty();
        assertThat(store.find(tenant.business(), stored.id())).isPresent();
    }

    // ---- inconsistent stored data --------------------------------------------

    @Test
    void aStoredLineCountThatDisagreesWithTheAppointmentIsAnUnexpectedFailureNeverATrustedRead() {
        Appointment stored = insertCommitted(visit(START, lines(2, 30, "5.00"), AppointmentSource.MANUAL, null));
        // Only with the user triggers disabled (the database refuses this otherwise).
        jdbc.sql("ALTER TABLE appointment_service DISABLE TRIGGER USER").update();
        try {
            jdbc.sql("DELETE FROM appointment_service WHERE appointment_id = :id AND position = 2")
                    .param("id", stored.id()).update();
        } finally {
            jdbc.sql("ALTER TABLE appointment_service ENABLE TRIGGER USER").update();
        }

        Throwable failure = catchThrowable(() -> store.find(tenant.business(), stored.id()));

        assertThat(failure).isInstanceOf(UnexpectedFailure.class);
        assertThat(failure.getMessage()).isEqualTo("Appointment persistence operation failed");
        assertThat(failure.getCause()).isNull();
    }

    // ---- helpers -------------------------------------------------------------

    private AppointmentServiceLine serviceLine(int position, int minutes, String price) {
        return new AppointmentServiceLine(
                position, fixtures.service(tenant.business(), minutes), "Услуга " + position, minutes,
                new BigDecimal(price));
    }

    private List<AppointmentServiceLine> lines(int count, int minutes, String price) {
        List<AppointmentServiceLine> lines = new ArrayList<>();
        for (int position = 1; position <= count; position++) {
            lines.add(serviceLine(position, minutes, price));
        }
        return lines;
    }

    private NewAppointment visit(
            Instant start, List<AppointmentServiceLine> lines, AppointmentSource source, BookingAttempt attempt) {
        return visitFor(tenant, start, lines, source, attempt);
    }

    private static NewAppointment visitFor(
            Tenant forTenant, Instant start, List<AppointmentServiceLine> lines, AppointmentSource source,
            BookingAttempt attempt) {
        int minutes = lines.stream().mapToInt(AppointmentServiceLine::durationMinutes).sum();
        BigDecimal price = lines.stream().map(AppointmentServiceLine::priceEur)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        AppointmentServiceLine first = lines.get(0);
        return new NewAppointment(
                UUID.randomUUID(), forTenant.business(), forTenant.customer(), first.serviceId(),
                forTenant.staff(), source, start, minutes, price, AppointmentFixtures.TIMEZONE,
                first.serviceName(), AppointmentFixtures.STAFF_NAME, null, AppointmentFixtures.reference(),
                attempt, AppointmentFixtures.CREATED_AT, lines);
    }

    private Appointment insertCommitted(NewAppointment appointment) {
        return transaction().execute(status -> store.insert(appointment));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
