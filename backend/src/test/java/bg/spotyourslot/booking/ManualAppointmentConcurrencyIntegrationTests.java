package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.assertNotBlocked;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.ReviewChanged;
import bg.spotyourslot.booking.AppointmentAdministrationException.ServiceUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.TemporarilyUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.booking.BookingTestHooks.TransactionObservation;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.RescheduleUpdate;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner-created Appointment races with real PostgreSQL. Every interleaving is ordered by latches
 * inside the attempt and by lock-wait evidence from {@code pg_stat_activity} and
 * {@code pg_blocking_pids}; nothing sleeps. The failures PostgreSQL raises (exclusion,
 * serialization, deadlock) are produced by the database and travel through the real orchestration.
 *
 * <p>Neighbouring Appointments are cancelled or moved here only through the persistence primitives
 * of {@link AppointmentStore} (the Phase 2 conditional updates); the Phase 5 and 6 orchestration
 * races of reschedule, reassign, and cancel are not covered by this class.
 */
class ManualAppointmentConcurrencyIntegrationTests extends ManualAppointmentTest {
    @Autowired BusinessAdministration businesses;
    @Autowired ServiceAdministration services;
    @Autowired StaffMemberAdministration staffMembers;
    @Autowired AppointmentStore store;

    // ---- helpers ------------------------------------------------------------------

    private int pidOf(String label) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<TransactionObservation> mine = hooks.observationsOf(threadOf(label));
            if (!mine.isEmpty()) {
                return mine.get(0).backendPid();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("attempt " + label + " never reached an observed point");
    }

    private int backendOfWaiter(int holder) {
        return awaitWaiterBlockedBy(jdbc, holder).pid();
    }

    private void assertDistinctTransactionsAndSnapshots(String label, int attempts) {
        List<TransactionObservation> firstPoint = hooks.observationsOf(threadOf(label)).stream()
                .filter(observation -> observation.point() == Point.BEFORE_STAFF_LOCK)
                .toList();
        assertThat(firstPoint).hasSize(attempts);
        assertThat(firstPoint.stream().map(TransactionObservation::transactionId).distinct().count())
                .as("distinct PostgreSQL transaction identifiers").isEqualTo(attempts);
        assertThat(firstPoint.stream().map(TransactionObservation::snapshot).distinct().count())
                .as("fresh snapshots").isEqualTo(attempts);
    }

    /** Which of the attempt's rows another transaction cannot lock FOR UPDATE (so it holds a lock). */
    private Set<String> lockedRows(UUID user) {
        return async(() -> {
            Set<String> locked = new TreeSet<>();
            String[][] probes = {
                {"business", "SELECT id FROM business WHERE id = :business"},
                {"membership", "SELECT id FROM membership WHERE business_id = :business AND user_id = :user"},
                {"staff", "SELECT id FROM staff_member WHERE id = :staff"},
                {"revision", "SELECT business_id FROM business_schedule_revision WHERE business_id = :business"},
                {"service", "SELECT id FROM service WHERE id = :service"},
                {"customer", "SELECT id FROM customer WHERE id = :customer"}};
            for (String[] probe : probes) {
                boolean free = !jdbc.sql(probe[1] + " FOR UPDATE SKIP LOCKED")
                        .param("business", tenant.business())
                        .param("user", user)
                        .param("staff", tenant.staff())
                        .param("service", tenant.service())
                        .param("customer", customerId)
                        .query(UUID.class).list().isEmpty();
                if (!free) {
                    locked.add(probe[0]);
                }
            }
            return locked;
        }).join();
    }

    private UUID ownerUser() {
        return ownerContext.userId();
    }

    // ---- lock order ----------------------------------------------------------------

