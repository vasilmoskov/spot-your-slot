package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.assertNotBlocked;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.BookingResult.AttemptMismatch;
import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.booking.BookingResult.Replayed;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.booking.BookingTestHooks.TransactionObservation;
import bg.spotyourslot.business.BusinessAdministration;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.integration.ConcurrencyTestSupport.WaitingBackend;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import bg.spotyourslot.workforce.StaffMemberRecords.StaffMemberVersionCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Races between guest bookings and between a booking and administrative changes, with real
 * PostgreSQL. Every interleaving is ordered by latches inside the attempt and by lock-wait evidence
 * from {@code pg_stat_activity} and {@code pg_blocking_pids}; nothing sleeps. The real failures that
 * PostgreSQL raises (exclusion, unique, serialization, deadlock) are produced by the database and
 * travel through the real orchestration to its retry and classification.
 */
class GuestBookingConcurrencyIntegrationTests extends BookingIntegrationTest {
    @Autowired BusinessAdministration businesses;
    @Autowired ServiceAdministration services;
    @Autowired StaffMemberAdministration staffMembers;

    // ---- helpers ------------------------------------------------------------------

    /** The backend of the first attempt of the named booking, once it has reached its first point. */
    private int pidOf(String label) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<TransactionObservation> mine = hooks.observationsOf(threadOf(label));
            if (!mine.isEmpty()) {
                return mine.get(0).backendPid();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("booking " + label + " never reached an observed point");
    }

    /** Holds the named booking at the point, until released, after telling the test it arrived. */
    private void holdAt(Point point, String label, CountDownLatch reached, CountDownLatch release) {
        hooks.on(point, invocation -> {
            if (invocation.thread().equals(threadOf(label)) && invocation.ordinal() == 1) {
                reached.countDown();
                await(release, "booking " + label + " was never released at " + point);
            }
        });
    }

    private Set<String> lockedRows() {
        // A row that another transaction holds a row lock on is skipped by FOR UPDATE SKIP LOCKED.
        // The probe runs on its own connection and autocommits, so it leaves nothing locked.
        return async(() -> {
            Set<String> locked = new TreeSet<>();
            String[][] probes = {
                {"business", "SELECT id FROM business WHERE id = :id"},
                {"staff", "SELECT id FROM staff_member WHERE id = :staff"},
                {"revision", "SELECT business_id FROM business_schedule_revision WHERE business_id = :id"},
                {"service", "SELECT id FROM service WHERE id = :service"}};
            for (String[] probe : probes) {
                boolean free = !jdbc.sql(probe[1] + " FOR UPDATE SKIP LOCKED")
                        .param("id", tenant.business())
                        .param("staff", tenant.staff())
                        .param("service", tenant.service())
                        .query(UUID.class).list().isEmpty();
                if (!free) {
                    locked.add(probe[0]);
                }
            }
            return locked;
        }).join();
    }

    // ---- lock order and shared locks ----------------------------------------------

    @Test
    void theAttemptTakesBusinessThenStaffMemberThenRevisionThenServiceInThatOrder() {
        List<Set<String>> held = new ArrayList<>();
        List<Point> points = new ArrayList<>();
        for (Point point : Point.values()) {
            hooks.on(point, invocation -> {
                points.add(invocation.point());
                held.add(lockedRows());
            });
        }

        assertThat(result(submit("a", req().build()))).isInstanceOf(Created.class);

        assertThat(points).containsExactly(Point.values());
        assertThat(held).containsExactly(
                Set.of("business"),
                Set.of("business", "staff"),
                Set.of("business", "staff", "revision"),
                Set.of("business", "revision", "service", "staff"),
                Set.of("business", "revision", "service", "staff"),
                Set.of("business", "revision", "service", "staff"));
    }

