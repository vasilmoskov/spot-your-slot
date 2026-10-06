package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.AppointmentStatus;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.DuplicateAttempt;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.DuplicatePublicReference;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.InvalidData;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.OverlapConflict;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.UnknownReference;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The Appointment store against real PostgreSQL with synthetic fixtures and a fixed clock value. */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final String SENTINEL = "СЕНТИНЕЛ-бележка-7731";

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

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private Appointment insertCommitted(NewAppointment appointment) {
        return transaction().execute(status -> store.insert(appointment));
    }

    // ---- persistence and snapshots -------------------------------------------

    @Test
    void anOnlineInsertReturnsEverySnapshotTheAttemptAndTheInitialLifecycle() {
        BookingAttempt attempt = AppointmentFixtures.attempt();
        NewAppointment request = new NewAppointment(
                UUID.randomUUID(), tenant.business(), tenant.customer(), tenant.service(),
                tenant.staff(), AppointmentSource.ONLINE, START, 45, new BigDecimal("37.5"),
                "Europe/Sofia", "Боядисване", "Иван Тестов", "Моля, без парфюм.\nБлагодаря.",
                "7ZQ4M9XB2K", attempt, Instant.parse("2026-10-01T08:00:00Z"));

        Appointment stored = insertCommitted(request);

        assertThat(stored.id()).isEqualTo(request.id());
        assertThat(stored.businessId()).isEqualTo(tenant.business());
        assertThat(stored.customerId()).isEqualTo(tenant.customer());
        assertThat(stored.serviceId()).isEqualTo(tenant.service());
        assertThat(stored.staffMemberId()).isEqualTo(tenant.staff());
        assertThat(stored.source()).isEqualTo(AppointmentSource.ONLINE);
        assertThat(stored.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(stored.startAt()).isEqualTo(START);
        assertThat(stored.endAt()).isEqualTo(START.plusSeconds(45 * 60L));
        assertThat(stored.occupiedUntil()).isEqualTo(stored.endAt());
        assertThat(stored.timezone()).isEqualTo("Europe/Sofia");
        assertThat(stored.durationMinutes()).isEqualTo(45);
        assertThat(stored.priceEur()).isEqualTo(new BigDecimal("37.50"));
        assertThat(stored.serviceName()).isEqualTo("Боядисване");
        assertThat(stored.staffDisplayName()).isEqualTo("Иван Тестов");
        assertThat(stored.customerNote()).isEqualTo("Моля, без парфюм.\nБлагодаря.");
        assertThat(stored.publicReference()).isEqualTo("7ZQ4M9XB2K");
        assertThat(stored.attempt()).isEqualTo(attempt);
        assertThat(stored.version()).isZero();
        assertThat(stored.createdAt()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(stored.updatedAt()).isEqualTo(stored.createdAt());
        assertThat(store.find(tenant.business(), request.id())).contains(stored);
    }

    @Test
    void aManualInsertHasNoAttemptAndNoNote() {
        Appointment stored = insertCommitted(AppointmentFixtures.manual(tenant, START, 30));

        assertThat(stored.source()).isEqualTo(AppointmentSource.MANUAL);
        assertThat(stored.attempt()).isNull();
        assertThat(stored.customerNote()).isNull();
        assertThat(store.findByAttemptHash(tenant.business(), AppointmentFixtures.digest())).isEmpty();
    }

    @Test
    void theStoredSnapshotsAreIndependentOfLaterServiceStaffAndBusinessChanges() {
        Appointment stored = insertCommitted(AppointmentFixtures.online(tenant, START, 30));

        jdbc.sql("UPDATE service SET name = 'Нова', price = 77.00, duration_minutes = 90 WHERE id = :id")
                .param("id", tenant.service()).update();
        jdbc.sql("UPDATE staff_member SET display_name = 'Нов', active = false WHERE id = :id")
                .param("id", tenant.staff()).update();

        assertThat(store.find(tenant.business(), stored.id())).contains(stored);
    }

    // ---- tenant scoping ------------------------------------------------------

    @Test
    void lookupsAreBusinessScopedAndAForeignBusinessBehavesLikeAMissingRow() {
        Tenant other = fixtures.tenant();
        Appointment stored = insertCommitted(AppointmentFixtures.online(tenant, START, 30));
        byte[] hash = stored.attempt().attemptHash();

        assertThat(store.find(tenant.business(), stored.id())).isPresent();
        assertThat(store.find(other.business(), stored.id())).isEmpty();
        assertThat(store.find(tenant.business(), UUID.randomUUID())).isEmpty();
        assertThat(store.findByAttemptHash(tenant.business(), hash)).contains(stored);
        assertThat(store.findByAttemptHash(other.business(), hash)).isEmpty();
        assertThat(store.findByAttemptHash(tenant.business(), AppointmentFixtures.digest())).isEmpty();
    }

    // ---- typed, sanitized failures -------------------------------------------

    @Test
    void anOverlapIsTheTypedConflictAndTheConnectionStaysHealthy() {
        insertCommitted(AppointmentFixtures.online(tenant, START, 60));

        Throwable failure = catchThrowable(() -> insertCommitted(
                AppointmentFixtures.online(tenant, START.plusSeconds(30 * 60L), 60)));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(rows()).isEqualTo(1L);
        // A completely new transaction works, and adjacency is allowed.
        insertCommitted(AppointmentFixtures.online(tenant, START.plusSeconds(3600), 30));
        assertThat(rows()).isEqualTo(2L);
    }

    @Test
    void aDuplicatePublicReferenceAndADuplicateAttemptAreTyped() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 30);
        insertCommitted(first);

        NewAppointment sameReference = new NewAppointment(
                UUID.randomUUID(), tenant.business(), tenant.customer(), tenant.service(),
                tenant.staff(), AppointmentSource.ONLINE, START.plusSeconds(7200), 30,
                new BigDecimal("25.00"), "Europe/Sofia", "Услуга", "Служител", null,
                first.publicReference(), AppointmentFixtures.attempt(), first.createdAt());
        assertThat(catchThrowable(() -> insertCommitted(sameReference)))
                .isInstanceOf(DuplicatePublicReference.class);

        NewAppointment sameAttempt = new NewAppointment(
                UUID.randomUUID(), tenant.business(), tenant.customer(), tenant.service(),
                tenant.staff(), AppointmentSource.ONLINE, START.plusSeconds(7200), 30,
                new BigDecimal("25.00"), "Europe/Sofia", "Услуга", "Служител", null,
                AppointmentFixtures.reference(), first.attempt(), first.createdAt());
        assertThat(catchThrowable(() -> insertCommitted(sameAttempt)))
                .isInstanceOf(DuplicateAttempt.class);
        assertThat(rows()).isEqualTo(1L);
    }

    @Test
    void aCrossBusinessOrMissingReferenceIsAnUnknownReferenceWithoutSayingWhich() {
        Tenant other = fixtures.tenant();
        NewAppointment foreignCustomer = new NewAppointment(
                UUID.randomUUID(), tenant.business(), other.customer(), tenant.service(),
                tenant.staff(), AppointmentSource.MANUAL, START, 30, BigDecimal.TEN,
                "Europe/Sofia", "Услуга", "Служител", null, AppointmentFixtures.reference(), null,
                Instant.parse("2026-10-01T08:00:00Z"));
        NewAppointment missingService = new NewAppointment(
                UUID.randomUUID(), tenant.business(), tenant.customer(), UUID.randomUUID(),
                tenant.staff(), AppointmentSource.MANUAL, START, 30, BigDecimal.TEN,
                "Europe/Sofia", "Услуга", "Служител", null, AppointmentFixtures.reference(), null,
                Instant.parse("2026-10-01T08:00:00Z"));

        Throwable foreign = catchThrowable(() -> insertCommitted(foreignCustomer));
        Throwable missing = catchThrowable(() -> insertCommitted(missingService));

        assertThat(foreign).isInstanceOf(UnknownReference.class);
        assertThat(missing).isInstanceOf(UnknownReference.class);
        assertThat(foreign.getMessage()).isEqualTo(missing.getMessage());
        assertThat(rows()).isZero();
    }

    @Test
    void aRealDriverRejectionOfAnInvalidValueIsInvalidDataAndLeaksNothing() {
        // The domain rejects a noncanonical name before any statement, so the database rejection
        // is provoked with direct SQL and its real driver exception is classified by the store.
        var row = AppointmentFixtures.row(tenant, START, 30);
        row.put("service_name", "Услуга  " + SENTINEL.replace('-', ' ') + " две");
        row.put("customer_note", SENTINEL);

        Throwable driverFailure = catchThrowable(() -> fixtures.insertRow(row));
        AppointmentPersistenceException translated = AppointmentStore.translate(driverFailure);

        assertThat(driverFailure).isNotNull();
        assertThat(translated).isInstanceOf(InvalidData.class);
        assertSanitized(translated);
        assertThat(rows()).isZero();
    }

    @Test
    void everyFailureFromARealStatementIsSanitized() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 60);
        insertCommitted(first);
        NewAppointment overlap = new NewAppointment(
                UUID.randomUUID(), tenant.business(), tenant.customer(), tenant.service(),
                tenant.staff(), AppointmentSource.MANUAL, START, 60, BigDecimal.TEN,
                "Europe/Sofia", "Услуга", "Служител", SENTINEL, AppointmentFixtures.reference(),
                null, first.createdAt());
        NewAppointment duplicate = new NewAppointment(
                UUID.randomUUID(), tenant.business(), tenant.customer(), tenant.service(),
                tenant.staff(), AppointmentSource.MANUAL, START.plusSeconds(7200), 60,
                BigDecimal.TEN, "Europe/Sofia", "Услуга", "Служител", SENTINEL,
                first.publicReference(), null, first.createdAt());

        assertSanitized(catchThrowable(() -> insertCommitted(overlap)));
        assertSanitized(catchThrowable(() -> insertCommitted(duplicate)));
    }

    // ---- transactions --------------------------------------------------------

    @Test
    void aRollbackRestoresThePreviousStateAndLeavesNoPartialRow() {
        Appointment committed = insertCommitted(AppointmentFixtures.online(tenant, START, 30));
        NewAppointment later = AppointmentFixtures.online(tenant, START.plusSeconds(7200), 30);

        Throwable failure = catchThrowable(() -> transaction().execute(status -> {
            store.insert(later);
            throw new IllegalStateException("abort after the write");
        }));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(tenant.business(), committed.id())).contains(committed);
        assertThat(store.find(tenant.business(), later.id())).isEmpty();
        assertThat(rows()).isEqualTo(1L);
    }

    @Test
    void aFailedStatementRollsBackEarlierWritesOfTheSameTransaction() {
        Appointment committed = insertCommitted(AppointmentFixtures.online(tenant, START, 60));
        NewAppointment fine = AppointmentFixtures.online(tenant, START.plusSeconds(7200), 30);
        NewAppointment overlapping = AppointmentFixtures.online(tenant, START.plusSeconds(1800), 30);

        Throwable failure = catchThrowable(() -> transaction().execute(status -> {
            store.insert(fine);
            return store.insert(overlapping);
        }));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(store.find(tenant.business(), fine.id())).isEmpty();
        assertThat(store.find(tenant.business(), committed.id())).contains(committed);
        assertThat(rows()).isEqualTo(1L);
    }

    @Test
    void theStoreJoinsTheCallersTransactionAndCommitsNothingItself() {
        NewAppointment request = AppointmentFixtures.online(tenant, START, 30);
        List<Boolean> visibleToAnotherConnection = new ArrayList<>();

        transaction().executeWithoutResult(status -> {
            store.insert(request);
            // Another thread uses another connection: an uncommitted write must be invisible.
            visibleToAnotherConnection.add(CompletableFuture
                    .supplyAsync(() -> store.find(tenant.business(), request.id()).isPresent())
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join());
        });

        assertThat(visibleToAnotherConnection).containsExactly(false);
        assertThat(store.find(tenant.business(), request.id())).isPresent();
    }

    @Test
    void anEmptyWindowOrAnEmptyStaffSetOverlapsNothing() {
        insertCommitted(AppointmentFixtures.online(tenant, START, 30));

        assertThat(store.findBlockingWindows(
                tenant.business(), List.of(tenant.staff()), START, START)).isEmpty();
        assertThat(store.findBlockingWindows(
                tenant.business(), List.of(tenant.staff()), START.plusSeconds(1), START)).isEmpty();
        assertThat(store.findBlockingWindows(
                tenant.business(), List.of(), START.minusSeconds(60), START.plusSeconds(3600)))
                .isEmpty();
    }

    private long rows() {
        return jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure).isInstanceOf(AppointmentPersistenceException.class);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage())
                .doesNotContain("СЕНТИНЕЛ")
                .doesNotContain("appointment_")
                .doesNotContain("staff_member_id");
        assertThat(failure.toString())
                .doesNotContain("СЕНТИНЕЛ")
                .doesNotContain("7731")
                .doesNotContain("appointment_")
                .doesNotContainIgnoringCase("insert into")
                .doesNotContainIgnoringCase("tstzrange");
    }
}
