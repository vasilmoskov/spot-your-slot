package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.CustomerNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.ReviewChanged;
import bg.spotyourslot.booking.AppointmentAdministrationException.ServiceUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.ReviewedService;
import bg.spotyourslot.booking.BookingResult.Created;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import bg.spotyourslot.scheduling.AvailabilityQuery;

/**
 * Owner-created Appointments through the real orchestration, PostgreSQL, locks, and availability.
 * The clock is Tuesday 2026-09-29 11:00 in Sofia (08:00 UTC).
 */
class ManualAppointmentCreationIntegrationTests extends ManualAppointmentTest {
    @Autowired AvailabilityQuery availability;

    private void assertNothingWritten() {
        assertThat(totalAppointments()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM appointment_service").query(Long.class).single()).isZero();
    }

    // ---- success ---------------------------------------------------------------------------

    @Test
    void aSingleServiceVisitIsStoredWithServerFactsAsAManualConfirmedAppointment() {
        String customersBefore = customerSnapshot();
        clock.resetReads();

        CreatedAppointment created = create(cmd());

        Map<String, Object> row = appointmentById(created.id());
        assertThat(row.get("source")).isEqualTo("MANUAL");
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(row.get("customer_id")).isEqualTo(customerId);
        assertThat(row.get("staff_member_id")).isEqualTo(tenant.staff());
        assertThat(row.get("service_id")).isEqualTo(tenant.service());
        assertThat(row.get("customer_note")).isNull();
        assertThat(row.get("internal_note")).isNull();
        assertThat(row.get("booking_attempt_hash")).isNull();
        assertThat(row.get("request_fingerprint")).isNull();
        assertThat(row.get("fingerprint_encoding_version")).isNull();
        assertThat(row.get("fingerprint_key_version")).isNull();
        assertThat(row.get("cancelled_at")).isNull();
        assertThat(row.get("cancelled_by")).isNull();
        assertThat(row.get("duration_minutes")).isEqualTo(30);
        assertThat((BigDecimal) row.get("price_eur")).isEqualByComparingTo("10.00");
        assertThat(row.get("timezone")).isEqualTo("Europe/Sofia");
        assertThat(((Number) row.get("service_count")).intValue()).isEqualTo(1);
        assertThat(linesOf(created.id())).hasSize(1);

        assertThat(created.status()).isEqualTo("CONFIRMED");
        assertThat(created.source()).isEqualTo("MANUAL");
        assertThat(created.version()).isZero();
        assertThat(created.start()).isEqualTo(at("10:00"));
        assertThat(created.end()).isEqualTo(at("10:30"));
        assertThat(created.timezone()).isEqualTo("Europe/Sofia");
        assertThat(created.customerId()).isEqualTo(customerId);
        assertThat(created.staffMemberId()).isEqualTo(tenant.staff());
        assertThat(created.reference()).matches("[0-9A-HJKMNP-TV-Z]{10}");
        assertThat(created.internalNote()).isNull();
        assertThat(created.durationMinutes()).isEqualTo(30);
        assertThat(created.price()).isEqualByComparingTo("10.00");
        assertThat(created.createdAt()).isEqualTo(BookingHookConfiguration.NOW);

        assertThat(customerSnapshot()).isEqualTo(customersBefore);
        assertThat(clock.reads()).as("exactly one clock read per attempt").isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("main"))).hasSize(1);
        assertThat(TransactionLog.endsOf(threadOf("main"))).containsExactly(threadOf("main") + ":commit");
    }

    @Test
    void theInternalNoteIsNormalizedStoredAndReturnedButNeverTheGuestNote() {
        CreatedAppointment created = create(cmd().note("  първа\r\nвтора  "));

        assertThat(created.internalNote()).isEqualTo("първа\nвтора");
        assertThat(appointmentById(created.id()).get("internal_note")).isEqualTo("първа\nвтора");
        assertThat(appointmentById(created.id()).get("customer_note")).isNull();
        assertThat(create(cmd().start("11:00").note("   ")).internalNote()).isNull();
    }

    @Test
    void severalServicesFormOneVisitInTheStoredProfileOrderWhateverTheRequestOrder() {
        UUID banya = addService(20, "Баня");
        UUID masaj = addService(35, "Масаж");
        UUID alpha = tenant.service();
        jdbc.sql("UPDATE service SET name = 'Арника' WHERE id = :id").param("id", alpha).update();
        jdbc.sql("UPDATE service SET price = 12.50 WHERE id = :id").param("id", masaj).update();

        jdbc.sql("UPDATE staff_working_period SET end_time = '18:00' WHERE staff_member_id = :id")
                .param("id", tenant.staff()).update();

        CreatedAppointment created = create(cmd().services(masaj, banya, alpha));
        CreatedAppointment reversed = create(cmd().start("13:00").services(alpha, banya, masaj).staff(tenant.staff()));

        assertThat(created.services()).extracting(AppointmentAdministrationRecords.CreatedService::name)
                .containsExactly("Арника", "Баня", "Масаж");
        assertThat(created.services()).extracting(AppointmentAdministrationRecords.CreatedService::position)
                .containsExactly(1, 2, 3);
        assertThat(created.durationMinutes()).isEqualTo(85);
        assertThat(created.price()).isEqualByComparingTo("32.50");
        assertThat(created.end()).isEqualTo(created.start().plusSeconds(85 * 60L));
        Map<String, Object> row = appointmentById(created.id());
        assertThat(row.get("service_id")).isEqualTo(alpha);
        assertThat(row.get("service_name")).isEqualTo("Арника");
        assertThat(((Number) row.get("service_count")).intValue()).isEqualTo(3);
        assertThat(linesOf(created.id())).extracting(line -> line.get("service_name"))
                .containsExactly("Арника", "Баня", "Масаж");
        assertThat(linesOf(created.id())).extracting(line -> ((Number) line.get("position")).intValue())
                .containsExactly(1, 2, 3);
        assertThat(reversed.services()).extracting(AppointmentAdministrationRecords.CreatedService::name)
                .containsExactly("Арника", "Баня", "Масаж");
    }

    @Test
    void fiveServicesOfEightyMinutesTotalReachTheVisitCeilingExactly() {
        List<UUID> services = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            services.add(addService(96, "Услуга " + index));
        }
        jdbc.sql("UPDATE staff_working_period SET end_time = '18:00' WHERE staff_member_id = :id")
                .param("id", tenant.staff()).update();

        CreatedAppointment created = create(cmd().start("09:00").services(services.toArray(UUID[]::new)));

        assertThat(created.durationMinutes()).isEqualTo(480);
        assertThat(created.services()).hasSize(5);
        assertThat(created.price()).isEqualByComparingTo("50.00");
    }

    @Test
    void aTotalLongerThanTheVisitCeilingIsRejectedAsInvalidInputWithNoWrite() {
        List<ReviewedService> claims = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            UUID service = addService(200, "Дълга " + index);
            claims.add(reviewed(service));
        }

        assertThat(failureOf(submitCreate("a", cmd().services(claims.toArray(ReviewedService[]::new)))))
                .isInstanceOf(InvalidInput.class);
        assertNothingWritten();
    }

    // ---- request validation (after authorization and lifecycle) -------------------------------

    @Test
    void everyDomainBoundViolationIsInvalidInputWithNoWriteAndNoClockRead() {
        ReviewedService good = reviewed(tenant.service());
        List<Cmd> invalid = new ArrayList<>();
        invalid.add(cmd().noServices());
        invalid.add(cmd().services(good, good));
        invalid.add(cmd().services(new ReviewedService(tenant.service(), good.serviceName(), 0, 1000)));
        invalid.add(cmd().services(new ReviewedService(tenant.service(), good.serviceName(), 481, 1000)));
        invalid.add(cmd().services(new ReviewedService(tenant.service(), good.serviceName(), 30, -1)));
        invalid.add(cmd().services(new ReviewedService(tenant.service(), "", 30, 1000)));
        invalid.add(cmd().services(new ReviewedService(tenant.service(), "я".repeat(201), 30, 1000)));
        invalid.add(cmd().note("x".repeat(501)));
        invalid.add(cmd().note("a\u0001b"));
        invalid.add(cmd().start(Instant.parse("1999-12-31T00:00:00Z")));
        invalid.add(cmd().start(Instant.parse("2101-01-01T12:00:00Z")));
        List<ReviewedService> six = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            six.add(new ReviewedService(UUID.randomUUID(), "x", 1, 1));
        }
        invalid.add(cmd().services(six.toArray(ReviewedService[]::new)));
        clock.resetReads();

        for (Cmd command : invalid) {
            assertThat(failureOf(submitCreate("a", command))).isInstanceOf(InvalidInput.class);
        }

        assertNothingWritten();
        assertThat(clock.reads()).isZero();
    }

    // ---- Customer ------------------------------------------------------------------------------

    @Test
    void aMissingAndAForeignCustomerAreTheSameNotFoundAndWriteNothing() {
        Tenant other = fixtures.tenant();
        clock.resetReads();

        Throwable missing = failureOf(submitCreate("a", cmd().customer(UUID.randomUUID())));
        Throwable foreign = failureOf(submitCreate("b", cmd().customer(other.customer())));

        assertThat(missing).isInstanceOf(CustomerNotFound.class);
        assertThat(foreign).isInstanceOf(CustomerNotFound.class);
        assertThat(foreign.getClass()).isEqualTo(missing.getClass());
        assertThat(foreign.getMessage()).isEqualTo(missing.getMessage());
        assertNothingWritten();
        assertThat(clock.reads()).isZero();
    }

    @Test
    void customerNotFoundWinsOverEveryLaterDecisionWithAllLocksUntouched() {
        UUID inactive = fixtures.service(tenant.business(), 30);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), inactive);
        availabilityFixtures.setServiceActive(inactive, false);

        Throwable failure = failureOf(submitCreate("a", cmd()
                .customer(UUID.randomUUID())
                .staff(UUID.randomUUID())
                .services(new ReviewedService(inactive, "changed", 99, 1))
                .start("13:00")));

        assertThat(failure).isInstanceOf(CustomerNotFound.class);
    }

    // ---- eligibility -------------------------------------------------------------------------------

    @Test
    void aMissingForeignAndInactiveServiceAreTheSameUnavailableResult() {
        Tenant other = fixtures.tenant();
        UUID inactive = fixtures.service(tenant.business(), 30);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), inactive);
        availabilityFixtures.setServiceActive(inactive, false);

        Throwable missing = failureOf(submitCreate("a",
                cmd().services(new ReviewedService(UUID.randomUUID(), "x", 30, 1000))));
        Throwable foreign = failureOf(submitCreate("b", cmd().services(reviewed(other.service()))));
        Throwable off = failureOf(submitCreate("c", cmd().services(reviewed(inactive))));

        for (Throwable failure : List.of(missing, foreign, off)) {
            assertThat(failure).isInstanceOf(ServiceUnavailable.class);
            assertThat(failure.getMessage()).isEqualTo(missing.getMessage());
        }
        assertNothingWritten();
    }

    @Test
    void aMissingForeignInactiveAndUnassignedStaffMemberAreTheSameUnavailableResult() {
        Tenant other = fixtures.tenant();
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), inactive, "09:00", "12:00");
        availabilityFixtures.setStaffMemberActive(inactive, false);
        UUID unassigned = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.unassign(tenant.business(), unassigned, tenant.service());
        availabilityFixtures.everyDay(tenant.business(), unassigned, "09:00", "12:00");

        List<Throwable> failures = new ArrayList<>();
        for (UUID staff : List.of(UUID.randomUUID(), other.staff(), inactive, unassigned)) {
            failures.add(failureOf(submitCreate("s", cmd().staff(staff))));
        }

        for (Throwable failure : failures) {
            assertThat(failure).isInstanceOf(StaffUnavailable.class);
            assertThat(failure.getMessage()).isEqualTo(failures.get(0).getMessage());
        }
        assertNothingWritten();
    }

    @Test
    void theStaffMemberMustBeAssignedToEveryServiceOfTheVisit() {
        UUID extra = fixtures.service(tenant.business(), 30);

        Throwable failure = failureOf(submitCreate("a", cmd().services(tenant.service(), extra)));

        assertThat(failure).isInstanceOf(StaffUnavailable.class);
        assertNothingWritten();
    }

    @Test
    void serviceUnavailabilityIsDecidedBeforeStaffEligibilityBeforeReviewBeforeTheSlot() {
        UUID inactive = fixtures.service(tenant.business(), 30);
        availabilityFixtures.assign(tenant.business(), tenant.staff(), inactive);
        availabilityFixtures.setServiceActive(inactive, false);
        ReviewedService stale = new ReviewedService(tenant.service(), "Стар", 99, 1);

        assertThat(failureOf(submitCreate("a", cmd()
                .services(reviewed(inactive)).staff(UUID.randomUUID()).start("13:00"))))
                .isInstanceOf(ServiceUnavailable.class);
        assertThat(failureOf(submitCreate("b", cmd()
                .services(stale).staff(UUID.randomUUID()).start("13:00"))))
                .isInstanceOf(StaffUnavailable.class);
        assertThat(failureOf(submitCreate("c", cmd().services(stale).start("13:00"))))
                .isInstanceOf(ReviewChanged.class);
        assertThat(failureOf(submitCreate("d", cmd().start("13:00")))).isInstanceOf(SlotUnavailable.class);
        assertNothingWritten();
    }

    // ---- reviewed terms --------------------------------------------------------------------------------

    @Test
    void anyDifferenceInTheReviewedNameDurationOrPriceIsReviewChangedWithZeroWrites() {
        ReviewedService now = reviewed(tenant.service());
        List<ReviewedService> stale = List.of(
                new ReviewedService(now.serviceId(), now.serviceName() + " ", now.durationMinutes(), now.priceCents()),
                new ReviewedService(now.serviceId(), now.serviceName().toLowerCase() + "x",
                        now.durationMinutes(), now.priceCents()),
                new ReviewedService(now.serviceId(), now.serviceName(), now.durationMinutes() + 1, now.priceCents()),
                new ReviewedService(now.serviceId(), now.serviceName(), now.durationMinutes(), now.priceCents() + 1),
                new ReviewedService(now.serviceId(), now.serviceName(), now.durationMinutes(), now.priceCents() - 1));

        for (ReviewedService claim : stale) {
            assertThat(failureOf(submitCreate("a", cmd().services(claim)))).isInstanceOf(ReviewChanged.class);
        }

        assertNothingWritten();
    }

    @Test
    void aServiceChangedAfterReviewIsReviewChangedAndNothingIndicatesWhichOne() {
        UUID other = addService(20, "Втора");
        Cmd command = cmd().services(tenant.service(), other);
        jdbc.sql("UPDATE service SET price = 99.99 WHERE id = :id").param("id", other).update();

        Throwable failure = failureOf(submitCreate("a", command));

        assertThat(failure).isInstanceOf(ReviewChanged.class);
        assertThat(failure.getMessage()).doesNotContain(other.toString()).doesNotContain("99");
        assertNothingWritten();
    }

    @Test
    void claimsArePairedByServiceIdentifierNotByListPosition() {
        UUID banya = addService(20, "Баня");
        ReviewedService a = reviewed(tenant.service());
        ReviewedService b = reviewed(banya);

        assertThat(create(cmd().services(b, a)).services()).hasSize(2);
        // Swapping the terms between the two identifiers is a difference, not a reordering.
        ReviewedService swapped = new ReviewedService(a.serviceId(), b.serviceName(), b.durationMinutes(), b.priceCents());
        assertThat(failureOf(submitCreate("a", cmd().start("11:00").services(swapped, b))))
                .isInstanceOf(ReviewChanged.class);
    }

    // ---- time -------------------------------------------------------------------------------------------

    @Test
    void zeroNoticeAWalkInStartingExactlyNowIsOffered() {
        // NOW is 11:00 Sofia; the public policy would require 13:00 (and the staff stops at 12:00).
        CreatedAppointment created = create(cmd().start(BookingHookConfiguration.NOW));

        assertThat(created.start()).isEqualTo(BookingHookConfiguration.NOW);
    }

    @Test
    void aStartBeforeNowOffGridOrOutsideWorkingTimeIsSlotUnavailable() {
        Instant now = BookingHookConfiguration.NOW;
        List<Instant> starts = List.of(
                now.minusSeconds(15 * 60), now.minusSeconds(1), now.plusSeconds(7 * 60), at("13:00"), at("08:45"));
        jdbc.sql("UPDATE staff_working_period SET end_time = '12:00'").update();

        for (Instant start : starts) {
            assertThat(failureOf(submitCreate("a", cmd().start(start)))).as(start.toString())
                    .isInstanceOf(SlotUnavailable.class);
        }
        assertNothingWritten();
    }

    @Test
    void thereIsNoBookingHorizonAStartMonthsAwayIsOffered() {
        Instant far = LocalDate.of(2027, 3, 4).atTime(10, 0).atZone(SOFIA).toInstant();

        assertThat(create(cmd().start(far)).start()).isEqualTo(far);
    }

    @Test
    void aTimeOfDayInTheAutumnRepeatedHourIsTwoDistinctInstantsEachJudgedByItsOwnInstant() {
        availabilityFixtures.everyDay(tenant.business(), tenant.staff(), "01:00", "06:00");
        LocalDate repeated = LocalDate.of(2026, 10, 25);
        Instant first = repeated.atTime(3, 30).atZone(SOFIA).withEarlierOffsetAtOverlap().toInstant();
        Instant second = repeated.atTime(3, 30).atZone(SOFIA).withLaterOffsetAtOverlap().toInstant();
        assertThat(second).isEqualTo(first.plusSeconds(3600));

        CreatedAppointment a = create(cmd().start(first));
        CreatedAppointment b = create(cmd().start(second));

        assertThat(a.start()).isEqualTo(first);
        assertThat(b.start()).isEqualTo(second);
        assertThat(a.start()).isNotEqualTo(b.start());
    }

    @Test
    void theSpringForwardGapContributesNoStartAndTheFirstOfferedInstantIsAccepted() {
        availabilityFixtures.everyDay(tenant.business(), tenant.staff(), "02:00", "06:00");
        LocalDate gapDay = LocalDate.of(2027, 3, 28);
        Instant beforeGap = gapDay.atTime(2, 30).atZone(SOFIA).toInstant();
        Instant afterGap = gapDay.atTime(4, 0).atZone(SOFIA).toInstant();
        assertThat(afterGap).isEqualTo(Instant.parse("2027-03-28T01:00:00Z"));
        // 03:30 wall-clock does not exist; the instant 30 minutes after 02:30 EET is already 04:00 EEST.
        List<Instant> offered = offeredOn(gapDay, 30);
        assertThat(offered).contains(beforeGap, afterGap);
        assertThat(offered).noneMatch(start -> start.atZone(SOFIA).getHour() == 3);

        assertThat(create(cmd().start(afterGap)).start()).isEqualTo(afterGap);
        assertThat(failureOf(submitCreate("a", cmd().start(afterGap.minusSeconds(15 * 60 * 3 + 1)))))
                .isInstanceOf(SlotUnavailable.class);
    }

    private List<Instant> offeredOn(LocalDate date, int minutes) {
        return snapshotTransaction().execute(status -> availability.calculateForBusiness(
                        tenant.business(), java.time.Duration.ofMinutes(minutes), tenant.staff(),
                        date, date, BookingHookConfiguration.NOW, null)
                .slots().stream().map(slot -> slot.start()).toList());
    }

    @Test
    void anAdjacentStartThatTouchesAnExistingVisitIsAcceptedAndAnOverlappingOneIsNot() {
        create(cmd().start("10:00"));

        assertThat(create(cmd().start("10:30")).start()).isEqualTo(at("10:30"));
        assertThat(create(cmd().start("09:30")).start()).isEqualTo(at("09:30"));
        assertThat(failureOf(submitCreate("a", cmd().start("10:15")))).isInstanceOf(SlotUnavailable.class);
        assertThat(totalAppointments()).isEqualTo(3);
    }

    @Test
    void aVisitMustFitTheWorkingPeriodAsOneContinuousInterval() {
        UUID banya = addService(60, "Баня");

        // 60 + 30 minutes starting 11:00 would end 12:30, after the 12:00 end of the period.
        assertThat(failureOf(submitCreate("a", cmd().start("11:00").services(tenant.service(), banya))))
                .isInstanceOf(SlotUnavailable.class);
        assertThat(create(cmd().start("10:30").services(tenant.service(), banya)).end()).isEqualTo(at("12:00"));
    }

    // ---- lifecycle and authorization --------------------------------------------------------------------------

    @Test
    void aDraftBusinessRejectsWithBusinessNotActiveAndASuspendedOneWithBusinessSuspended() {
        availabilityFixtures.setBusinessStatus(tenant.business(), "DRAFT");
        assertThat(failureOf(submitCreate("a", cmd()))).isInstanceOf(BusinessNotActive.class);
        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");
        assertThat(failureOf(submitCreate("b", cmd()))).isInstanceOf(BusinessSuspended.class);
        assertNothingWritten();
        availabilityFixtures.setBusinessStatus(tenant.business(), "ACTIVE");
        assertThat(create(cmd()).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void lifecycleResultsTakePrecedenceOverInvalidDomainValuesAndAMissingCustomer() {
        availabilityFixtures.setBusinessStatus(tenant.business(), "DRAFT");
        assertThat(failureOf(submitCreate("a", cmd().noServices().customer(UUID.randomUUID()))))
                .isInstanceOf(BusinessNotActive.class);
        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");
        assertThat(failureOf(submitCreate("b", cmd().noServices().customer(UUID.randomUUID()))))
                .isInstanceOf(BusinessSuspended.class);
    }

    @Test
    void onlyAnActiveOwnerOfTheSelectedBusinessMayCreateAndEveryOtherCallerIsDenied() {
        Tenant other = fixtures.tenant();
        AuthenticatedBusinessContext foreignOwner = owner(other.business());
        AuthenticatedBusinessContext manager = membership("MANAGER", true);
        AuthenticatedBusinessContext staff = membership("STAFF", true);
        AuthenticatedBusinessContext inactiveOwner = membership("BUSINESS_OWNER", false);
        AuthenticatedBusinessContext stranger = new OwnerContext(UUID.randomUUID(), tenant.business());
        AuthenticatedBusinessContext foreignSelection = new OwnerContext(
                ((OwnerContext) foreignOwner).userId(), tenant.business());

        for (AuthenticatedBusinessContext caller : List.of(manager, staff, inactiveOwner, stranger, foreignSelection)) {
            assertThat(failureOf(submitCreate("a", caller, cmd().build()))).isInstanceOf(AccessDenied.class);
        }
        assertThat(failureOf(submitCreate("b", new OwnerContext(ownerContext.userId(), UUID.randomUUID()),
                cmd().build()))).isInstanceOf(AccessDenied.class);
        assertNothingWritten();
    }

    private AuthenticatedBusinessContext membership(String role, boolean active) {
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,
                            password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Other','hash',:now,:now,:now)
                        """)
                .param("id", user)
                .param("email", user + "@example.invalid")
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        jdbc.sql("""
                        INSERT INTO membership(id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:businessId,:userId,:role,:active,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", tenant.business())
                .param("userId", user)
                .param("role", role)
                .param("active", active)
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        return new OwnerContext(user, tenant.business());
    }

    // ---- no interference with guest booking ------------------------------------------------------------------------

    @Test
    void aManualVisitBlocksTheGuestSlotAndAGuestVisitBlocksTheManualSlot() {
        create(cmd().start("10:00"));

        assertThat(book(req().staff(tenant.staff()).start("10:00").build()))
                .isEqualTo(new BookingResult.SlotUnavailable());
        assertThat(book(req().staff(tenant.staff()).start("11:00").build())).isInstanceOf(Created.class);
        assertThat(failureOf(submitCreate("a", cmd().start("11:00")))).isInstanceOf(SlotUnavailable.class);
    }

    @Test
    void theManualRowNeverLeaksIntoTheGuestReplayOfAnotherAttempt() {
        CreatedAppointment manual = create(cmd().note("частна-бележка-42").start("09:00"));
        Req guest = req().staff(tenant.staff()).start("10:00");

        BookingResult.Created created = (BookingResult.Created) book(guest.build());
        BookingResult replay = book(guest.copy().build());

        assertThat(replay.toString()).doesNotContain("частна-бележка-42").doesNotContain(manual.reference());
        assertThat(Optional.of(created)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void severalCreationsOfDifferentTimesAllSucceedAndKeepExactlyOneRowEach(int count) {
        for (int index = 0; index < count; index++) {
            create(cmd().start(Instant.parse("2026-10-01T06:00:00Z").plusSeconds(1800L * index)));
        }

        assertThat(totalAppointments()).isEqualTo(count);
        assertThat(linesCount(tenant.business())).isEqualTo(count);
    }
}