    @Test
    void withoutPreferenceEveryQualifiedStaffMemberIsLockedBeforeTheRevisionGuardInIdentifierOrder() {
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), second, "09:00", "12:00");
        List<Set<UUID>> lockedStaff = new ArrayList<>();
        hooks.on(Point.BEFORE_GUARD, invocation -> lockedStaff.add(async(() -> {
            Set<UUID> locked = new TreeSet<>();
            for (UUID id : List.of(tenant.staff(), second)) {
                if (jdbc.sql("SELECT id FROM staff_member WHERE id = :id FOR UPDATE SKIP LOCKED")
                        .param("id", id).query(UUID.class).list().isEmpty()) {
                    locked.add(id);
                }
            }
            return locked;
        }).join()));

        assertThat(result(submit("a", req().build()))).isInstanceOf(Created.class);

        assertThat(lockedStaff).containsExactly(new TreeSet<>(List.of(tenant.staff(), second)));
    }

    @Test
    void concurrentBookingsNeverBlockEachOther() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(Point.AFTER_CUSTOMER, "a", reached, release);
        CompletableFuture<BookingResult> first = submit("a", req().start("09:00").build());
        await(reached, "booking a never reached the customer step");

        // Booking a is open, holding its shared locks and its Customer insert, and b completes.
        BookingResult second = completed(submit("b", req().start("10:30").build()));

        assertThat(second).isInstanceOf(Created.class);
        assertThat(first).isNotDone();
        assertNotBlocked(jdbc, pidOf("a"));
        release.countDown();
        assertThat(completed(first)).isInstanceOf(Created.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
        assertThat(customerCount(tenant.business())).isEqualTo(2);
    }

    // ---- competing bookings and the exclusion constraint ------------------------------

    @Test
    void twoBookingsOfTheSameTimeLeaveExactlyOneAndNoOrphanCustomerForTheLoser() {
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<BookingResult> winner = submit("a", req().staff(tenant.staff()).build());
        await(committing, "booking a never reached its commit");
        // A holds its inserted, uncommitted Appointment and Customer. B validated against its own
        // snapshot (which cannot see A) and now races to insert the same time.
        CompletableFuture<BookingResult> loser = submit("b", req().staff(tenant.staff()).build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));

        assertThat(loser).isNotDone();
        release.countDown();

        assertThat(completed(winner)).isInstanceOf(Created.class);
        // B's insert failed with the exclusion violation, the whole transaction rolled back, and the
        // retry in a completely new transaction no longer finds the time offered.
        assertThat(completed(loser)).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("b")))
                .containsExactly(threadOf("b") + ":rollback", threadOf("b") + ":rollback");
        assertDistinctTransactionsAndSnapshots("b", 2);
    }

    @Test
    void aBookingWithoutPreferenceMovesToTheOtherStaffMemberAfterLosingTheOverlapRace() {
        UUID second = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), second, "09:00", "12:00");
        UUID first = tenant.staff().compareTo(second) < 0 ? tenant.staff() : second;
        UUID other = first.equals(tenant.staff()) ? second : tenant.staff();
        jdbc.sql("UPDATE staff_member SET created_at = :at, updated_at = :at WHERE id = :id")
                .param("at", utc(java.time.Instant.parse("2026-01-02T00:00:00Z"))).param("id", first).update();
        jdbc.sql("UPDATE staff_member SET created_at = :at, updated_at = :at WHERE id = :id")
                .param("at", utc(java.time.Instant.parse("2026-01-03T00:00:00Z"))).param("id", other).update();
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        // A takes the earlier-created member explicitly; B ("no preference") is assigned the same
        // member from its snapshot, collides, and on its retry is assigned the other one.
        CompletableFuture<BookingResult> explicit = submit("a", req().staff(first).build());
        await(committing, "booking a never reached its commit");
        CompletableFuture<BookingResult> anyone = submit("b", req().build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));
        release.countDown();

        assertThat(completed(explicit)).isInstanceOf(Created.class);
        BookingResult retried = completed(anyone);

        assertThat(retried).isInstanceOf(Created.class);
        assertThat(appointmentRow(((Created) retried).appointment().reference()).staffMemberId()).isEqualTo(other);
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
    }

    // ---- idempotency races ------------------------------------------------------------

    @Test
    void twoSimultaneousIdenticalAttemptsCreateOneAppointmentAndOneCustomerAndReplayTheWinner() {
        Req request = req().staff(tenant.staff());
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<BookingResult> first = submit("a", request.build());
        await(committing, "booking a never reached its commit");
        // The same attempt and payload arrive again: neither sees the other's uncommitted rows, so
        // B proceeds and blocks on A's uncommitted Customer insert (the same phone).
        CompletableFuture<BookingResult> second = submit("b", request.copy().build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));
        release.countDown();

        BookingResult created = completed(first);
        BookingResult replayed = completed(second);

        assertThat(created).isInstanceOf(Created.class);
        assertThat(replayed).isEqualTo(new Replayed(((Created) created).appointment()));
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
    }

    @Test
    void simultaneousAttemptsWithTheSameIdentifierButDifferentContactEndInOneSuccessAndOneMismatchWithoutAnOrphanCustomer() {
        Req request = req().staff(tenant.staff());
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<BookingResult> first = submit("a", request.build());
        await(committing, "booking a never reached its commit");
        // B has its own new Customer (another phone), then blocks on A's Appointment insert.
        CompletableFuture<BookingResult> second = submit("b", request.copy().phone(freshPhone()).build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));
        release.countDown();

        assertThat(completed(first)).isInstanceOf(Created.class);
        assertThat(completed(second)).isEqualTo(new AttemptMismatch());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        // B's Customer insert was rolled back with its failed transaction.
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void anAttemptUniquenessRaceWithoutAnOverlapIsResolvedByTheReplayLookupOfTheRetry() {
        Req request = req().staff(tenant.staff()).start("10:00");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<BookingResult> first = submit("a", request.build());
        await(committing, "booking a never reached its commit");
        // The same attempt identifier for another time of the same StaffMember: the times do not
        // overlap, so only the unique attempt hash can reject B's insert.
        CompletableFuture<BookingResult> second = submit(
                "b", request.copy().start("11:00").phone(freshPhone()).build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));
        release.countDown();

        assertThat(completed(first)).isInstanceOf(Created.class);
        assertThat(completed(second)).isEqualTo(new AttemptMismatch());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
    }

    @Test
    void aCustomerCreatedByAConcurrentBookingAfterTheSnapshotIsFoundOnTheRetry() {
        String phone = freshPhone();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        onFirst(Point.BEFORE_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("b"))) {
                reached.countDown();
                await(release, "booking b was never released");
            }
        });
        CompletableFuture<BookingResult> second = submit("b", req().phone(phone).start("10:30").build());
        await(reached, "booking b never reached the customer step");

        // While B holds its snapshot, another booking creates and commits the same Customer.
        assertThat(completed(submit("a", req().phone(phone).start("09:00").build())))
                .isInstanceOf(Created.class);
        release.countDown();

        // B's Customer insert hit a row committed after its snapshot: a real concurrency failure
        // of the Customer capability, retried in a new transaction that finds the Customer.
        assertThat(completed(second)).isInstanceOf(Created.class);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
        assertThat(jdbc.sql("SELECT count(DISTINCT customer_id) FROM appointment").query(Long.class).single())
                .isEqualTo(1L);
    }

    // ---- real serialization failures through the orchestration ---------------------------

    @Test
    void aServiceChangeCommittedAfterTheSnapshotFailsTheLockAndTheRetryUsesTheNewSnapshot() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(reached, "booking never reached the staff lock");

        // The snapshot exists; a committed change of the Service now postdates it.
        jdbc.sql("UPDATE service SET price = 12.50 WHERE id = :id").param("id", tenant.service()).update();
        release.countDown();

        BookingResult result = completed(booking);

        assertThat(result).isInstanceOf(Created.class);
        // Attempt one failed at the Service lock with 40001; attempt two saw the new price.
        assertThat(((Created) result).appointment().price()).isEqualByComparingTo("12.50");
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":commit");
        assertDistinctTransactionsAndSnapshots("a", 2);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aStaffMemberChangeCommittedAfterTheSnapshotFailsTheLockAndTheRetrySnapshotsTheNewName() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().staff(tenant.staff()).build());
        await(reached, "booking never reached the staff lock");
        jdbc.sql("UPDATE staff_member SET display_name = 'Преименувана Мария' WHERE id = :id")
                .param("id", tenant.staff()).update();
        release.countDown();

        BookingResult result = completed(booking);

        assertThat(((Created) result).appointment().staffDisplayName()).isEqualTo("Преименувана Мария");
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
    }

    @Test
    void aStaffMemberDeactivatedAfterTheSnapshotIsRejectedByTheRetryAndNothingIsWritten() {
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().staff(tenant.staff()).build());
        await(reached, "booking never reached the staff lock");
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);
        release.countDown();

        assertThat(completed(booking)).isEqualTo(new BookingResult.StaffMemberUnavailable());

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(appointmentCount(tenant.business())).isZero();
        assertThat(customerCount(tenant.business())).isZero();
    }

    @Test
    void anAssignmentRemovedAfterTheSnapshotThroughTheRealAdministrationIsRejectedByTheRetry() {
        var owner = owner(tenant.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().staff(tenant.staff()).build());
        await(reached, "booking never reached the staff lock");
        staffMembers.replaceServiceAssignments(
                owner, tenant.staff(), new ReplaceServiceAssignmentsCommand(List.of(), 0L));
        release.countDown();

        assertThat(completed(booking)).isEqualTo(new BookingResult.StaffMemberUnavailable());

        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void retriesAreBoundedAtThreeCompleteTransactionsAndExhaustionIsAKnownRollback() {
        hooks.observeTransactions(jdbc);
        AtomicInteger changes = new AtomicInteger();
        hooks.on(Point.BEFORE_STAFF_LOCK, invocation -> {
            // After every fresh snapshot, commit another Service change from a different connection.
            changes.incrementAndGet();
            async(() -> jdbc.sql("UPDATE service SET price = price + 1 WHERE id = :id")
                    .param("id", tenant.service()).update()).join();
        });

        BookingResult result = completed(submit("a", req().build()));

        assertThat(result).isEqualTo(new BookingResult.TemporarilyUnavailable());
        assertThat(changes.get()).isEqualTo(3);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(3);
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(
                threadOf("a") + ":rollback", threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertDistinctTransactionsAndSnapshots("a", 3);
        assertThat(appointmentCount(tenant.business())).isZero();
        assertThat(customerCount(tenant.business())).isZero();
        // Nothing was stored, so the same attempt identifier can simply be tried again.
        hooks.reset();
        assertThat(completed(submit("again", req().build()))).isInstanceOf(Created.class);
    }

    // ---- real deadlock through the orchestration ------------------------------------------

    @Test
    void aRealDeadlockAbortsTheBookingWhichRetriesInANewTransactionAndSucceeds() throws Exception {
        hooks.observeTransactions(jdbc);
        CountDownLatch bookingHolding = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        holdAt(Point.BEFORE_GUARD, "a", bookingHolding, releaseBooking);
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(bookingHolding, "booking never reached the guard");
        // The booking holds the Business and StaffMember shared locks and is paused.

        CountDownLatch holderHasService = new CountDownLatch(1);
        CountDownLatch holderMayRequestBusiness = new CountDownLatch(1);
        CountDownLatch holderHasBusiness = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        int[] holderPid = new int[1];
        CompletableFuture<Void> holder = async(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                holderPid[0] = backendPid(jdbc);
                // PostgreSQL aborts whichever participant's deadlock check finds the cycle first, which
                // depends on timer timing, not on who waited first. A long timeout for the holder's
                // session (a superuser setting, scoped to this transaction) leaves the booking as the
                // only participant that can run the check, so the victim is deterministic.
                jdbc.sql("SET LOCAL deadlock_timeout = '60s'").update();
                jdbc.sql("SELECT 1 FROM service WHERE id = :id FOR UPDATE")
                        .param("id", tenant.service()).query().singleRow();
                holderHasService.countDown();
                await(holderMayRequestBusiness, "holder was never told to continue");
                // Conflicts with the booking's shared Business lock; granted once the booking aborts.
                jdbc.sql("SELECT 1 FROM business WHERE id = :id FOR UPDATE")
                        .param("id", tenant.business()).query().singleRow();
                holderHasBusiness.countDown();
                await(releaseHolder, "holder was never released");
            });
            return null;
        });
        await(holderHasService, "holder never locked the Service");

        // The booking resumes, takes the revision guard, and waits first for the Service row.
        releaseBooking.countDown();
        awaitBlockedBy(jdbc, pidOf("a"), holderPid[0]);
        // Now the holder requests the Business row the booking shares: the cycle completes. The
        // booking began waiting first, so its deadlock check runs first and aborts the booking (40P01).
        holderMayRequestBusiness.countDown();
        await(holderHasBusiness, "the holder was never granted the Business lock after the deadlock");

        // The retry is a completely new transaction and waits behind the holder's Business lock.
        WaitingBackend retry = awaitWaiterBlockedBy(jdbc, holderPid[0]);
        assertThat(retry.query()).containsIgnoringCase("FROM business").containsIgnoringCase("FOR SHARE");
        assertThat(booking).isNotDone();
        releaseHolder.countDown();
        completed(holder);

        BookingResult result = completed(booking);

        assertThat(result).isInstanceOf(Created.class);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":commit");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    // ---- administrative changes wait for the booking's shared locks ---------------------

    @Test
    void aBusinessSuspensionWaitsForTheOpenBookingWhichCommitsFirst() {
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(reached, "booking never reached the staff lock");

        CompletableFuture<Void> suspension = async(() -> {
            businesses.suspendActive(tenant.business(), 0L);
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(suspension).isNotDone();
        release.countDown();

        assertThat(completed(booking)).isInstanceOf(Created.class);
        completed(suspension);
        assertThat(jdbc.sql("SELECT status FROM business WHERE id = :id").param("id", tenant.business())
                .query(String.class).single()).isEqualTo("SUSPENDED");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(result(submit("next", req().start("10:30").build()))).isEqualTo(new BookingResult.BusinessUnavailable());
    }

    @Test
    void aServiceDeactivationWaitsForTheBookingThatHoldsTheServiceLock() {
        hooks.observeTransactions(jdbc);
        var owner = owner(tenant.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_AVAILABILITY, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().build());
        await(reached, "booking never reached availability");

        CompletableFuture<Void> deactivation = async(() -> {
            services.deactivate(owner, tenant.service(), new ServiceVersionCommand(0L));
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(deactivation).isNotDone();
        release.countDown();

        assertThat(completed(booking)).isInstanceOf(Created.class);
        completed(deactivation);
        assertThat(result(submit("next", req().start("10:30").build()))).isEqualTo(new BookingResult.ServiceUnavailable());
    }

    @Test
    void aStaffMemberDeactivationWaitsForTheBookingThatHoldsTheStaffLock() {
        hooks.observeTransactions(jdbc);
        var owner = owner(tenant.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_GUARD, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().staff(tenant.staff()).build());
        await(reached, "booking never reached the guard");

        CompletableFuture<Void> deactivation = async(() -> {
            staffMembers.deactivate(owner, tenant.staff(), new StaffMemberVersionCommand(0L));
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(deactivation).isNotDone();
        release.countDown();

        assertThat(completed(booking)).isInstanceOf(Created.class);
        completed(deactivation);
        assertThat(result(submit("next", req().staff(tenant.staff()).start("10:30").build())))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
    }

    @Test
    void anAssignmentReplacementWaitsForTheBookingThatHoldsTheStaffLock() {
        hooks.observeTransactions(jdbc);
        var owner = owner(tenant.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_GUARD, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", req().staff(tenant.staff()).build());
        await(reached, "booking never reached the guard");

        CompletableFuture<Void> replacement = async(() -> {
            staffMembers.replaceServiceAssignments(
                    owner, tenant.staff(), new ReplaceServiceAssignmentsCommand(List.of(), 0L));
            return null;
        });
        awaitBlockedBy(jdbc, backendOfWaiter(pidOf("a")), pidOf("a"));
        assertThat(replacement).isNotDone();
        release.countDown();

        assertThat(completed(booking)).isInstanceOf(Created.class);
        completed(replacement);
        assertThat(result(submit("next", req().staff(tenant.staff()).start("10:30").build())))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
    }

    /** The one backend (not the observer) that waits for a lock held by {@code holder}. */
    private int backendOfWaiter(int holder) {
        return awaitWaiterBlockedBy(jdbc, holder).pid();
    }

    // ---- bursts -----------------------------------------------------------------------------

    @Test
    void manyBookingsOfDifferentTimesAllSucceed() {
        List<CompletableFuture<BookingResult>> futures = new ArrayList<>();
        String[] starts = {"09:00", "09:30", "10:00", "10:30", "11:00", "11:30"};
        for (int index = 0; index < starts.length; index++) {
            futures.add(submit("m" + index, req().start(starts[index]).build()));
        }

        for (CompletableFuture<BookingResult> future : futures) {
            assertThat(completed(future)).isInstanceOf(Created.class);
        }
        assertThat(appointmentCount(tenant.business())).isEqualTo(6);
        assertThat(customerCount(tenant.business())).isEqualTo(6);
    }

    @Test
    void manyBookingsOfTheSameTimeLeaveExactlyOneAppointmentAndNoOrphanCustomers() {
        List<CompletableFuture<BookingResult>> futures = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            futures.add(submit("s" + index, req().start("10:00").build()));
        }

        int created = 0;
        for (CompletableFuture<BookingResult> future : futures) {
            BookingResult result = completed(future);
            if (result instanceof Created) {
                created++;
            } else {
                // The losers are told the time is taken or, when PostgreSQL resolved their
                // simultaneous inserts into the exclusion index by deadlock victims on every one of
                // the three bounded attempts, that the booking was not made (observed with six
                // simultaneous contenders). Both are known rollbacks that leave no data behind.
                assertThat(result).isIn(
                        new BookingResult.SlotUnavailable(), new BookingResult.TemporarilyUnavailable());
            }
        }

        assertThat(created).isEqualTo(1);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    // ---- assertions ----------------------------------------------------------------------------

    private void assertDistinctTransactionsAndSnapshots(String label, int attempts) {
        List<TransactionObservation> firstPoint = hooks.observationsOf(threadOf(label)).stream()
                .filter(observation -> observation.point() == Point.BEFORE_STAFF_LOCK)
                .toList();
        assertThat(firstPoint).hasSize(attempts);
        assertThat(firstPoint.stream().map(TransactionObservation::transactionId).distinct().count())
                .as("distinct PostgreSQL transaction identifiers").isEqualTo(attempts);
        assertThat(firstPoint.stream().map(TransactionObservation::snapshot).distinct().count())
                .as("fresh snapshots").isEqualTo(attempts);
        for (int index = 1; index < attempts; index++) {
            assertThat(firstPoint.get(index).transactionId()).isGreaterThan(firstPoint.get(index - 1).transactionId());
        }
    }
}
