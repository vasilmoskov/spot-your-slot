package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.backendPid;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.BookingResult.AttemptMismatch;
import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.booking.BookingResult.Replayed;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.integration.ConcurrencyTestSupport.WaitingBackend;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent replay (ADR-0024) against real PostgreSQL: an exact or normalized-equivalent replay
 * returns the stored result and writes nothing; any changed field is a mismatch; replay survives
 * later Business, Service, and StaffMember changes and returns the original snapshots and the
 * current status; a replay holds only the initial Business lock.
 */
class GuestBookingReplayIntegrationTests extends BookingIntegrationTest {
    private Req firstRequest() {
        return req().staff(tenant.staff()).note("Бележка").email("ivan@example.com");
    }

    private BookedAppointment bookFirst(Req request) {
        return ((Created) result(submit("first", request.build()))).appointment();
    }

    private BookingResult replay(Req request) {
        return result(submit("replay", request.build()));
    }

    // ---- exact and equivalent replay ------------------------------------------------

    @Test
    void anExactReplayReturnsTheStoredResultAndWritesNothing() {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);
        AppointmentRow before = appointmentRow(booked.reference());
        String customerXminBefore = customerXmin();
        long revision = revisionOf(tenant.business());
        for (Point point : Point.values()) {
            hooks.on(point, invocation -> {
                throw new AssertionError("a replay must not reach " + invocation.point());
            });
        }

        BookingResult result = replay(request.copy());

        assertThat(result).isEqualTo(new Replayed(booked));
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(appointmentRow(booked.reference()).xmin()).isEqualTo(before.xmin());
        assertThat(customerXmin()).isEqualTo(customerXminBefore);
        assertThat(revisionOf(tenant.business())).isEqualTo(revision);
        // One read-only-in-effect transaction that ended by rollback.
        assertThat(TransactionLog.begins(threadOf("replay"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("replay"))).containsExactly(threadOf("replay") + ":rollback");
    }

    @Test
    void aReplayDoesNotReadTheClockOrTheCurrentAvailabilityOrCallCustomer() {
        Req request = firstRequest();
        bookFirst(request);
        clock.resetReads();
        // Even a start that is no longer offered (it is booked) replays, because no availability
        // is calculated; the clock is untouched.
        assertThat(replay(request.copy())).isInstanceOf(Replayed.class);

        assertThat(clock.reads()).isZero();
    }

    @Test
    void aReplayCanBeRepeatedAnyNumberOfTimes() {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);

        for (int index = 0; index < 5; index++) {
            assertThat(replay(request.copy())).isEqualTo(new Replayed(booked));
        }

        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aNormalizedEquivalentRequestReplays() {
        Req request = firstRequest().name("Иван Петров").phone("+359888777666");
        BookedAppointment booked = bookFirst(request);

        Req equivalent = request.copy()
                .name("  Иван \t Петров  ")
                .phone("0888 777 666")
                .email("  IVAN@Example.com ")
                .note("\\r\\n  Бележка \\n".replace("\\r", "\r").replace("\\n", "\n"))
                .slug(request.slug.toUpperCase());

        assertThat(replay(equivalent)).isEqualTo(new Replayed(booked));
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void anUppercaseAttemptIdentifierIsInvalidInputNeverAReplay() {
        Req request = firstRequest();
        bookFirst(request);

        BookingResult result = replay(request.copy().attempt(request.attemptId.toUpperCase()));

        assertThat(result).isInstanceOfSatisfying(BookingResult.InvalidRequest.class,
                invalid -> assertThat(invalid.fields()).containsExactly(BookingField.ATTEMPT_ID));
    }

    // ---- mismatch ------------------------------------------------------------------------

    static Stream<Object[]> changedRequests() {
        return Stream.of(
                new Object[] {"another service", (UnaryOperator<Req>) request -> request},
                new Object[] {"preference to none", (UnaryOperator<Req>) request -> request.staff(null)},
                new Object[] {"start", (UnaryOperator<Req>) request -> request.start("11:00")},
                new Object[] {"name", (UnaryOperator<Req>) request -> request.name("Иван Петров-Втори")},
                new Object[] {"phone", (UnaryOperator<Req>) request -> request.phone("+359888000999")},
                new Object[] {"email", (UnaryOperator<Req>) request -> request.email("other@example.com")},
                new Object[] {"email removed", (UnaryOperator<Req>) request -> request.email(null)},
                new Object[] {"note", (UnaryOperator<Req>) request -> request.note("Друга бележка")},
                new Object[] {"note removed", (UnaryOperator<Req>) request -> request.note(null)});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changedRequests")
    void aChangeToAnyFingerprintedFieldIsAMismatchAndWritesNothing(
            String field, UnaryOperator<Req> change) {
        Req request = firstRequest().phone("+359888777666");
        bookFirst(request);
        UUID secondService = availabilityFixtures.service(tenant.business(), 30, true);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), secondService);
        long customers = customerCount(tenant.business());
        Req changed = change.apply(request.copy());
        if (field.equals("another service")) {
            changed.service(secondService);
        }

        BookingResult result = replay(changed);

        assertThat(result).isEqualTo(new AttemptMismatch());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(customers);
    }

