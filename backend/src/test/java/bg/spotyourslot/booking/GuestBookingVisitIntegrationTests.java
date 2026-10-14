package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.await;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.awaitWaiterBlockedBy;
import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.BookedAppointment.BookedService;
import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.booking.BookingResult.Replayed;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.booking.BookingTestHooks.TransactionObservation;
import bg.spotyourslot.booking.GuestBookingRequest.ReviewedService;
import bg.spotyourslot.catalog.ServiceAdministration;
import bg.spotyourslot.catalog.ServiceRecords.ServiceVersionCommand;
import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import bg.spotyourslot.workforce.StaffMemberAdministration;
import bg.spotyourslot.workforce.StaffMemberRecords.ReplaceServiceAssignmentsCommand;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole-visit orchestration of ADR-0027 (phase M3) with real PostgreSQL: normalization,
 * encoding-2 replay, the locked-fact snapshots, the review-consistency check, ordered locks, atomic
 * persistence, and the races. Interleavings are ordered by latches and PostgreSQL lock-wait
 * evidence; nothing sleeps. The clock is Tuesday 2026-09-29 11:00 in Sofia and every slot is on
 * Thursday 2026-10-01.
 *
 * <p>Evidence kinds are kept apart: everything here is produced by real PostgreSQL behavior except
 * the tests under "injected faults", which install a test-only trigger to make a statement fail.
 */
class GuestBookingVisitIntegrationTests extends BookingIntegrationTest {
    private static final UUID LOW = UUID.fromString("7fffffff-ffff-4fff-bfff-ffffffffffff");
    private static final UUID HIGH = UUID.fromString("80000000-0000-4000-8000-000000000000");

    @Autowired DataSource dataSource;
    @Autowired ServiceAdministration services;
    @Autowired StaffMemberAdministration staffMembers;
    @Autowired ScheduleExceptionAdministration exceptions;

    // ---- fixtures ---------------------------------------------------------------------------

    /** An active Service of the tenant's Business, assigned to the tenant's StaffMember. */
    private UUID svc(String name, int minutes, String price) {
        UUID id = fixtures.service(tenant.business(), minutes);
        jdbc.sql("UPDATE service SET name = :name, price = CAST(:price AS numeric) WHERE id = :id")
                .param("name", name).param("price", price).param("id", id).update();
        availabilityFixtures.assign(tenant.business(), tenant.staff(), id);
        return id;
    }

    private void rename(UUID service, String name, int minutes, String price) {
        jdbc.sql("""
                        UPDATE service SET name = :name, duration_minutes = :minutes,
                               price = CAST(:price AS numeric)
                        WHERE id = :id
                        """)
                .param("name", name).param("minutes", minutes).param("price", price)
                .param("id", service).update();
    }

    /** The reviewed terms a guest would have seen: the current committed facts of the Service. */
    private ReviewedService seen(UUID service) {
        return jdbc.sql("SELECT duration_minutes, price FROM service WHERE id = :id")
                .param("id", service)
                .query((resultSet, row) -> new ReviewedService(
                        service,
                        resultSet.getInt("duration_minutes"),
                        resultSet.getBigDecimal("price").movePointRight(2).longValueExact()))
                .single();
    }

    private final class Visit {
        String slug = slugOf(tenant.business());
        String attemptId = UUID.randomUUID().toString();
        UUID staff;
        Instant start = at("10:00");
        String name = "Иван Петров";
        String phone = freshPhone();
        String note;
        List<ReviewedService> selected = new ArrayList<>();

        Visit of(UUID... serviceIds) {
            selected = new ArrayList<>();
            for (UUID id : serviceIds) {
                selected.add(seen(id));
            }
            return this;
        }

        Visit claims(ReviewedService... claims) {
            selected = new ArrayList<>(List.of(claims));
            return this;
        }

        Visit permuted() {
            Collections.reverse(selected);
            return this;
        }

        Visit staff(UUID value) {
            staff = value;
            return this;
        }

        Visit start(String time) {
            start = at(time);
            return this;
        }

        Visit note(String value) {
            note = value;
            return this;
        }

        Visit phone(String value) {
            phone = value;
            return this;
        }

        GuestBookingRequest build() {
            return new GuestBookingRequest(
                    slug, attemptId, null, staff, start, name, phone, null, note, selected);
        }

        Visit copy() {
            Visit copy = new Visit();
            copy.slug = slug;
            copy.attemptId = attemptId;
            copy.staff = staff;
            copy.start = start;
            copy.name = name;
            copy.phone = phone;
            copy.note = note;
            copy.selected = new ArrayList<>(selected);
            return copy;
        }
    }

    private Visit visit(UUID... serviceIds) {
        return new Visit().of(serviceIds);
    }

    private long lineCount() {
        return jdbc.sql("SELECT count(*) FROM appointment_service").query(Long.class).single();
    }