    @Test
    void theAttemptTakesBusinessMembershipStaffMemberRevisionServiceInThatOrderAndTheCustomerOnlyAtTheInsert() {
        List<String> points = new ArrayList<>();
        List<Set<String>> held = new ArrayList<>();
        for (ManualBookingHooks.Point point : List.of(
                ManualBookingHooks.Point.BEFORE_OWNER_LOCK,
                ManualBookingHooks.Point.BEFORE_CUSTOMER_READ,
                ManualBookingHooks.Point.BEFORE_INSERT)) {
            manualHooks.on(point, invocation -> {
                points.add(invocation.point().name());
                held.add(lockedRows(ownerUser()));
            });
        }
        for (Point point : List.of(Point.BEFORE_STAFF_LOCK, Point.BEFORE_GUARD, Point.BEFORE_SERVICE_LOCK,
                Point.BEFORE_AVAILABILITY)) {
            hooks.on(point, invocation -> {
                points.add(invocation.point().name());
                held.add(lockedRows(ownerUser()));
            });
        }

        create(cmd());

        assertThat(points).containsExactly(
                "BEFORE_OWNER_LOCK", "BEFORE_CUSTOMER_READ", "BEFORE_STAFF_LOCK", "BEFORE_GUARD",
                "BEFORE_SERVICE_LOCK", "BEFORE_AVAILABILITY", "BEFORE_INSERT");
        assertThat(held).containsExactly(
                Set.of("business"),
                Set.of("business", "membership"),
                Set.of("business", "membership"),
                Set.of("business", "membership", "staff"),
                Set.of("business", "membership", "revision", "staff"),
                Set.of("business", "membership", "revision", "service", "staff"),
                Set.of("business", "membership", "revision", "service", "staff"));
        // The Customer is read without a lock; its implicit key-share lock exists only after the insert.
        assertThat(held).noneMatch(rows -> rows.contains("customer"));
    }

    @Test
    void concurrentCreationsOfDifferentTimesNeverBlockEachOther() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(ManualBookingHooks.Point.BEFORE_INSERT, "a", reached, release);
        CompletableFuture<CreatedAppointment> first = submitCreate("a", cmd().start("09:00"));
        await(reached, "creation a never reached its insert");

        CreatedAppointment second = completed(submitCreate("b", cmd().start("10:30")));