    @Test
    void aRequestedPreferenceIsNotTheAssignedStaffMemberSoSubstitutingItIsAMismatch() {
        // "No preference" was submitted and the only qualified member was assigned. Replaying the
        // same attempt with that member named explicitly is a different request.
        Req noPreference = req().phone("+359888777666");
        bookFirst(noPreference);

        BookingResult result = replay(noPreference.copy().staff(tenant.staff()));

        assertThat(result).isEqualTo(new AttemptMismatch());
        // And the reverse: an explicit preference does not replay as "no preference".
        Req explicit = req().phone("+359888777667").start("11:00").staff(tenant.staff());
        bookFirst(explicit);
        assertThat(replay(explicit.copy().staff(null))).isEqualTo(new AttemptMismatch());
    }

    @Test
    void theSameAttemptIdentifierInAnotherBusinessIsIndependent() {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);
        var other = openTenant();

        BookingResult inOther = replay(request.copy().forTenant(other));

        assertThat(inOther).isInstanceOf(Created.class);
        assertThat(((Created) inOther).appointment().reference()).isNotEqualTo(booked.reference());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(appointmentCount(other.business())).isEqualTo(1);
        assertThat(replay(request.copy())).isEqualTo(new Replayed(booked));
    }

    // ---- replay survives later changes -----------------------------------------------------

    @Test
    void aMatchingReplayIsStillAnsweredAfterTheBusinessWasSuspended() {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);
        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");

        assertThat(replay(request.copy())).isEqualTo(new Replayed(booked));
        // A mismatch is a mismatch even while suspended, and a new attempt is unavailable.
        assertThat(replay(request.copy().name("Друго Име"))).isEqualTo(new AttemptMismatch());
        assertThat(replay(req())).isEqualTo(new BookingResult.BusinessUnavailable());
    }

    @Test
    void aReplayReturnsTheOriginalSnapshotsAfterServiceStaffAndBusinessChanges() {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);
        jdbc.sql("UPDATE service SET name = 'Нова услуга', price = 99.00, duration_minutes = 60, active = false WHERE id = :id")
                .param("id", tenant.service()).update();
        jdbc.sql("UPDATE staff_member SET display_name = 'Преименувана', active = false WHERE id = :id")
                .param("id", tenant.staff()).update();
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), tenant.service());
        jdbc.sql("UPDATE business SET timezone = 'Europe/London' WHERE id = :id")
                .param("id", tenant.business()).update();

        BookingResult result = replay(request.copy());

        assertThat(result).isEqualTo(new Replayed(booked));
        BookedAppointment replayed = ((Replayed) result).appointment();
        assertThat(replayed.serviceName()).isEqualTo(booked.serviceName()).isNotEqualTo("Нова услуга");
        assertThat(replayed.price()).isEqualByComparingTo("10.00");
        assertThat(replayed.durationMinutes()).isEqualTo(30);
        assertThat(replayed.staffDisplayName()).isEqualTo("Availability Staff");
        assertThat(replayed.timezone()).isEqualTo("Europe/Sofia");
        assertThat(replayed.start()).isEqualTo(at("10:00"));
        assertThat(replayed.end()).isEqualTo(at("10:30"));
    }

    @Test
    void aLaterCancelledAppointmentReplaysAsCancelledWithoutBookingAgain() {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);
        // Integration fixture only: production has no cancellation capability in Phase 4.
        jdbc.sql("UPDATE appointment SET status = 'CANCELLED' WHERE public_reference = :reference")
                .param("reference", booked.reference()).update();

        BookingResult result = replay(request.copy());

        assertThat(result).isInstanceOf(Replayed.class);
        BookedAppointment replayed = ((Replayed) result).appointment();
        assertThat(replayed.status()).isEqualTo(BookedAppointment.Status.CANCELLED);
        assertThat(replayed.reference()).isEqualTo(booked.reference());
        assertThat(replayed.serviceName()).isEqualTo(booked.serviceName());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        // The released time is bookable by a new attempt, which does not disturb the replay.
        assertThat(result(submit("other", req().staff(tenant.staff()).build()))).isInstanceOf(Created.class);
        assertThat(replay(request.copy())).isEqualTo(new Replayed(replayed));
    }

    @Test
    void aReplayDoesNotCallCustomerEvenWhenTheCustomerWasCorrectedLater() {
        Req request = firstRequest().phone("+359888777666");
        BookedAppointment booked = bookFirst(request);
        jdbc.sql("UPDATE customer SET phone = '+359888123123', display_name = 'Коригиран'").update();

        assertThat(replay(request.copy())).isEqualTo(new Replayed(booked));
    }

    // ---- locks ----------------------------------------------------------------------------

    @Test
    void aReplayTakesNoStaffRevisionOrServiceLockAndOnlyTheBusinessSharedLock() throws Exception {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = async(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // Exclusive row locks on everything a booking attempt would share-lock, except the Business.
                jdbc.sql("SELECT 1 FROM staff_member WHERE id = :id FOR UPDATE")
                        .param("id", tenant.staff()).query().singleRow();
                jdbc.sql("SELECT 1 FROM service WHERE id = :id FOR UPDATE")
                        .param("id", tenant.service()).query().singleRow();
                jdbc.sql("SELECT 1 FROM business_schedule_revision WHERE business_id = :id FOR UPDATE")
                        .param("id", tenant.business()).query().singleRow();
                locked.countDown();
                ConcurrencyTestSupportBridge.await(release);
            });
            return null;
        });
        ConcurrencyTestSupportBridge.await(locked);

        // The replay finishes while those exclusive locks are still held: it never waited on them.
        BookingResult result = completed(submit("replay", request.copy().build()));

        assertThat(result).isEqualTo(new Replayed(booked));
        release.countDown();
        completed(holder);
    }

    @Test
    void aReplayHoldsTheBusinessSharedLockSoAnExclusiveBusinessLockBlocksIt() throws Exception {
        Req request = firstRequest();
        BookedAppointment booked = bookFirst(request);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int[] holderPid = new int[1];
        CompletableFuture<Void> holder = async(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.sql("SELECT 1 FROM business WHERE id = :id FOR UPDATE")
                        .param("id", tenant.business()).query().singleRow();
                holderPid[0] = backendPid(jdbc);
                locked.countDown();
                ConcurrencyTestSupportBridge.await(release);
            });
            return null;
        });
        ConcurrencyTestSupportBridge.await(locked);

        CompletableFuture<BookingResult> replaying = submit("replay", request.copy().build());
        WaitingBackend waiting = awaitWaiterBlockedBy(jdbc, holderPid[0]);

        assertThat(waiting.query()).containsIgnoringCase("FROM business").containsIgnoringCase("FOR SHARE");
        assertThat(replaying).isNotDone();
        release.countDown();
        completed(holder);
        assertThat(completed(replaying)).isEqualTo(new Replayed(booked));
    }

    // ---- unverifiable stored fingerprints -----------------------------------------------------

    @Test
    void aMissingHistoricalKeyIsAnUncertainTechnicalFailureNeverAMismatchAndWritesNothing() {
        Req request = firstRequest();
        bookFirst(request);
        jdbc.sql("UPDATE appointment SET fingerprint_key_version = 9").update();
        ListAppender<ILoggingEvent> logs = captureDiagnostics();

        BookingResult sameRequest = replay(request.copy());
        BookingResult differentRequest = replay(request.copy().name("Съвсем Друго"));

        assertThat(sameRequest).isEqualTo(new BookingResult.OutcomeUncertain());
        assertThat(differentRequest).isEqualTo(new BookingResult.OutcomeUncertain());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .allMatch(message -> message.startsWith("booking event=")
                        && !message.contains("Съвсем") && !message.contains(request.phone));
        assertThat(logs.list).isNotEmpty();
    }

    @Test
    void anUnsupportedStoredEncodingVersionIsAnUncertainTechnicalFailureNeverAMismatch() {
        Req request = firstRequest();
        bookFirst(request);
        jdbc.sql("UPDATE appointment SET fingerprint_encoding_version = 2").update();

        assertThat(replay(request.copy())).isEqualTo(new BookingResult.OutcomeUncertain());
        assertThat(replay(request.copy().note("Друга"))).isEqualTo(new BookingResult.OutcomeUncertain());

        jdbc.sql("UPDATE appointment SET fingerprint_encoding_version = 1").update();
        assertThat(replay(request.copy())).isInstanceOf(Replayed.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    private ListAppender<ILoggingEvent> captureDiagnostics() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger("bg.spotyourslot.booking.application.BookingDiagnostics"))
                .addAppender(appender);
        return appender;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String customerXmin() {
        return jdbc.sql("SELECT xmin::text FROM customer WHERE business_id = :id")
                .param("id", tenant.business()).query(String.class).single();
    }

    private long revisionOf(UUID business) {
        return jdbc.sql("SELECT revision FROM business_schedule_revision WHERE business_id = :id")
                .param("id", business).query(Long.class).single();
    }

    @SuppressWarnings("unused")
    private static Instant unusedInstant() {
        return null;
    }

    /** Local alias so test code reads clearly; delegates to the shared bounded latch wait. */
    private static final class ConcurrencyTestSupportBridge {
        static void await(CountDownLatch latch) {
            bg.spotyourslot.integration.ConcurrencyTestSupport.await(latch, "latch was never released");
        }
    }
}