    private int pidOf(String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<TransactionObservation> mine = hooks.observationsOf(threadOf(label));
            if (!mine.isEmpty()) {
                return mine.get(0).backendPid();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("booking " + label + " never reached an observed point");
    }

    private void holdAt(Point point, String label, CountDownLatch reached, CountDownLatch release) {
        hooks.on(point, invocation -> {
            if (invocation.thread().equals(threadOf(label)) && invocation.ordinal() == 1) {
                reached.countDown();
                await(release, "booking " + label + " was never released at " + point);
            }
        });
    }

    private List<String> diagnostics(Runnable action) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger("bg.spotyourslot.booking.application.BookingDiagnostics");
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---- a created visit ------------------------------------------------------------------------

    @Test
    void aTwoServiceVisitIsOneAppointmentWithLinesInProfileOrderAndSummedTotals() {
        UUID late = svc("Zulu", 30, "10.00");
        UUID early = svc("Alpha", 45, "20.50");

        // The request lists the Services in the opposite of the profile order.
        BookingResult result = book(visit(late, early).staff(tenant.staff()).build());

        BookedAppointment booked = ((Created) result).appointment();
        assertThat(booked.services()).containsExactly(
                new BookedService("Alpha", 45, new BigDecimal("20.50")),
                new BookedService("Zulu", 30, new BigDecimal("10.00")));
        assertThat(booked.serviceName()).isEqualTo("Alpha");
        assertThat(booked.durationMinutes()).isEqualTo(75);
        assertThat(booked.price()).isEqualTo(new BigDecimal("30.50"));
        assertThat(booked.start()).isEqualTo(at("10:00"));
        assertThat(booked.end()).isEqualTo(at("11:15"));
        AppointmentRow row = appointmentRow(booked.reference());
        assertThat(row.serviceId()).isEqualTo(early);
        assertThat(row.serviceName()).isEqualTo("Alpha");
        assertThat(row.durationMinutes()).isEqualTo(75);
        assertThat(row.price()).isEqualByComparingTo("30.50");
        assertThat(row.encodingVersion()).isEqualTo(2);
        assertThat(row.occupiedUntil()).isEqualTo(at("11:15"));
        assertThat(jdbc.sql("""
                        SELECT position || ':' || service_id || ':' || service_name || ':' || duration_minutes
                        FROM appointment_service ORDER BY position
                        """).query(String.class).list())
                .containsExactly("1:" + early + ":Alpha:45", "2:" + late + ":Zulu:30");
        assertThat(jdbc.sql("SELECT service_count FROM appointment").query(Integer.class).single()).isEqualTo(2);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void theSnapshotsComeFromTheLockedRowsNotFromTheRequest() {
        UUID first = svc("Alpha", 45, "20.50");
        UUID second = svc("Zulu", 30, "10.00");

        BookedAppointment booked = ((Created) book(visit(first, second).build())).appointment();

        assertThat(booked.services()).extracting(BookedService::name).containsExactly("Alpha", "Zulu");
        assertThat(lineCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT sum(price_eur) FROM appointment_service").query(BigDecimal.class).single())
                .isEqualByComparingTo(booked.price());
    }

    @Test
    void aSingleServiceInTheNewShapeCreatesOneLineWithEncodingTwo() {
        BookedAppointment booked = ((Created) book(visit(tenant.service()).build())).appointment();

        assertThat(booked.services()).hasSize(1);
        assertThat(appointmentRow(booked.reference()).encodingVersion()).isEqualTo(2);
        assertThat(lineCount()).isEqualTo(1);
    }

    @Test
    void theLegacyShapeStillCreatesOneLineWithEncodingTwoAndIsNotReviewChecked() {
        // The terms changed since the guest looked, but a legacy request carries no claims.
        rename(tenant.service(), "Repriced", 30, "99.00");

        BookedAppointment booked = ((Created) book(req().build())).appointment();

        assertThat(booked.price()).isEqualByComparingTo("99.00");
        assertThat(booked.services()).containsExactly(new BookedService("Repriced", 30, new BigDecimal("99.00")));
        assertThat(appointmentRow(booked.reference()).encodingVersion()).isEqualTo(2);
        assertThat(lineCount()).isEqualTo(1);
    }

    @Test
    void anExactMaximumPriceVisitOfFiveServicesCommitsWithExactTotalsAndReplaysExactly() {
        UUID staff = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), staff, "00:00", "23:59");
        List<UUID> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            UUID service = fixtures.service(tenant.business(), 96);
            jdbc.sql("UPDATE service SET name = :name, price = 9999999999.99 WHERE id = :id")
                    .param("name", "Premium " + index).param("id", service).update();
            availabilityFixtures.assign(tenant.business(), staff, service);
            five.add(service);
        }
        Visit visit = visit(five.toArray(UUID[]::new)).staff(staff).start("08:00");
        assertThat(visit.selected).allSatisfy(claim -> {
            assertThat(claim.priceCents()).isEqualTo(999_999_999_999L);
            assertThat(claim.durationMinutes()).isEqualTo(96);
        });

        BookedAppointment booked = ((Created) book(visit.build())).appointment();

        assertThat(booked.durationMinutes()).isEqualTo(480);
        assertThat(booked.price()).isEqualTo(new BigDecimal("49999999999.95"));
        assertThat(booked.end()).isEqualTo(at("16:00"));
        assertThat(booked.services()).hasSize(5);
        assertThat(appointmentRow(booked.reference()).price()).isEqualTo(new BigDecimal("49999999999.95"));
        assertThat(book(visit.copy().build())).isEqualTo(new Replayed(booked));
    }