        assertThat(second.start()).isEqualTo(at("10:30"));
        assertThat(first).isNotDone();
        assertNotBlocked(jdbc, pidOf("a"));
        release.countDown();
        assertThat(completed(first).start()).isEqualTo(at("09:00"));
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
    }

    // ---- competing creations --------------------------------------------------------------

    @Test
    void twoOwnersCreatingTheSameSlotLeaveExactlyOneVisitAndTheLoserIsToldTheTimeIsTaken() {
        AuthenticatedBusinessContext secondOwner = owner(tenant.business());
        String customersBefore = customerSnapshot();
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<CreatedAppointment> winner = submitCreate("a", ownerContext, cmd().build());
        await(committing, "creation a never reached its commit");
        // A holds its inserted, uncommitted visit; B validated against a snapshot that cannot see it.
        CompletableFuture<CreatedAppointment> loser = submitCreate("b", secondOwner, cmd().build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));

        assertThat(loser).isNotDone();
        release.countDown();

        assertThat(completed(winner).source()).isEqualTo("MANUAL");
        assertThat(failureOf(loser)).isInstanceOf(SlotUnavailable.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(linesCount(tenant.business())).isEqualTo(1);
        assertThat(customerSnapshot()).isEqualTo(customersBefore);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("b")))
                .containsExactly(threadOf("b") + ":rollback", threadOf("b") + ":rollback");
        assertDistinctTransactionsAndSnapshots("b", 2);
    }

    @Test
    void simultaneousInsertsIntoTheExclusionIndexLeaveOneWinnerAndTheLoserRetriesToSlotUnavailable() {
        AuthenticatedBusinessContext secondOwner = owner(tenant.business());
        CountDownLatch bothArrived = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            if (invocation.ordinal() == 1) {
                bothArrived.countDown();
                await(release, "the insert barrier was never released");
            }
        });
        CompletableFuture<CreatedAppointment> a = submitCreate("a", ownerContext, cmd().build());
        CompletableFuture<CreatedAppointment> b = submitCreate("b", secondOwner, cmd().build());
        await(bothArrived, "both creations never reached the insert barrier");
        // Both validated the same free slot and now insert into the exclusion index together.
        // PostgreSQL resolves the cycle itself (a deadlock victim or a plain overlap), and either
        // attempt may win; the outcome class is what is asserted.
        release.countDown();

        int created = 0;
        for (CompletableFuture<CreatedAppointment> future : List.of(a, b)) {
            try {
                completed(future);
                created++;
            } catch (AssertionError failed) {
                assertThat(failed.getCause().getCause()).isInstanceOf(SlotUnavailable.class);
            }
        }

        assertThat(created).isEqualTo(1);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(linesCount(tenant.business())).isEqualTo(1);
        int loserBegins = Math.max(
                TransactionLog.begins(threadOf("a")).size(), TransactionLog.begins(threadOf("b")).size());
        // The deadlock victim retries; its second snapshot may still precede the winner's commit, in which
        // case it loses the plain overlap on a third attempt. Both are whole new transactions.
        assertThat(loserBegins).as("the loser retried in completely new transactions").isBetween(2, 3);
    }

    @Test
    void aManualCreationRacingAGuestBookingForTheSameSlotLeavesOneWinnerWhicheverOrder() {
        // Guest first, manual loses.
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("g"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<BookingResult> guest = submit("g", req().staff(tenant.staff()).build());
        await(committing, "the guest booking never reached its commit");
        CompletableFuture<CreatedAppointment> manual = submitCreate("m", cmd());
        awaitBlockedBy(jdbc, pidOf("m"), pidOf("g"));
        release.countDown();

        assertThat(completed(guest)).isInstanceOf(BookingResult.Created.class);
        assertThat(failureOf(manual)).isInstanceOf(SlotUnavailable.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(2); // the guest's new Customer and the fixture

        // Manual first, the guest loses.
        hooks.reset();
        manualHooks.reset();
        CountDownLatch manualCommitting = new CountDownLatch(1);
        CountDownLatch manualRelease = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            if (invocation.thread().equals(threadOf("m2"))) {
                holdBeforeCommit(manualCommitting, manualRelease);
            }
        });
        CompletableFuture<CreatedAppointment> manualFirst = submitCreate("m2", cmd().start("11:00"));
        await(manualCommitting, "the manual creation never reached its commit");
        CompletableFuture<BookingResult> guestSecond = submit(
                "g2", req().staff(tenant.staff()).start("11:00").build());
        awaitBlockedBy(jdbc, pidOf("g2"), pidOf("m2"));
        manualRelease.countDown();

        assertThat(completed(manualFirst).source()).isEqualTo("MANUAL");
        assertThat(completed(guestSecond)).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
    }

    // ---- committed changes after the snapshot (real 40001 through the real orchestration) -------

    @Test
    void aServiceDeactivatedAfterTheSnapshotIsRejectedByTheRetryAndNothingIsWritten() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached the staff lock");
        availabilityFixtures.setServiceActive(tenant.service(), false);
        release.countDown();

        assertThat(failureOf(creation)).isInstanceOf(ServiceUnavailable.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertDistinctTransactionsAndSnapshots("a", 2);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void aServiceTermChangeAfterTheSnapshotFailsTheServiceLockAndTheRetryReportsReviewChanged() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached the staff lock");
        jdbc.sql("UPDATE service SET price = 12.50 WHERE id = :id").param("id", tenant.service()).update();
        release.countDown();

        assertThat(failureOf(creation)).isInstanceOf(ReviewChanged.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void aStaffMemberDeactivatedAfterTheSnapshotIsRejectedByTheRetry() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached the staff lock");
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);
        release.countDown();

        assertThat(failureOf(creation)).isInstanceOf(StaffUnavailable.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void anAssignmentRemovedAfterTheSnapshotThroughTheRealAdministrationIsRejectedByTheRetry() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached the staff lock");
        staffMembers.replaceServiceAssignments(
                ownerContext, tenant.staff(), new ReplaceServiceAssignmentsCommand(List.of(), 0L));
        release.countDown();

        assertThat(failureOf(creation)).isInstanceOf(StaffUnavailable.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    // ---- in-flight changes make the attempt wait, then retry ------------------------------------------

    /** An open transaction on another connection that changed a row and has not committed. */
    private final class OpenChange {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch commit = new CountDownLatch(1);
        final int[] pid = new int[1];
        final CompletableFuture<Void> done;

        OpenChange(Runnable change) {
            done = async(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    change.run();
                    pid[0] = backendPid(jdbc);
                    held.countDown();
                    await(commit, "the open change was never committed");
                });
                return null;
            });
            await(held, "the open change never started");
        }

        void commit() {
            commit.countDown();
            completed(done);
        }
    }

    @Test
    void aBusinessSuspensionInFlightMakesTheFirstStatementWaitThenFailAndTheRetryIsBusinessSuspended() {
        OpenChange suspension = new OpenChange(() ->
                jdbc.sql("UPDATE business SET status = 'SUSPENDED' WHERE id = :id")
                        .param("id", tenant.business()).update());
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        awaitWaiterBlockedBy(jdbc, suspension.pid[0]);
        assertThat(creation).isNotDone();

        suspension.commit();

        assertThat(failureOf(creation)).isInstanceOf(BusinessSuspended.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void aBusinessSuspensionCommittedAfterTheSnapshotDoesNotBlockTheOpenAttemptWhichThenCommits() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(ManualBookingHooks.Point.BEFORE_INSERT, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached its insert");

        CompletableFuture<Void> suspension = async(() -> {
            businesses.suspendActive(tenant.business(), 0L);
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(suspension).isNotDone();
        release.countDown();

        assertThat(completed(creation).status()).isEqualTo("CONFIRMED");
        completed(suspension);
        assertThat(failureOf(submitCreate("next", cmd().start("10:30")))).isInstanceOf(BusinessSuspended.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aMembershipDeactivatedAfterTheSnapshotFailsTheMembershipLockAndTheRetryIsDenied() {
        manualHooks.on(ManualBookingHooks.Point.BEFORE_OWNER_LOCK, invocation -> {
            if (invocation.ordinal() == 1) {
                async(() -> jdbc.sql("UPDATE membership SET active = false WHERE business_id = :b AND user_id = :u")
                        .param("b", tenant.business()).param("u", ownerUser()).update()).join();
            }
        });

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(AccessDenied.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void aServiceChangeInFlightMakesTheServiceLockWaitThenFailAndTheRetrySeesTheServiceUnavailable() {
        OpenChange deactivation = new OpenChange(() ->
                jdbc.sql("UPDATE service SET active = false WHERE id = :id")
                        .param("id", tenant.service()).update());
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        awaitWaiterBlockedBy(jdbc, deactivation.pid[0]);

        deactivation.commit();

        assertThat(failureOf(creation)).isInstanceOf(ServiceUnavailable.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    // ---- administrative changes wait for the creation's shared locks --------------------------------

    @Test
    void aServiceDeactivationWaitsForTheCreationThatHoldsTheServiceLockAndTheVisitKeepsItsSnapshot() {
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_AVAILABILITY, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached availability");

        CompletableFuture<Void> deactivation = async(() -> {
            services.deactivate(ownerContext, tenant.service(), new ServiceVersionCommand(0L));
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(deactivation).isNotDone();
        release.countDown();

        CreatedAppointment created = completed(creation);
        completed(deactivation);
        assertThat(created.services()).hasSize(1);
        assertThat(linesOf(created.id())).hasSize(1);
        assertThat(failureOf(submitCreate("next", cmd().start("10:30")))).isInstanceOf(ServiceUnavailable.class);
    }

    @Test
    void aStaffMemberDeactivationAndAnAssignmentReplacementWaitForTheCreationThatHoldsTheStaffLock() {
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_GUARD, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached the guard");

        CompletableFuture<Void> deactivation = async(() -> {
            staffMembers.deactivate(ownerContext, tenant.staff(), new StaffMemberVersionCommand(0L));
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(deactivation).isNotDone();
        release.countDown();

        assertThat(completed(creation).status()).isEqualTo("CONFIRMED");
        completed(deactivation);
        assertThat(failureOf(submitCreate("next", cmd().start("10:30")))).isInstanceOf(StaffUnavailable.class);
    }

    @Test
    void anAssignmentReplacementWaitsForTheCreationThatHoldsTheStaffLock() {
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_GUARD, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached the guard");

        CompletableFuture<Void> replacement = async(() -> {
            staffMembers.replaceServiceAssignments(
                    ownerContext, tenant.staff(), new ReplaceServiceAssignmentsCommand(List.of(), 0L));
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(replacement).isNotDone();
        release.countDown();

        assertThat(completed(creation).status()).isEqualTo("CONFIRMED");
        completed(replacement);
        assertThat(failureOf(submitCreate("next", cmd().start("10:30")))).isInstanceOf(StaffUnavailable.class);
    }

    // ---- the Customer ----------------------------------------------------------------------------------

    @Test
    void aCustomerContactUpdateDoesNotWaitForTheOpenCreationAndTheVisitNeverChangesTheCustomer() {
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> holdBeforeCommit(committing, release));
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(committing, "creation never reached its commit");

        // The visit's insert holds only a key-share lock on the Customer row; a contact update is not a key change.
        CompletableFuture<Integer> update = async(() -> jdbc.sql(
                "UPDATE customer SET display_name = 'Преименуван Клиент', version = version + 1 WHERE id = :id")
                .param("id", customerId).update());
        assertThat(completed(update)).isEqualTo(1);
        assertNotBlocked(jdbc, pidOf("a"));
        release.countDown();

        completed(creation);
        assertThat(jdbc.sql("SELECT display_name FROM customer WHERE id = :id").param("id", customerId)
                .query(String.class).single()).isEqualTo("Преименуван Клиент");
        assertThat(jdbc.sql("SELECT version FROM customer WHERE id = :id").param("id", customerId)
                .query(Long.class).single()).isEqualTo(1L);
    }

    // ---- neighbouring Appointments (persistence primitives only) -----------------------------------------

    private UUID neighbour(String time) {
        return insertRow(at(time));
    }

    private UUID insertRow(Instant start) {
        Map<String, Object> row = new LinkedHashMap<>(AppointmentFixtures.row(tenant, start, 30));
        row.put("customer_id", customerId);
        // Created before the clock's "now", so the conditional updates may stamp the clock instant.
        row.put("created_at", AppointmentFixtures.utc(Instant.parse("2026-09-01T00:00:00Z")));
        row.put("updated_at", AppointmentFixtures.utc(Instant.parse("2026-09-01T00:00:00Z")));
        fixtures.insertRow(row);
        return (UUID) row.get("id");
    }

    @Test
    void aNeighbourCancelledAfterTheSnapshotStillBlocksTheCreationWhichIsToldTheTimeIsTakenAndMayRepeat() {
        UUID existing = neighbour("10:00");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached the staff lock");
        // Persistence primitive of Phase 2; the orchestrated cancel is Phase 6.
        assertThat(store.cancel(new CancelUpdate(tenant.business(), existing, 0L, BookingHookConfiguration.NOW)))
                .isPresent();
        release.countDown();

        assertThat(failureOf(creation)).isInstanceOf(SlotUnavailable.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
        assertThat(create(cmd()).start()).isEqualTo(at("10:00"));
    }

    @Test
    void aNeighbourMovedIntoTheIntervalAfterAvailabilityMakesTheInsertLoseTheExclusionAndTheRetryIsToldItIsTaken() {
        UUID existing = neighbour("11:00");
        hooks.observeTransactions(jdbc);
        AtomicInteger moves = new AtomicInteger();
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            if (moves.incrementAndGet() == 1) {
                async(() -> store.reschedule(new RescheduleUpdate(
                        tenant.business(), existing, 0L, tenant.staff(), at("11:00"),
                        at("10:00"), at("10:30"), at("10:30"), tenant.staff(), "Мария",
                        BookingHookConfiguration.NOW))).join();
            }
        });

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(SlotUnavailable.class);

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void anAdjacentNeighbourNeverConflictsWhateverItDoes() {
        neighbour("10:30");
        neighbour("09:30");

        assertThat(create(cmd()).start()).isEqualTo(at("10:00"));
        assertThat(appointmentCount(tenant.business())).isEqualTo(3);
    }

    // ---- retry exhaustion ---------------------------------------------------------------------------------

    @Test
    void anOverlapOnEveryAttemptEndsSlotUnavailableAfterExactlyThreeCompleteTransactionsAndLeavesNoVisit() {
        hooks.observeTransactions(jdbc);
        List<UUID> blockers = new ArrayList<>();
        manualHooks.on(ManualBookingHooks.Point.BEFORE_INSERT, invocation -> {
            // After availability offered the slot, another connection commits a visit on it ...
            UUID blocker = async(() -> insertRow(at("10:00"))).join();
            blockers.add(blocker);
            // ... and frees the time again as soon as this attempt has rolled back, before the next snapshot.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    async(() -> fixtures.cancel(blocker)).join();
                }
            });
        });

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(SlotUnavailable.class);

        assertThat(blockers).hasSize(3);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(3);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(
                threadOf("a") + ":rollback", threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertDistinctTransactionsAndSnapshots("a", 3);
        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE status = 'CONFIRMED'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE source = 'MANUAL' AND customer_id = :c")
                .param("c", customerId).query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void aConcurrencyFailureOnEveryAttemptEndsTemporarilyUnavailableAfterExactlyThreeAttempts() {
        hooks.observeTransactions(jdbc);
        AtomicInteger changes = new AtomicInteger();
        hooks.on(Point.BEFORE_STAFF_LOCK, invocation -> {
            changes.incrementAndGet();
            async(() -> jdbc.sql("UPDATE service SET price = price + 0.01 WHERE id = :id")
                    .param("id", tenant.service()).update()).join();
        });

        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(TemporarilyUnavailable.class);

        assertThat(changes.get()).isEqualTo(3);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(3);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(
                threadOf("a") + ":rollback", threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertDistinctTransactionsAndSnapshots("a", 3);
        assertThat(appointmentCount(tenant.business())).isZero();
        hooks.reset();
        assertThat(completed(submitCreate("again", cmd())).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void manyCreationsOfTheSameTimeLeaveExactlyOneVisit() {
        List<CompletableFuture<CreatedAppointment>> futures = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            futures.add(submitCreate("s" + index, owner(tenant.business()), cmd().build()));
        }

        int created = 0;
        for (CompletableFuture<CreatedAppointment> future : futures) {
            try {
                completed(future);
                created++;
            } catch (AssertionError failed) {
                // Known rollbacks only: the time is taken, or PostgreSQL chose every attempt as a deadlock victim.
                assertThat(failed.getCause().getCause()).isInstanceOfAny(
                        SlotUnavailable.class, TemporarilyUnavailable.class);
            }
        }

        assertThat(created).isEqualTo(1);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(linesCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void anotherBusinessIsUnaffectedByTheTenantsLocksAndCreationsForeignToItsOwnData() {
        Tenant other = fixtures.tenant();
        availabilityFixtures.everyDay(other.business(), other.staff(), "09:00", "12:00");
        UUID otherCustomer = fixtures.customer(other.business());
        AuthenticatedBusinessContext otherOwner = owner(other.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(ManualBookingHooks.Point.BEFORE_INSERT, "a", reached, release);
        CompletableFuture<CreatedAppointment> creation = submitCreate("a", cmd());
        await(reached, "creation never reached its insert");

        CreatedAppointment foreignCreated = completed(submitCreate("b", otherOwner,
                new AppointmentAdministrationRecords.CreateAppointmentCommand(
                        otherCustomer, other.staff(), at("10:00"),
                        List.of(reviewed(other.service())), null)));
        release.countDown();

        assertThat(foreignCreated.staffMemberId()).isEqualTo(other.staff());
        assertThat(completed(creation).staffMemberId()).isEqualTo(tenant.staff());
        assertThat(appointmentCount(other.business())).isEqualTo(1);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }
}
