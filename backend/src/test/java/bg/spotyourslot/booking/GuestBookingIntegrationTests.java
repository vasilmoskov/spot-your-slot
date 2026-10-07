package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.BookingResult.Created;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The guest booking orchestration against real PostgreSQL: transaction ownership, successful
 * booking with an explicit and an automatically assigned StaffMember, and what is written.
 */
class GuestBookingIntegrationTests extends BookingIntegrationTest {
    // ---- transaction ownership --------------------------------------------------

    @Test
    void anActiveCallerTransactionIsRejectedBeforeAnyClockReadSqlLockAvailabilityOrCustomerCall() {
        clock.resetReads();
        GuestBookingRequest request = req().build();

        Throwable failure = async(() -> {
            Thread.currentThread().setName(BookingTestHooks.BOOKING_THREAD_PREFIX + "caller");
            try {
                new TransactionTemplate(transactionManager).execute(status -> booking.book(request));
                return null;
            } catch (Throwable thrown) {
                return thrown;
            } finally {
                Thread.currentThread().setName("pool");
            }
        }).join();

        assertThat(failure).isInstanceOf(BookingOrchestrationFailure.class)
                .hasMessage("Guest booking must be invoked without an active transaction");
        assertThat(failure.getCause()).isNull();
        // Nothing was read or written: no clock read, no hook point reached, and the only
        // transaction the caller thread ever began is the caller's own.
        assertThat(clock.reads()).isZero();
        assertThat(hooks.observations()).isEmpty();
        assertThat(TransactionLog.begins(BookingTestHooks.BOOKING_THREAD_PREFIX + "caller")).hasSize(1);
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void anActiveSynchronizationWithoutATransactionIsRejectedToo() {
        GuestBookingRequest request = req().build();

        Throwable failure = async(() -> {
            TransactionSynchronizationManager.initSynchronization();
            try {
                booking.book(request);
                return null;
            } catch (Throwable thrown) {
                return thrown;
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }).join();

        assertThat(failure).isInstanceOf(BookingOrchestrationFailure.class);
        assertThat(clock.reads()).isZero();
        assertThat(totalAppointments()).isZero();
        assertThat(totalCustomers()).isZero();
    }

    @Test
    void theRejectionIsNotARetriedBookingOutcomeAndTheCallerTransactionStaysUsable() {
        GuestBookingRequest request = req().build();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        Boolean callerStillWorks = template.execute(status -> {
            assertThatThrownBy(() -> booking.book(request)).isInstanceOf(BookingOrchestrationFailure.class);
            return jdbc.sql("SELECT true").query(Boolean.class).single();
        });

        assertThat(callerStillWorks).isTrue();
        assertThat(totalAppointments()).isZero();
    }

    @Test
    void everyAttemptIsItsOwnRepeatableReadReadWriteTransactionAndNothingIsNestedOrRequiresNew() {
        Created created = (Created) completedBooking("solo", req());

        assertThat(created).isNotNull();
        var started = TransactionLog.begins(threadOf("solo"));
        assertThat(started).hasSize(1);
        assertThat(started.get(0).propagation())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(started.get(0).isolation()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(started.get(0).readOnly()).isFalse();
        // Every collaborator joined that one transaction: nothing was suspended (no REQUIRES_NEW)
        // and the single transaction committed.
        assertThat(TransactionLog.suspensions()).isZero();
        assertThat(TransactionLog.endsOf(threadOf("solo"))).containsExactly(threadOf("solo") + ":commit");
    }

    private BookingResult completedBooking(String label, Req request) {
        return result(submit(label, request.build()));
    }

    // ---- successful booking -----------------------------------------------------

    @Test
    void anExplicitStaffMemberBookingCommitsOneConfirmedOnlineAppointmentWithItsSnapshots() {
        Req request = req().staff(tenant.staff()).note("  Бележка\r\nза часа \n").phone("0888 111 222");

        BookingResult result = completedBooking("a", request);

        assertThat(result).isInstanceOf(Created.class);
        BookedAppointment booked = ((Created) result).appointment();
        assertThat(booked.status()).isEqualTo(BookedAppointment.Status.CONFIRMED);
        assertThat(booked.start()).isEqualTo(at("10:00"));
        assertThat(booked.end()).isEqualTo(at("10:30"));
        assertThat(booked.durationMinutes()).isEqualTo(30);
        assertThat(booked.price()).isEqualByComparingTo("10.00");
        assertThat(booked.timezone()).isEqualTo("Europe/Sofia");
        assertThat(booked.reference()).matches("[0-9A-HJKMNP-TV-Z]{10}");
        assertThat(booked.serviceName()).startsWith("Service ");
        assertThat(booked.staffDisplayName()).isEqualTo("Availability Staff");

        AppointmentRow row = appointmentRow(booked.reference());
        assertThat(row.businessId()).isEqualTo(tenant.business());
        assertThat(row.serviceId()).isEqualTo(tenant.service());
        assertThat(row.staffMemberId()).isEqualTo(tenant.staff());
        assertThat(row.source()).isEqualTo("ONLINE");
        assertThat(row.status()).isEqualTo("CONFIRMED");
        assertThat(row.startAt()).isEqualTo(at("10:00"));
        assertThat(row.endAt()).isEqualTo(at("10:30"));
        assertThat(row.occupiedUntil()).isEqualTo(row.endAt());
        assertThat(row.durationMinutes()).isEqualTo(30);
        assertThat(row.price()).isEqualByComparingTo("10.00");
        assertThat(row.timezone()).isEqualTo("Europe/Sofia");
        assertThat(row.customerNote()).isEqualTo("Бележка\nза часа");
        assertThat(row.version()).isZero();
        assertThat(row.createdAt()).isEqualTo(BookingHookConfiguration.NOW);
        assertThat(row.updatedAt()).isEqualTo(row.createdAt());
    }

    @Test
    void theStoredIdempotencyMetadataIsTheAttemptHashAndTheVersionedHmacFingerprint() {
        Req request = req().staff(tenant.staff());

        BookedAppointment booked = ((Created) completedBooking("a", request)).appointment();

        AppointmentRow row = appointmentRow(booked.reference());
        assertThat(row.attemptHash()).hasSize(32)
                .isEqualTo(bg.spotyourslot.booking.domain.BookingAttemptId.parse(request.attemptId)
                        .orElseThrow().hash());
        assertThat(row.fingerprint()).hasSize(32);
        assertThat(row.encodingVersion()).isEqualTo(1);
        assertThat(row.keyVersion()).isEqualTo(1);
        // The raw attempt identifier and contact data are not stored anywhere in the row.
        String everything = jdbc.sql("SELECT a::text FROM appointment a").query(String.class).single();
        assertThat(everything)
                .doesNotContain(request.attemptId)
                .doesNotContain(request.phone)
                .doesNotContain(request.name);
    }

    @Test
    void theCustomerIsCreatedWithTheCanonicalContactInTheSameTransaction() {
        completedBooking("a", req().phone("0888 111 222").email("  IVAN@Example.COM ").name("  Иван   Петров "));

        Map<String, Object> customer = jdbc.sql("""
                        SELECT display_name, phone, email FROM customer WHERE business_id = :id
                        """)
                .param("id", tenant.business()).query().singleRow();
        assertThat(customer).containsEntry("display_name", "Иван Петров")
                .containsEntry("phone", "+359888111222")
                .containsEntry("email", "ivan@example.com");
        assertThat(customerCount(tenant.business())).isEqualTo(1);
    }

    @Test
    void aReturningGuestWithTheSamePhoneIsMatchedAndNoSecondCustomerIsCreated() {
        String phone = freshPhone();
        completedBooking("a", req().phone(phone).start("10:00"));
        completedBooking("b", req().phone(phone).name("Друго име").start("10:30"));

        assertThat(customerCount(tenant.business())).isEqualTo(1);
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(DISTINCT customer_id) FROM appointment").query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    void aBookingWithoutAPreferenceAssignsTheOnlyQualifiedStaffMember() {
        BookedAppointment booked = ((Created) completedBooking("a", req())).appointment();

        assertThat(appointmentRow(booked.reference()).staffMemberId()).isEqualTo(tenant.staff());
    }

    @Test
    void theFreshlyOfferedStartIsTheOnlyAcceptedStartAndTheGridAndHoursApply() {
        assertThat(completedBooking("a", req().start("09:00"))).isInstanceOf(Created.class);
        assertThat(completedBooking("b", req().start("11:30"))).isInstanceOf(Created.class);
        assertThat(completedBooking("c", req().start("10:05"))).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(completedBooking("d", req().start("08:45"))).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(completedBooking("e", req().start("11:45"))).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
        assertThat(customerCount(tenant.business())).isEqualTo(2);
    }

    @Test
    void theStartAfterBookedTimeAndAdjacentToItIsBookable() {
        completedBooking("a", req().start("10:00"));

        assertThat(completedBooking("b", req().start("10:30"))).isInstanceOf(Created.class);
        assertThat(completedBooking("c", req().start("09:30"))).isInstanceOf(Created.class);
        assertThat(completedBooking("d", req().start("10:15"))).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(completedBooking("e", req().start("09:45"))).isEqualTo(new BookingResult.SlotUnavailable());
    }

    @Test
    void theBookingItselfNeverReadsTheClockAndTheAppointmentIsCreatedAtTheCalculationInstant() {
        clock.resetReads();
        String phone = freshPhone();

        BookedAppointment first = ((Created) completedBooking("a", req().phone(phone))).appointment();

        // Availability reads the clock once and the Customer insert once; the booking never does.
        assertThat(clock.reads()).isEqualTo(2);
        assertThat(appointmentRow(first.reference()).createdAt()).isEqualTo(BookingHookConfiguration.NOW);

        clock.resetReads();
        completedBooking("b", req().phone(phone).start("10:30"));

        // A matched Customer needs no insert, so only the availability read remains.
        assertThat(clock.reads()).isEqualTo(1);
    }

    // ---- the deterministic "Без предпочитание" assignment ----------------------

    private Tenant staffTenant(UUID staff) {
        return new Tenant(tenant.business(), tenant.service(), staff, fixtures.customer(tenant.business()));
    }

    /** A second qualified StaffMember working 09:00-12:00 whose creation time and name are chosen. */
    private UUID secondStaff(String createdAt, String name) {
        UUID staff = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), staff, "09:00", "12:00");
        jdbc.sql("""
                        UPDATE staff_member
                        SET created_at = :createdAt, updated_at = :createdAt, display_name = :name
                        WHERE id = :id
                        """)
                .param("createdAt", utc(Instant.parse(createdAt)))
                .param("name", name)
                .param("id", staff)
                .update();
        return staff;
    }

    private void renameFirstStaff(String createdAt, String name) {
        jdbc.sql("""
                        UPDATE staff_member
                        SET created_at = :createdAt, updated_at = :createdAt, display_name = :name
                        WHERE id = :id
                        """)
                .param("createdAt", utc(Instant.parse(createdAt)))
                .param("name", name)
                .param("id", tenant.staff())
                .update();
    }

    private void confirmedAppointment(UUID staff, Instant start, int minutes, String status) {
        Map<String, Object> row = AppointmentFixtures.row(staffTenant(staff), start, minutes);
        row.put("status", status);
        fixtures.insertRow(row);
    }

    private UUID assignedStaff(BookingResult result) {
        return appointmentRow(((Created) result).appointment().reference()).staffMemberId();
    }

    @Test
    void withoutPreferenceTheEarlierCreatedStaffMemberWinsAnEqualCount() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");

        UUID assigned = assignedStaff(completedBooking("a", req()));

        assertThat(assigned).isEqualTo(tenant.staff()).isNotEqualTo(later);
    }

