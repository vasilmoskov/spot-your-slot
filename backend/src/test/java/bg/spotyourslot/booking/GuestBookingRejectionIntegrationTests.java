package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every rejection path of the orchestration against real PostgreSQL, and the atomic rollback of
 * everything an attempt wrote. After every rejection the Business has no Appointment and no Customer
 * that the attempt could have created, and the revision of the schedule is untouched.
 */
class GuestBookingRejectionIntegrationTests extends BookingIntegrationTest {
    @Autowired ScheduleExceptionAdministration scheduleExceptions;

    @BeforeEach
    void createTheConsumerProbe() {
        // A stand-in for any other consumer that writes inside the booking transaction.
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS booking_consumer_probe(
                            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                            note text NOT NULL)
                        """).update();
        jdbc.sql("TRUNCATE booking_consumer_probe").update();
    }

    private long probeRows() {
        return jdbc.sql("SELECT count(*) FROM booking_consumer_probe").query(Long.class).single();
    }

    private void writeProbe() {
        jdbc.sql("INSERT INTO booking_consumer_probe(note) VALUES ('consumer write')").update();
    }

    private void assertNothingWritten() {
        assertThat(totalAppointments()).as("appointments").isZero();
        assertThat(totalCustomers()).as("customers").isZero();
        assertThat(probeRows()).as("consumer writes").isZero();
    }

    private BookingResult attempt(Req request) {
        return result(submit("r", request.build()));
    }

    // ---- validation ---------------------------------------------------------------

    static Stream<Object[]> invalidRequests() {
        return Stream.of(
                new Object[] {"attempt id", (java.util.function.UnaryOperator<Req>) request -> request.attempt("nope"),
                        EnumSet.of(BookingField.ATTEMPT_ID)},
                new Object[] {"name", (java.util.function.UnaryOperator<Req>) request -> request.name("  "),
                        EnumSet.of(BookingField.DISPLAY_NAME)},
                new Object[] {"phone", (java.util.function.UnaryOperator<Req>) request -> request.phone("12"),
                        EnumSet.of(BookingField.PHONE)},
                new Object[] {"email", (java.util.function.UnaryOperator<Req>) request -> request.phone(null).email("x@"),
                        EnumSet.of(BookingField.EMAIL)},
                new Object[] {"contact", (java.util.function.UnaryOperator<Req>) request -> request.phone(null),
                        EnumSet.of(BookingField.CONTACT)},
                new Object[] {"note", (java.util.function.UnaryOperator<Req>) request -> request.note("a\u0000b"),
                        EnumSet.of(BookingField.NOTE)},
                new Object[] {"start", (java.util.function.UnaryOperator<Req>) request ->
                        request.start(at("10:00").plusNanos(5)), EnumSet.of(BookingField.START)});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void anInvalidRequestIsRejectedWithoutBeginningATransaction(
            String field, java.util.function.UnaryOperator<Req> mutation, EnumSet<BookingField> expected) {
        BookingResult result = attempt(mutation.apply(req()));

        assertThat(result).isInstanceOfSatisfying(BookingResult.InvalidRequest.class,
                invalid -> assertThat(invalid.fields()).isEqualTo(expected));
        assertThat(TransactionLog.begins(threadOf("r"))).isEmpty();
        assertThat(clock.reads()).isZero();
        assertNothingWritten();
    }

    // ---- Business -----------------------------------------------------------------

    static Stream<String> unusableSlugs() {
        return Stream.of("no-such-business", "Bad Slug!", "", "api", "x".repeat(200));
    }

    @ParameterizedTest
    @MethodSource("unusableSlugs")
    void anUnknownMalformedOrReservedSlugIsTheCollapsedBusinessOutcome(String slug) {
        assertThat(attempt(req().slug(slug))).isEqualTo(new BookingResult.BusinessUnavailable());

        assertNothingWritten();
    }

    @ParameterizedTest
    @MethodSource("unavailableStatuses")
    void aDraftOrSuspendedBusinessRejectsANewBookingAsTheSameCollapsedOutcome(String status) {
        availabilityFixtures.setBusinessStatus(tenant.business(), status);

        assertThat(attempt(req())).isEqualTo(new BookingResult.BusinessUnavailable());

        assertNothingWritten();
    }

    static Stream<String> unavailableStatuses() {
        return Stream.of("DRAFT", "SUSPENDED");
    }

    @Test
    void anotherBusinessesSlugNeverReachesThisBusinessData() {
        var other = openTenant();

        // This Business's Service and StaffMember through the other Business's slug are foreign.
        assertThat(attempt(req().slug(slugOf(other.business())))).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(attempt(req().forTenant(other).staff(tenant.staff())))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
        assertNothingWritten();
    }

    // ---- Service ------------------------------------------------------------------

    @Test
    void aMissingForeignOrInactiveServiceIsTheServiceOutcome() {
        var other = openTenant();
        UUID inactive = availabilityFixtures.service(tenant.business(), 30, false);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), inactive);

        assertThat(attempt(req().service(UUID.randomUUID()))).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(attempt(req().service(other.service()))).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(attempt(req().service(inactive))).isEqualTo(new BookingResult.ServiceUnavailable());

        assertNothingWritten();
    }

    @Test
    void aDeactivatedServiceNoLongerAcceptsBookings() {
        assertThat(attempt(req())).isInstanceOf(Created.class);
        availabilityFixtures.setServiceActive(tenant.service(), false);

        assertThat(attempt(req().start("10:30"))).isEqualTo(new BookingResult.ServiceUnavailable());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    // ---- StaffMember --------------------------------------------------------------

    @Test
    void aMissingForeignInactiveOrUnassignedRequestedStaffMemberIsTheStaffOutcome() {
        var other = openTenant();
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.setStaffMemberActive(inactive, false);
        UUID unassigned = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.unassign(tenant.business(), unassigned, tenant.service());

        for (UUID staff : List.of(UUID.randomUUID(), other.staff(), inactive, unassigned)) {
            assertThat(attempt(req().staff(staff))).isEqualTo(new BookingResult.StaffMemberUnavailable());
        }

        assertNothingWritten();
    }

    @Test
    void withoutPreferenceANoEligibleStaffMemberIsASlotRejection() {
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);
        assertThat(attempt(req())).isEqualTo(new BookingResult.SlotUnavailable());

        availabilityFixtures.setStaffMemberActive(tenant.staff(), true);
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), tenant.service());
        assertThat(attempt(req())).isEqualTo(new BookingResult.SlotUnavailable());

        assertNothingWritten();
    }

    @Test
    void aStaffMemberDeactivatedAfterAnEarlierBookingIsRejectedForTheNextOne() {
        assertThat(attempt(req().staff(tenant.staff()))).isInstanceOf(Created.class);
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);

        assertThat(attempt(req().staff(tenant.staff()).start("10:30")))
                .isEqualTo(new BookingResult.StaffMemberUnavailable());
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    // ---- availability -------------------------------------------------------------

    @Test
    void anUnofferedStartIsASlotRejection() {
        Instant tooSoon = BookingHookConfiguration.NOW.plusSeconds(3_600);
        Instant beyondHorizon = at("10:00").plusSeconds(40L * 86_400);
        Instant offGrid = at("10:07");
        Instant outsideHours = at("07:00");

        for (Instant start : List.of(tooSoon, beyondHorizon, offGrid, outsideHours)) {
            assertThat(attempt(req().start(start))).as(start.toString())
                    .isEqualTo(new BookingResult.SlotUnavailable());
        }

        assertNothingWritten();
    }

    @Test
    void aBusinessClosureCommittedBeforeTheSnapshotRemovesTheSlot() {
        scheduleExceptions.create(owner(tenant.business()), new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, THURSDAY, THURSDAY, true, List.of()));

        assertThat(attempt(req())).isEqualTo(new BookingResult.SlotUnavailable());

        assertNothingWritten();
    }

    @Test
    void aBookedStartIsRejectedForTheNextGuestWithoutCreatingTheirCustomer() {
        assertThat(attempt(req())).isInstanceOf(Created.class);
        long customers = customerCount(tenant.business());

        assertThat(attempt(req())).isEqualTo(new BookingResult.SlotUnavailable());

        assertThat(customerCount(tenant.business())).isEqualTo(customers);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
    }

    // ---- Customer identity --------------------------------------------------------

    @Test
    void aSplitIdentityIsTheGenericConflictAndNothingIsWritten() {
        // Two Customers: one holds the phone, another holds the email.
        String phone = "+359888555001";
        completedFixtureCustomer(phone, null);
        completedFixtureCustomer(null, "held@example.com");
        long customers = customerCount(tenant.business());

        BookingResult result = attempt(req().phone(phone).email("held@example.com"));

        assertThat(result).isEqualTo(new BookingResult.IdentityConflict());
        assertThat(customerCount(tenant.business())).isEqualTo(customers);
        assertThat(appointmentCount(tenant.business())).isZero();
    }

    @Test
    void aPartialIdentityMatchIsTheGenericConflictToo() {
        completedFixtureCustomer("+359888555002", null);

        BookingResult result = attempt(req().phone("+359888555002").email("fresh@example.com"));

        assertThat(result).isEqualTo(new BookingResult.IdentityConflict());
        assertThat(appointmentCount(tenant.business())).isZero();
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    private void completedFixtureCustomer(String phone, String email) {
        jdbc.sql("""
                        INSERT INTO customer(
                            id, business_id, display_name, phone, email, version, created_at, updated_at)
                        VALUES (:id, :businessId, 'Тестов Клиент', :phone, :email, 0, :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", tenant.business())
                .param("phone", phone)
                .param("email", email)
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
    }

    @Test
    void aCustomerOfAnotherBusinessNeverMatches() {
        var other = openTenant();
        String phone = freshPhone();
        assertThat(attempt(req().forTenant(other).phone(phone))).isInstanceOf(Created.class);

        assertThat(attempt(req().phone(phone))).isInstanceOf(Created.class);

        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(other.business())).isEqualTo(1);
    }