    // ---- replay and compatibility --------------------------------------------------------------

    @Test
    void thePermutedSelectionReplaysAndAnyChangedFactOrFieldIsAMismatch() {
        UUID first = svc("Alpha", 45, "20.50");
        UUID second = svc("Zulu", 30, "10.00");
        Visit original = visit(first, second);
        BookedAppointment booked = ((Created) book(original.build())).appointment();

        Visit permuted = original.copy();
        Collections.reverse(permuted.selected);
        Visit otherDuration = original.copy().claims(
                new ReviewedService(first, 46, 2050), seen(second));
        Visit otherPrice = original.copy().claims(
                seen(first), new ReviewedService(second, 30, 1001));
        Visit fewer = original.copy().claims(seen(first));

        assertThat(book(permuted.build())).isEqualTo(new Replayed(booked));
        assertThat(book(otherDuration.build())).isEqualTo(new BookingResult.AttemptMismatch());
        assertThat(book(otherPrice.build())).isEqualTo(new BookingResult.AttemptMismatch());
        assertThat(book(fewer.build())).isEqualTo(new BookingResult.AttemptMismatch());
        assertThat(book(original.copy().note("друга").build())).isEqualTo(new BookingResult.AttemptMismatch());
        assertThat(book(original.copy().staff(tenant.staff()).build())).isEqualTo(new BookingResult.AttemptMismatch());
        assertThat(book(original.copy().start("10:15").build())).isEqualTo(new BookingResult.AttemptMismatch());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aVisitDoesNotReplayThroughTheLegacyShape() {
        UUID first = svc("Alpha", 45, "20.50");
        Visit original = visit(first);
        book(original.build());

        GuestBookingRequest legacy = new GuestBookingRequest(
                original.slug, original.attemptId, first, null, original.start, original.name,
                original.phone, null, null);

        // One legacy Service has no reviewed terms: a different request than the reviewed one.
        assertThat(book(legacy)).isEqualTo(new BookingResult.AttemptMismatch());
    }

    @Test
    void aReplayAfterPriceDurationNameAndActivityChangesReturnsTheStoredVisit() {
        UUID first = svc("Alpha", 45, "20.50");
        UUID second = svc("Zulu", 30, "10.00");
        Visit original = visit(first, second);
        BookedAppointment booked = ((Created) book(original.build())).appointment();

        rename(first, "Renamed Alpha", 50, "99.99");
        rename(second, "Renamed Zulu", 35, "1.00");
        availabilityFixtures.setServiceActive(first, false);

        BookingResult replay = book(original.copy().build());

        // The terms changed and the Service is inactive, yet the committed visit replays unchanged.
        assertThat(replay).isEqualTo(new Replayed(booked));
        assertThat(((Replayed) replay).appointment().services())
                .extracting(BookedService::name).containsExactly("Alpha", "Zulu");
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aCancelledVisitStillReplaysWithItsStoredLinesAndCancelledStatus() {
        UUID first = svc("Alpha", 45, "20.50");
        Visit original = visit(first, tenant.service());
        BookedAppointment booked = ((Created) book(original.build())).appointment();
        fixtures.cancelByReference(booked.reference());

        BookingResult replay = book(original.copy().build());

        BookedAppointment replayed = ((Replayed) replay).appointment();
        assertThat(replayed.status()).isEqualTo(BookedAppointment.Status.CANCELLED);
        assertThat(replayed.services()).isEqualTo(booked.services());
        assertThat(replayed.durationMinutes()).isEqualTo(booked.durationMinutes());
    }

    // ---- the review-consistency check ------------------------------------------------------------

    @Test
    void aChangedPriceOrDurationIsAReviewChangeWithNothingWrittenAndNoRetry() {
        UUID first = svc("Alpha", 45, "20.50");
        UUID second = svc("Zulu", 30, "10.00");
        Visit reviewedBefore = visit(first, second);
        List<Point> reached = Collections.synchronizedList(new ArrayList<>());
        for (Point point : Point.values()) {
            hooks.on(point, invocation -> reached.add(invocation.point()));
        }
        hooks.observeTransactions(jdbc);

        jdbc.sql("UPDATE service SET price = 10.01 WHERE id = :id").param("id", second).update();
        assertThat(book(reviewedBefore.build())).isEqualTo(new BookingResult.ReviewChanged());
        rename(second, "Zulu", 31, "10.00");
        Visit durationDrift = visit(first).claims(seen(first), new ReviewedService(second, 30, 1000));
        assertThat(book(durationDrift.build())).isEqualTo(new BookingResult.ReviewChanged());

        assertThat(appointmentCount(tenant.business())).isZero();
        assertThat(lineCount()).isZero();
        assertThat(customerCount(tenant.business())).isZero();
        // Only the three lock points were reached: never availability, the Customer, or an insert.
        assertThat(reached).doesNotContain(Point.BEFORE_AVAILABILITY, Point.BEFORE_CUSTOMER, Point.AFTER_CUSTOMER);
        assertThat(TransactionLog.begins(threadOf("main"))).hasSize(2);
        assertThat(hooks.observationsOf(threadOf("main")).stream()
                .filter(observation -> observation.point() == Point.BEFORE_STAFF_LOCK)
                .map(TransactionObservation::transactionId).distinct().count()).isEqualTo(2);
    }

    @Test
    void aReviewChangeNamesNoSubmittedValueAndLogsNothing() {
        UUID first = svc("Alpha", 45, "20.50");
        Visit request = visit(first, tenant.service()).phone("+359888765432");
        jdbc.sql("UPDATE service SET price = 21.00 WHERE id = :id").param("id", first).update();

        List<String> logs = diagnostics(() ->
                assertThat(book(request.build())).isEqualTo(new BookingResult.ReviewChanged()));

        assertThat(logs).isEmpty();
        assertThat(new BookingResult.ReviewChanged().toString()).isEqualTo("ReviewChanged[]");
        assertThat(request.build().toString()).isEqualTo("GuestBookingRequest[redacted]");
    }

    @Test
    void aChangedTermTakesPrecedenceOverAVanishedSlotAndAnInactiveServiceOverTheChange() {
        UUID first = svc("Alpha", 45, "20.50");
        jdbc.sql("UPDATE service SET price = 21.00 WHERE id = :id").param("id", first).update();

        // 13:00 is outside the working hours: the slot is not offered, but the terms are checked first.
        Visit afterHours = new Visit().claims(
                new ReviewedService(first, 45, 2050), seen(tenant.service())).start("13:00");
        assertThat(book(afterHours.build())).isEqualTo(new BookingResult.ReviewChanged());

        availabilityFixtures.setServiceActive(first, false);
        assertThat(book(afterHours.copy().build())).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void aReviewChangeIsDeterministicAndDoesNotConsumeTheAttemptSoTheRefreshedRequestSucceeds() {
        UUID first = svc("Alpha", 45, "20.50");
        Visit stale = visit(first);
        jdbc.sql("UPDATE service SET price = 21.00 WHERE id = :id").param("id", first).update();

        assertThat(book(stale.build())).isEqualTo(new BookingResult.ReviewChanged());
        assertThat(book(stale.copy().build())).isEqualTo(new BookingResult.ReviewChanged());
        Visit refreshed = visit(first);
        assertThat(book(refreshed.build())).isInstanceOf(Created.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    // ---- request validation ------------------------------------------------------------------------

    private BookingResult submitClaims(List<ReviewedService> claims) {
        return book(new GuestBookingRequest(
                slugOf(tenant.business()), UUID.randomUUID().toString(), null, null, at("10:00"),
                "Иван Петров", freshPhone(), null, null, claims));
    }

    @Test
    void invalidSelectionsAreRejectedBeforeAnyTransaction() {
        List<ReviewedService> six = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            six.add(new ReviewedService(UUID.randomUUID(), 10, 100));
        }
        ReviewedService one = new ReviewedService(tenant.service(), 30, 1000);
        BookingResult invalid = new BookingResult.InvalidRequest(java.util.EnumSet.of(BookingField.SERVICES));

        assertThat(submitClaims(List.of())).isEqualTo(invalid);
        assertThat(submitClaims(six)).isEqualTo(invalid);
        assertThat(submitClaims(List.of(one, one))).isEqualTo(invalid);
        assertThat(submitClaims(List.of(new ReviewedService(tenant.service(), 0, 1000)))).isEqualTo(invalid);
        assertThat(submitClaims(List.of(new ReviewedService(tenant.service(), 481, 1000)))).isEqualTo(invalid);
        assertThat(submitClaims(List.of(new ReviewedService(tenant.service(), 30, -1)))).isEqualTo(invalid);
        assertThat(submitClaims(List.of(new ReviewedService(tenant.service(), 30, 1_000_000_000_000L))))
                .isEqualTo(invalid);
        // Claims that sum to 481 minutes could never have been reviewed.
        assertThat(submitClaims(List.of(
                new ReviewedService(tenant.service(), 240, 1000),
                new ReviewedService(UUID.randomUUID(), 241, 1000)))).isEqualTo(invalid);
        assertThat(TransactionLog.begins(threadOf("main"))).isEmpty();
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void aRequestNamingBothShapesOrNeitherIsAProgrammingError() {
        assertThatThrownBy(() -> new GuestBookingRequest(
                "slug", "id", tenant.service(), null, at("10:00"), "N", "p", null, null,
                List.of(new ReviewedService(tenant.service(), 30, 1000))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GuestBookingRequest(
                "slug", "id", null, null, at("10:00"), "N", "p", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aMissingForeignOrInactiveServiceIsUnavailable() {
        UUID first = svc("Alpha", 45, "20.50");
        AppointmentFixtures.Tenant other = openTenant();
        Visit foreign = visit(first).claims(seen(first), seen(other.service()));
        Visit missing = visit(first).claims(seen(first), new ReviewedService(UUID.randomUUID(), 30, 1000));

        assertThat(book(foreign.build())).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(book(missing.build())).isEqualTo(new BookingResult.ServiceUnavailable());
        availabilityFixtures.setServiceActive(first, false);
        assertThat(book(visit(first, tenant.service()).build())).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(totalAppointments()).isZero();
    }

    // ---- eligibility ------------------------------------------------------------------------------------

    @Test
    void onlyAStaffMemberAssignedToEveryServiceMayBeRequestedOrAssigned() {
        UUID second = svc("Alpha", 45, "20.50");
        // tenant.staff is assigned to both. Another member performs only the tenant's Service.
        UUID partial = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), partial, "09:00", "12:00");
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.assign(tenant.business(), inactive, second);
        availabilityFixtures.everyDay(tenant.business(), inactive, "09:00", "12:00");
        availabilityFixtures.setStaffMemberActive(inactive, false);

        assertThat(book(visit(tenant.service(), second).staff(partial).build()))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
        assertThat(book(visit(tenant.service(), second).staff(inactive).build()))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
        BookedAppointment booked = ((Created) book(visit(tenant.service(), second).build())).appointment();

        assertThat(appointmentRow(booked.reference()).staffMemberId()).isEqualTo(tenant.staff());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void whenNobodySupportsTheCombinationTheOutcomeDependsOnWhetherAMemberWasRequested() {
        UUID second = svc("Alpha", 45, "20.50");
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), second);
        UUID onlySecond = fixtures.staffMember(tenant.business(), second);
        availabilityFixtures.everyDay(tenant.business(), onlySecond, "09:00", "12:00");

        // Each Service has a member; nobody performs both.
        assertThat(book(visit(tenant.service(), second).build())).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(book(visit(tenant.service(), second).staff(tenant.staff()).build()))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
        assertThat(book(visit(tenant.service(), second).staff(onlySecond).build()))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void withoutPreferenceTheExistingAssignmentRuleChoosesAmongTheEligibleMembers() {
        UUID second = svc("Alpha", 45, "20.50");
        UUID other = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.assign(tenant.business(), other, second);
        availabilityFixtures.everyDay(tenant.business(), other, "09:00", "12:00");
        // The tenant's member already holds one visit that day, so the rule prefers the other one.
        assertThat(book(visit(tenant.service()).staff(tenant.staff()).start("09:00").build()))
                .isInstanceOf(Created.class);

        BookedAppointment booked = ((Created) book(visit(tenant.service(), second).start("10:00").build()))
                .appointment();

        assertThat(appointmentRow(booked.reference()).staffMemberId()).isEqualTo(other);
    }

    // ---- overlap ---------------------------------------------------------------------------------------------

    @Test
    void overlappingVisitsOfTheSameMemberAreRefusedAsAWholeAndAdjacencyIsAllowed() {
        UUID second = svc("Alpha", 45, "20.50");
        Visit firstVisit = visit(tenant.service(), second).staff(tenant.staff()).start("10:00");
        BookedAppointment booked = ((Created) book(firstVisit.build())).appointment();
        assertThat(booked.end()).isEqualTo(at("11:15"));

        // Any part of the 75 minutes: the head, the tail, and a visit inside the second Service's part.
        assertThat(book(visit(tenant.service(), second).staff(tenant.staff()).start("09:00").build()))
                .isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(book(visit(tenant.service()).staff(tenant.staff()).start("11:00").build()))
                .isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(book(visit(tenant.service()).staff(tenant.staff()).start("10:45").build()))
                .isEqualTo(new BookingResult.SlotUnavailable());
        // Half-open adjacency: ending exactly at 10:00 and starting exactly at 11:15 are both free.
        assertThat(book(visit(tenant.service()).staff(tenant.staff()).start("09:30").build()))
                .isInstanceOf(Created.class);
        assertThat(book(visit(tenant.service()).staff(tenant.staff()).start("11:15").build()))
                .isInstanceOf(Created.class);
        assertThat(appointmentCount(tenant.business())).isEqualTo(3);
        // The refused attempts left no stray line and no stray Customer.
        assertThat(lineCount()).isEqualTo(4);
        assertThat(customerCount(tenant.business())).isEqualTo(3);
    }

    // ---- ordered locks, evidence from PostgreSQL ----------------------------------------------------

    private UUID insertService(UUID id, String name, int minutes) {
        jdbc.sql("""
                        INSERT INTO service(
                            id,business_id,name,description,duration_minutes,price,
                            active,version,created_at,updated_at)
                        VALUES (:id,:business,:name,NULL,:minutes,10.00,true,0,:now,:now)
                        """)
                .param("id", id).param("business", tenant.business()).param("name", name)
                .param("minutes", minutes).param("now", AvailabilityFixtures.databaseNow()).update();
        return id;
    }

    private UUID insertStaff(UUID id, String name) {
        jdbc.sql("""
                        INSERT INTO staff_member(
                            id,business_id,display_name,contact_email,contact_phone,
                            active,version,created_at,updated_at)
                        VALUES (:id,:business,:name,NULL,NULL,true,0,:now,:now)
                        """)
                .param("id", id).param("business", tenant.business()).param("name", name)
                .param("now", AvailabilityFixtures.databaseNow()).update();
        jdbc.sql("""
                        INSERT INTO staff_working_schedule(business_id,staff_member_id,version,created_at,updated_at)
                        VALUES (:business,:id,0,:now,:now)
                        """)
                .param("id", id).param("business", tenant.business())
                .param("now", AvailabilityFixtures.databaseNow()).update();
        availabilityFixtures.everyDay(tenant.business(), id, "09:00", "12:00");
        return id;
    }

    /** Whether another backend currently holds a row lock on the row (probed without waiting). */
    private boolean lockedByAnother(String table, UUID id) {
        try (Connection probe = dataSource.getConnection()) {
            probe.setAutoCommit(true);
            try (var statement = probe.prepareStatement(
                    "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
                statement.setObject(1, id);
                statement.executeQuery();
                return false;
            }
        } catch (SQLException failure) {
            if ("55P03".equals(failure.getSQLState())) {
                return true;
            }
            throw new IllegalStateException(failure);
        }
    }

    /** Holds the row {@code FOR UPDATE} on its own connection until {@link #close()}. */
    private final class RowHolder implements AutoCloseable {
        private final Connection connection;
        private final int pid;

        RowHolder(String table, UUID id) throws SQLException {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, id);
                statement.executeQuery();
            }
            try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
                    var rows = statement.executeQuery()) {
                rows.next();
                pid = rows.getInt(1);
            }
        }

        int pid() {
            return pid;
        }

        @Override
        public void close() throws SQLException {
            if (!connection.isClosed()) {
                connection.commit();
                connection.close();
            }
        }
    }

    @Test
    void theServicesAreLockedInUnsignedUuidOrderAcrossTheHighBitBoundary() throws Exception {
        // Unsigned order: LOW (0x7f...) before HIGH (0x80...). Java's UUID.compareTo says the reverse.
        assertThat(LOW.compareTo(HIGH)).isPositive();
        insertService(LOW, "Low", 30);
        insertService(HIGH, "High", 30);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), LOW);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), HIGH);
        hooks.observeTransactions(jdbc);

        // Another transaction holds the unsigned-LAST row, so the booking waits on it after having
        // locked the unsigned-first one. A signed-order lock would wait on HIGH first and hold nothing.
        try (RowHolder holder = new RowHolder("service", HIGH)) {
            CompletableFuture<BookingResult> booking = submit("a", visit(LOW, HIGH).build());
            awaitBlockedBy(jdbc, pidOf("a"), holder.pid());

            assertThat(lockedByAnother("service", LOW)).as("the unsigned-first Service is already locked").isTrue();
            assertThat(booking).isNotDone();
            holder.close();
            assertThat(completed(booking)).isInstanceOf(Created.class);
        }
    }

    @Test
    void aBookingWaitingOnTheUnsignedFirstServiceHasLockedNothingOfTheLaterOnes() throws Exception {
        insertService(LOW, "Low", 30);
        insertService(HIGH, "High", 30);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), LOW);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), HIGH);
        hooks.observeTransactions(jdbc);

        try (RowHolder holder = new RowHolder("service", LOW)) {
            CompletableFuture<BookingResult> booking = submit("a", visit(LOW, HIGH).build());
            awaitBlockedBy(jdbc, pidOf("a"), holder.pid());

            assertThat(lockedByAnother("service", HIGH)).as("a later Service is not locked yet").isFalse();
            holder.close();
            assertThat(completed(booking)).isInstanceOf(Created.class);
        }
    }

    @Test
    void theEligibleStaffMembersAreLockedInUnsignedUuidOrderAcrossTheHighBitBoundary() throws Exception {
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);
        UUID second = svc("Alpha", 45, "20.50");
        insertStaff(LOW, "Low");
        insertStaff(HIGH, "High");
        availabilityFixtures.assign(tenant.business(), LOW, tenant.service());
        availabilityFixtures.assign(tenant.business(), LOW, second);
        availabilityFixtures.assign(tenant.business(), HIGH, tenant.service());
        availabilityFixtures.assign(tenant.business(), HIGH, second);
        hooks.observeTransactions(jdbc);

        try (RowHolder holder = new RowHolder("staff_member", HIGH)) {
            CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
            awaitBlockedBy(jdbc, pidOf("a"), holder.pid());

            assertThat(lockedByAnother("staff_member", LOW)).as("the unsigned-first member is locked").isTrue();
            holder.close();
            assertThat(completed(booking)).isInstanceOf(Created.class);
        }
    }

    @Test
    void theAttemptKeepsTheBusinessStaffRevisionServiceOrderForAVisit() {
        UUID second = svc("Alpha", 45, "20.50");
        List<java.util.Set<String>> held = new ArrayList<>();
        for (Point point : Point.values()) {
            hooks.on(point, invocation -> held.add(heldLocks(second)));
        }

        assertThat(book(visit(tenant.service(), second).build())).isInstanceOf(Created.class);

        assertThat(held).containsExactly(
                java.util.Set.of("business"),
                java.util.Set.of("business", "staff"),
                java.util.Set.of("business", "staff", "revision"),
                java.util.Set.of("business", "staff", "revision", "service-1", "service-2"),
                java.util.Set.of("business", "staff", "revision", "service-1", "service-2"),
                java.util.Set.of("business", "staff", "revision", "service-1", "service-2"));
    }

    private java.util.Set<String> heldLocks(UUID second) {
        return async(() -> {
            java.util.Set<String> locked = new java.util.TreeSet<>();
            if (lockedByAnother("business", tenant.business())) {
                locked.add("business");
            }
            if (lockedByAnother("staff_member", tenant.staff())) {
                locked.add("staff");
            }
            if (!jdbc.sql("SELECT business_id FROM business_schedule_revision WHERE business_id = :id"
                            + " FOR UPDATE SKIP LOCKED")
                    .param("id", tenant.business()).query(UUID.class).list().contains(tenant.business())) {
                locked.add("revision");
            }
            if (lockedByAnother("service", tenant.service())) {
                locked.add("service-1");
            }
            if (lockedByAnother("service", second)) {
                locked.add("service-2");
            }
            return locked;
        }).join();
    }

    // ---- races ------------------------------------------------------------------------------------------------

    @Test
    void twoIdenticalVisitsInParallelCreateOneVisitAndTheLoserReplaysIt() {
        UUID second = svc("Alpha", 45, "20.50");
        Visit request = visit(tenant.service(), second).staff(tenant.staff());
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
        CompletableFuture<BookingResult> second2 = submit("b", request.copy().permuted().build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));
        release.countDown();

        BookingResult created = completed(first);
        BookingResult replayed = completed(second2);

        assertThat(created).isInstanceOf(Created.class);
        assertThat(replayed).isEqualTo(new Replayed(((Created) created).appointment()));
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(lineCount()).isEqualTo(2);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
    }

    @Test
    void twoDifferentVisitsForOverlappingTimeLeaveOneVisitAndNoOrphanLinesOrCustomer() {
        UUID second = svc("Alpha", 45, "20.50");
        CountDownLatch committing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            if (invocation.thread().equals(threadOf("a"))) {
                holdBeforeCommit(committing, release);
            }
        });
        CompletableFuture<BookingResult> winner = submit(
                "a", visit(tenant.service(), second).staff(tenant.staff()).start("10:00").build());
        await(committing, "booking a never reached its commit");
        // Starts inside the held visit's tail (10:00-11:15): it validated against a snapshot that cannot see it.
        CompletableFuture<BookingResult> loser = submit(
                "b", visit(tenant.service()).staff(tenant.staff()).start("10:45").build());
        awaitBlockedBy(jdbc, pidOf("b"), pidOf("a"));
        release.countDown();

        assertThat(completed(winner)).isInstanceOf(Created.class);
        assertThat(completed(loser)).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(lineCount()).isEqualTo(2);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("b"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("b")))
                .containsExactly(threadOf("b") + ":rollback", threadOf("b") + ":rollback");
        List<TransactionObservation> attempts = hooks.observationsOf(threadOf("b")).stream()
                .filter(observation -> observation.point() == Point.BEFORE_STAFF_LOCK).toList();
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).transactionId()).isLessThan(attempts.get(1).transactionId());
        assertThat(attempts.get(0).snapshot()).isNotEqualTo(attempts.get(1).snapshot());
    }

    @Test
    void aPriceChangeCommittedAfterTheSnapshotMakesTheRetryAReviewChangeWithNothingWritten() {
        UUID second = svc("Alpha", 45, "20.50");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        hooks.observeTransactions(jdbc);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
        await(reached, "booking never reached the staff lock");

        // The snapshot exists; a committed change postdates it. The Service lock fails with 40001 and
        // the retry, in a completely new transaction, compares the reviewed terms with the new ones.
        jdbc.sql("UPDATE service SET price = 12.50 WHERE id = :id").param("id", second).update();
        release.countDown();

        assertThat(completed(booking)).isEqualTo(new BookingResult.ReviewChanged());
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(TransactionLog.endsOf(threadOf("a")))
                .containsExactly(threadOf("a") + ":rollback", threadOf("a") + ":rollback");
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void aDurationChangeCommittedAfterTheSnapshotIsAReviewChangeOnTheRetry() {
        UUID second = svc("Alpha", 45, "20.50");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
        await(reached, "booking never reached the staff lock");
        jdbc.sql("UPDATE service SET duration_minutes = 50 WHERE id = :id").param("id", second).update();
        release.countDown();

        assertThat(completed(booking)).isEqualTo(new BookingResult.ReviewChanged());
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void aNameChangeCommittedAfterTheSnapshotIsNotATermChangeAndTheRetryStoresTheNewName() {
        UUID second = svc("Alpha", 45, "20.50");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
        await(reached, "booking never reached the staff lock");
        jdbc.sql("UPDATE service SET name = 'Renamed' WHERE id = :id").param("id", second).update();
        release.countDown();

        BookedAppointment booked = ((Created) completed(booking)).appointment();

        assertThat(booked.services()).extracting(BookedService::name).contains("Renamed");
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
    }

    @Test
    void aServiceDeactivatedAfterTheSnapshotIsUnavailableOnTheRetry() {
        UUID second = svc("Alpha", 45, "20.50");
        var owner = owner(tenant.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
        await(reached, "booking never reached the staff lock");
        services.deactivate(owner, second, new ServiceVersionCommand(0L));
        release.countDown();

        assertThat(completed(booking)).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void anAssignmentRemovedAfterTheSnapshotThroughTheRealAdministrationEndsTheEligibility() {
        UUID second = svc("Alpha", 45, "20.50");
        var owner = owner(tenant.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit(
                "a", visit(tenant.service(), second).staff(tenant.staff()).build());
        await(reached, "booking never reached the staff lock");
        staffMembers.replaceServiceAssignments(owner, tenant.staff(),
                new ReplaceServiceAssignmentsCommand(List.of(tenant.service()), 0L));
        release.countDown();

        assertThat(completed(booking)).isEqualTo(new BookingResult.StaffMemberUnavailable());
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void aServiceChangeWaitsForTheOpenVisitWhichStoresTheTermsItLocked() {
        UUID second = svc("Alpha", 45, "20.50");
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_AVAILABILITY, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
        await(reached, "booking never reached availability");

        CompletableFuture<Integer> change = async(() -> jdbc.sql(
                "UPDATE service SET price = 99.00, duration_minutes = 60 WHERE id = :id")
                .param("id", second).update());
        awaitWaiterBlockedBy(jdbc, pidOf("a"));
        assertThat(change).isNotDone();
        release.countDown();

        BookedAppointment booked = ((Created) completed(booking)).appointment();
        completed(change);
        // The visit committed with the terms it locked; the change applied only afterwards.
        assertThat(booked.durationMinutes()).isEqualTo(75);
        assertThat(booked.price()).isEqualByComparingTo("30.50");
        assertThat(seen(second).durationMinutes()).isEqualTo(60);
    }

    @Test
    void aScheduleChangeThatWaitsForTheOpenVisitCommitsAfterIt() {
        UUID second = svc("Alpha", 45, "20.50");
        var owner = owner(tenant.business());
        hooks.observeTransactions(jdbc);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_CUSTOMER, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
        await(reached, "booking never reached the customer step");

        CompletableFuture<Void> change = async(() -> {
            exceptions.create(owner, new CreateScheduleExceptionCommand(
                    ScheduleExceptionKind.STAFF_TIME_OFF, tenant.staff(), THURSDAY, THURSDAY, true, List.of()));
            return null;
        });
        awaitWaiterBlockedBy(jdbc, pidOf("a"));
        assertThat(change).isNotDone();
        release.countDown();

        assertThat(completed(booking)).isInstanceOf(Created.class);
        completed(change);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    @Test
    void aScheduleChangeCommittedAfterTheSnapshotMakesTheRetryFindTheVisitUnavailable() {
        UUID second = svc("Alpha", 45, "20.50");
        var owner = owner(tenant.business());
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdAt(Point.BEFORE_STAFF_LOCK, "a", reached, release);
        CompletableFuture<BookingResult> booking = submit("a", visit(tenant.service(), second).build());
        await(reached, "booking never reached the staff lock");
        exceptions.create(owner, new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.STAFF_TIME_OFF, tenant.staff(), THURSDAY, THURSDAY, true, List.of()));
        release.countDown();

        assertThat(completed(booking)).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(2);
        assertThat(totalAppointments()).isZero();
    }

    // ---- injected faults (test-only triggers; not behavior PostgreSQL produces by itself) -----------------

    private void withTrigger(String ddl, String drop, Runnable body) {
        jdbc.sql("""
                        CREATE FUNCTION injected_line_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'injected fault' USING ERRCODE = 'P0001'; END $$
                        """).update();
        jdbc.sql(ddl).update();
        try {
            body.run();
        } finally {
            jdbc.sql(drop).update();
            jdbc.sql("DROP FUNCTION injected_line_fault()").update();
        }
    }

    @Test
    void anInjectedFailureWhileInsertingTheSecondLineRollsBackTheAppointmentTheFirstLineAndTheNewCustomer() {
        UUID second = svc("Alpha", 45, "20.50");

        withTrigger("""
                        CREATE TRIGGER injected_line_fault BEFORE INSERT ON appointment_service
                        FOR EACH ROW WHEN (NEW.position = 2) EXECUTE FUNCTION injected_line_fault()
                        """,
                "DROP TRIGGER injected_line_fault ON appointment_service",
                () -> assertThat(book(visit(tenant.service(), second).build()))
                        .isEqualTo(new BookingResult.TemporarilyUnavailable()));

        assertThat(totalAppointments()).isZero();
        assertThat(lineCount()).isZero();
        assertThat(totalCustomers()).isZero();
        assertThat(TransactionLog.begins(threadOf("main"))).hasSize(1);
        // The same request succeeds once the fault is gone: nothing of it was consumed.
        assertThat(book(visit(tenant.service(), second).build())).isInstanceOf(Created.class);
    }

    @Test
    void anInjectedDeferredConstraintFailureAtCommitLeavesNothingOfTheVisitOrItsNewCustomer() {
        UUID second = svc("Alpha", 45, "20.50");

        withTrigger("""
                        CREATE CONSTRAINT TRIGGER injected_line_fault AFTER INSERT ON appointment_service
                        DEFERRABLE INITIALLY DEFERRED
                        FOR EACH ROW WHEN (NEW.position = 2) EXECUTE FUNCTION injected_line_fault()
                        """,
                "DROP TRIGGER injected_line_fault ON appointment_service",
                () -> assertThat(book(visit(tenant.service(), second).build()))
                        .isEqualTo(new BookingResult.TemporarilyUnavailable()));

        // The deferred trigger fails at COMMIT; PostgreSQL rolls the transaction back and Spring
        // reports it as rolled back, so this is the proven-rollback outcome, and nothing remains.
        assertThat(totalAppointments()).isZero();
        assertThat(lineCount()).isZero();
        assertThat(totalCustomers()).isZero();
    }
}
