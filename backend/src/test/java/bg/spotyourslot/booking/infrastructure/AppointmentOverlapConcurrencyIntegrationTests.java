package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.OverlapConflict;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-PostgreSQL commit and rollback races of the overlap exclusion. A first transaction holds
 * its write open on a latch; the second transaction is proven to be waiting on a PostgreSQL lock
 * through {@code pg_stat_activity} before the first is released, or, where the ranges cannot
 * conflict, to finish while the first is still open. No sleeps and no timing assumptions.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentOverlapConcurrencyIntegrationTests extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");

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

    @Test
    void identicalSlotsHaveExactlyOneWinnerAndTheLoserFailsWhenTheFirstCommits() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 60);
        NewAppointment second = AppointmentFixtures.online(tenant, START, 60);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.insert(first));
            race.startSecond(() -> store.insert(second));
            race.assertSecondWaitsOnALock();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(store.find(tenant.business(), first.id())).isPresent();
        assertThat(store.find(tenant.business(), second.id())).isEmpty();
        assertThat(blockingRows(tenant.staff())).isEqualTo(1L);
        assertHealthyAfterwards();
    }

    @Test
    void partiallyOverlappingSlotsHaveExactlyOneWinner() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 60);
        NewAppointment second = AppointmentFixtures.online(
                tenant, START.plusSeconds(45 * 60L), 60);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.insert(first));
            race.startSecond(() -> store.insert(second));
            race.assertSecondWaitsOnALock();

            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
            assertThat(race.secondOutcome()).isInstanceOf(OverlapConflict.class);
        }

        assertThat(blockingRows(tenant.staff())).isEqualTo(1L);
        assertThat(store.find(tenant.business(), second.id())).isEmpty();
    }

    @Test
    void theSecondInsertSucceedsWhenTheFirstTransactionRollsBack() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 60);
        NewAppointment second = AppointmentFixtures.online(tenant, START, 60);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.insert(first));
            race.startSecond(() -> store.insert(second));
            race.assertSecondWaitsOnALock();

            race.rollBackFirst();

            assertThat(race.firstOutcome()).isInstanceOf(RollbackRequested.class);
            assertThat(race.secondOutcome()).isInstanceOf(Appointment.class);
        }

        assertThat(store.find(tenant.business(), first.id())).isEmpty();
        assertThat(store.find(tenant.business(), second.id())).isPresent();
        assertThat(blockingRows(tenant.staff())).isEqualTo(1L);
    }

    @Test
    void adjacentSlotsNeverWaitForEachOther() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 60);
        NewAppointment second = AppointmentFixtures.online(tenant, START.plusSeconds(3600), 30);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.insert(first));
            race.startSecond(() -> store.insert(second));

            // The second finishes while the first is still open: no lock wait exists.
            assertThat(race.secondOutcomeWhileFirstIsOpen()).isInstanceOf(Appointment.class);
            race.commitFirst();

            assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
        }

        assertThat(blockingRows(tenant.staff())).isEqualTo(2L);
    }

    @Test
    void differentStaffMembersAndDifferentBusinessesNeverWait() {
        UUID secondStaff = fixtures.staffMember(tenant.business(), tenant.service());
        Tenant sameBusinessOtherStaff =
                new Tenant(tenant.business(), tenant.service(), secondStaff, tenant.customer());
        Tenant otherBusiness = fixtures.tenant();
        Tenant[] others = {sameBusinessOtherStaff, otherBusiness};

        for (int index = 0; index < others.length; index++) {
            // The held first insert occupies START for the base StaffMember on its own day.
            NewAppointment held = AppointmentFixtures.online(
                    tenant, START.plusSeconds(86_400L * (index + 1)), 60);
            NewAppointment concurrent = AppointmentFixtures.online(
                    others[index], held.startAt(), 60);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Race race = new Race(executor);
                race.holdFirst(() -> store.insert(held));
                race.startSecond(() -> store.insert(concurrent));

                assertThat(race.secondOutcomeWhileFirstIsOpen()).isInstanceOf(Appointment.class);
                race.commitFirst();
                assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
            }
        }

        assertThat(blockingRows(tenant.staff())).isEqualTo(2L);
        assertThat(blockingRows(secondStaff)).isEqualTo(1L);
        assertThat(blockingRows(otherBusiness.staff())).isEqualTo(1L);
    }

    @Test
    void aCancelledRowNeverWaitsForAnUncommittedConfirmedRow() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 60);
        Map<String, Object> cancelled = AppointmentFixtures.row(tenant, START, 60);
        cancelled.put("status", "CANCELLED");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Race race = new Race(executor);
            race.holdFirst(() -> store.insert(first));
            race.startSecond(() -> {
                fixtures.insertRow(cancelled);
                return "inserted";
            });

            assertThat(race.secondOutcomeWhileFirstIsOpen()).isEqualTo("inserted");
            race.commitFirst();
            assertThat(race.firstOutcome()).isInstanceOf(Appointment.class);
        }

        assertThat(blockingRows(tenant.staff())).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single())
                .isEqualTo(2L);
    }

    @Test
    void aLosingBookingLeavesTheWinnerUntouchedAndNoPartialRowBehind() {
        NewAppointment first = AppointmentFixtures.online(tenant, START, 60);
        Appointment winner = transaction().execute(status -> store.insert(first));
        NewAppointment extra = AppointmentFixtures.online(tenant, START.plusSeconds(7200), 30);
        NewAppointment loser = AppointmentFixtures.online(tenant, START.plusSeconds(600), 30);

        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(
                () -> transaction().execute(status -> {
                    store.insert(extra);
                    return store.insert(loser);
                }));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(store.find(tenant.business(), winner.id())).contains(winner);
        assertThat(store.find(tenant.business(), extra.id())).isEmpty();
        assertThat(store.find(tenant.business(), loser.id())).isEmpty();
        assertThat(blockingRows(tenant.staff())).isEqualTo(1L);
    }

    private void assertHealthyAfterwards() {
        transaction().executeWithoutResult(status -> store.insert(
                AppointmentFixtures.online(tenant, START.plusSeconds(86_400L), 30)));
        assertThat(blockingRows(tenant.staff())).isEqualTo(2L);
    }

    private long blockingRows(UUID staff) {
        return jdbc.sql("""
                        SELECT count(*) FROM appointment
                        WHERE staff_member_id = :staff AND status = 'CONFIRMED'
                        """)
                .param("staff", staff)
                .query(Long.class)
                .single();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private static final class RollbackRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private RollbackRequested() {
            super("rollback requested", null, false, false);
        }
    }

    private final class Race {
        private final ExecutorService executor;
        private final CountDownLatch firstWrote = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicInteger secondBackendPid = new AtomicInteger();
        private volatile boolean commit = true;
        private CompletableFuture<Object> first;
        private CompletableFuture<Object> second;

        private Race(ExecutorService executor) {
            this.executor = executor;
        }

        private void holdFirst(Supplier<Object> operation) {
            first = CompletableFuture.supplyAsync(() -> attempt(() -> transaction().execute(
                    status -> {
                        Object result = operation.get();
                        firstWrote.countDown();
                        await(releaseFirst, "the first transaction was not released");
                        if (!commit) {
                            throw new RollbackRequested();
                        }
                        return result;
                    })), executor);
            await(firstWrote, "the first transaction did not perform its write");
        }

        private void startSecond(Supplier<Object> operation) {
            second = CompletableFuture.supplyAsync(() -> attempt(() -> transaction().execute(
                    status -> {
                        secondBackendPid.set(backendPid());
                        secondStarted.countDown();
                        return operation.get();
                    })), executor);
            await(secondStarted, "the second transaction did not start");
        }

        private void assertSecondWaitsOnALock() {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                boolean waiting = jdbc.sql("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE pid = :pid AND wait_event_type = 'Lock'
                                """)
                        .param("pid", secondBackendPid.get())
                        .query(Long.class)
                        .single() > 0;
                if (waiting) {
                    assertThat(second).isNotDone();
                    return;
                }
                Thread.onSpinWait();
            }
            throw new AssertionError("PostgreSQL lock wait was not observed");
        }

        private Object secondOutcomeWhileFirstIsOpen() {
            assertThat(first).isNotDone();
            Object outcome = completed(second);
            assertThat(first).isNotDone();
            return outcome;
        }

        private void commitFirst() {
            releaseFirst.countDown();
        }

        private void rollBackFirst() {
            commit = false;
            releaseFirst.countDown();
        }

        private Object firstOutcome() {
            return completed(first);
        }

        private Object secondOutcome() {
            return completed(second);
        }
    }

    private static Object attempt(Supplier<Object> operation) {
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static Object completed(CompletableFuture<Object> future) {
        try {
            return future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError("the operation did not complete", failure);
        }
    }

    private static void await(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError(failure);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, interrupted);
        }
    }
}