    // ---- atomic rollback ----------------------------------------------------------

    @Test
    void aFailureAfterTheCustomerInsertRollsBackTheCustomerAndEveryConsumerWrite() {
        hooks.on(Point.AFTER_CUSTOMER, invocation -> {
            writeProbe();
            throw new IllegalStateException("consumer failed after the customer step");
        });

        BookingResult result = attempt(req());

        assertThat(result).isEqualTo(new BookingResult.TemporarilyUnavailable());
        assertNothingWritten();
        // A proven rollback is not retried: one transaction began and it rolled back.
        assertThat(TransactionLog.begins(threadOf("r"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("r"))).containsExactly(threadOf("r") + ":rollback");
    }

    @Test
    void aFailureBeforeTheCustomerStepRollsBackEarlierConsumerWrites() {
        hooks.on(Point.BEFORE_CUSTOMER, invocation -> {
            writeProbe();
            throw new IllegalStateException("consumer failed");
        });

        assertThat(attempt(req())).isEqualTo(new BookingResult.TemporarilyUnavailable());

        assertNothingWritten();
    }

    @Test
    void anIdentityConflictRollsBackConsumerWritesMadeBeforeIt() {
        completedFixtureCustomer("+359888555003", null);
        completedFixtureCustomer(null, "held2@example.com");
        long customers = customerCount(tenant.business());
        hooks.on(Point.BEFORE_CUSTOMER, invocation -> writeProbe());

        BookingResult result = attempt(req().phone("+359888555003").email("held2@example.com"));

        assertThat(result).isEqualTo(new BookingResult.IdentityConflict());
        assertThat(probeRows()).isZero();
        assertThat(customerCount(tenant.business())).isEqualTo(customers);
    }

    @Test
    void aSlotRejectionRollsBackConsumerWritesMadeDuringAvailability() {
        hooks.on(Point.BEFORE_AVAILABILITY, invocation -> writeProbe());

        assertThat(attempt(req().start("10:07"))).isEqualTo(new BookingResult.SlotUnavailable());

        assertNothingWritten();
    }

    @Test
    void aSuccessfulBookingCommitsTheCustomerTheAppointmentAndTheConsumerWriteTogether() {
        hooks.on(Point.AFTER_CUSTOMER, invocation -> writeProbe());

        assertThat(attempt(req())).isInstanceOf(Created.class);

        assertThat(appointmentCount(tenant.business())).isEqualTo(1);
        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(probeRows()).isEqualTo(1);
    }

    @Test
    void everyKnownFailureResultCarriesNoDiagnosticsOrRequestValues() {
        BookingResult result = attempt(req().start("10:07").name("СЕНТИНЕЛ-ИМЕ").note("СЕНТИНЕЛ-БЕЛЕЖКА"));

        assertThat(result.toString()).doesNotContain("СЕНТИНЕЛ").doesNotContain("SQL");
        assertThat(result).isEqualTo(new BookingResult.SlotUnavailable());
    }
}