    @Test
    void anEqualCountAndCreationTimeIsDecidedBySmallestIdentifier() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID other = secondStaff("2026-01-02T00:00:00Z", "Втора");

        UUID assigned = assignedStaff(completedBooking("a", req()));

        assertThat(assigned).isEqualTo(tenant.staff().compareTo(other) < 0 ? tenant.staff() : other);
    }

    @Test
    void theFewestConfirmedAppointmentsOnTheLocalDateBeatsAnEarlierCreation() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");
        confirmedAppointment(tenant.staff(), at("09:00"), 30, "CONFIRMED");

        UUID assigned = assignedStaff(completedBooking("a", req().start("10:00")));

        assertThat(assigned).isEqualTo(later);
    }

    @Test
    void successiveAssignmentsAlternateByTheRule() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");

        UUID first = assignedStaff(completedBooking("a", req().start("09:00")));
        UUID second = assignedStaff(completedBooking("b", req().start("09:30")));
        UUID third = assignedStaff(completedBooking("c", req().start("10:00")));
        UUID fourth = assignedStaff(completedBooking("d", req().start("10:30")));

        assertThat(List.of(first, second, third, fourth))
                .containsExactly(tenant.staff(), later, tenant.staff(), later);
    }

    @Test
    void onlyConfirmedAppointmentsOnTheSlotsLocalDateCountAndCancelledOnesDoNot() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");
        // The earlier-created member has a CONFIRMED Appointment on Wednesday and a CANCELLED one
        // on Thursday: neither counts for Thursday. The other has one CONFIRMED on Thursday.
        confirmedAppointment(tenant.staff(), at("10:00").minusSeconds(86_400), 30, "CONFIRMED");
        confirmedAppointment(tenant.staff(), at("09:00"), 30, "CANCELLED");
        confirmedAppointment(later, at("09:00"), 30, "CONFIRMED");

        UUID assigned = assignedStaff(completedBooking("a", req().start("10:00")));

        assertThat(assigned).isEqualTo(tenant.staff());
    }

    @Test
    void theDateIsTheBusinessLocalDateNotTheUtcDate() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");
        // 00:30 Thursday in Sofia (UTC+3) is 21:30 Wednesday UTC; 23:30 Wednesday in Sofia is 20:30
        // Wednesday UTC. By UTC date both would be Wednesday and the earlier-created member would
        // win the tie; by local date only the first member has a Thursday Appointment.
        confirmedAppointment(tenant.staff(), at("00:30"), 15, "CONFIRMED");
        confirmedAppointment(later, at("00:30").minusSeconds(3_600), 15, "CONFIRMED");

        UUID assigned = assignedStaff(completedBooking("a", req().start("10:00")));

        assertThat(assigned).isEqualTo(later);
    }

    @Test
    void aBusyStaffMemberIsSkippedEvenWhenTheRuleWouldPreferThem() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");
        confirmedAppointment(tenant.staff(), at("10:00"), 30, "CONFIRMED");
        confirmedAppointment(later, at("09:00"), 30, "CONFIRMED");
        confirmedAppointment(later, at("09:30"), 30, "CONFIRMED");

        UUID assigned = assignedStaff(completedBooking("a", req().start("10:00")));

        assertThat(assigned).isEqualTo(later);
    }

    @Test
    void anInactiveOrUnassignedStaffMemberIsNeverAssigned() {
        UUID inactive = secondStaff("2025-01-01T00:00:00Z", "Неактивна");
        availabilityFixtures.setStaffMemberActive(inactive, false);
        UUID unassigned = secondStaff("2025-01-01T00:00:00Z", "Неназначена");
        availabilityFixtures.unassign(tenant.business(), unassigned, tenant.service());

        UUID assigned = assignedStaff(completedBooking("a", req()));

        assertThat(assigned).isEqualTo(tenant.staff());
    }

    @Test
    void aRequestedStaffMemberIsNeverReplacedByTheAssignmentRule() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Първа");
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");
        confirmedAppointment(later, at("09:00"), 30, "CONFIRMED");
        confirmedAppointment(later, at("09:30"), 30, "CONFIRMED");

        UUID assigned = assignedStaff(completedBooking("a", req().staff(later).start("10:00")));

        assertThat(assigned).isEqualTo(later);
    }

    @Test
    void aBusyRequestedStaffMemberIsASlotRejectionNotASilentReassignment() {
        UUID later = secondStaff("2026-01-03T00:00:00Z", "Втора");
        confirmedAppointment(tenant.staff(), at("10:00"), 30, "CONFIRMED");
        long customersBefore = customerCount(tenant.business());

        BookingResult result = completedBooking("a", req().staff(tenant.staff()).start("10:00"));

        assertThat(result).isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(customerCount(tenant.business())).isEqualTo(customersBefore);
        assertThat(completedBooking("b", req().staff(later).start("10:00"))).isInstanceOf(Created.class);
        assertThat(customerCount(tenant.business())).isEqualTo(customersBefore + 1);
    }

    @Test
    void theAssignedStaffMembersNameIsSnapshottedFromTheLockedRow() {
        renameFirstStaff("2026-01-02T00:00:00Z", "Мария Тестова");

        BookedAppointment booked = ((Created) completedBooking("a", req())).appointment();

        assertThat(booked.staffDisplayName()).isEqualTo("Мария Тестова");
        assertThat(appointmentRow(booked.reference()).staffDisplayName()).isEqualTo("Мария Тестова");
    }
}
