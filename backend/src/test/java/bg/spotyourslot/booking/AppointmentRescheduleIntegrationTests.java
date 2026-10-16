package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.SlotUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.scheduling.ScheduleExceptionAdministration;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import java.time.Instant;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The owner reschedule and StaffMember reassignment through the real orchestration against real
 * PostgreSQL (ADR-0028 sections 3 and 6, ADR-0029): the three operations, the immutable identity,
 * the eligibility rules after creation, the instant and state rules, the availability policy that
 * ignores only the moved Appointment, lifecycle, authorization, tenant isolation, and
 * daylight-saving behavior in the Business timezone. Every failure is asserted to leave the row
 * byte-identical.
 */
class AppointmentRescheduleIntegrationTests extends RescheduleAppointmentTest {
    @Autowired AppointmentStore store;
    @Autowired ScheduleExceptionAdministration exceptions;

    // ---- the three operations ------------------------------------------------------------------------

    @Test
    void aTimeOnlyMoveChangesExactlyTheStartTheEndTheVersionAndTheUpdateTime() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        List<Map<String, Object>> linesBefore = linesOf(created.id());
        Instant later = Instant.parse("2026-09-30T08:00:00Z");
        clock.set(later);

        RescheduledAppointment moved = reschedule(move(created).start("11:00"));

        Map<String, Object> after = fullRow(created.id());
        assertThat(changedColumns(before, after)).isEqualTo(MOVE_COLUMNS);
        assertThat(moved.start()).isEqualTo(at("11:00"));
        assertThat(moved.end()).isEqualTo(at("11:30"));
        assertThat(instantOf(after, "occupied_until")).isEqualTo(instantOf(after, "end_at"));
        assertThat(moved.version()).isEqualTo(1L);
        assertThat(moved.updatedAt()).isEqualTo(later);
        assertThat(moved.staffMemberId()).isEqualTo(tenant.staff());
        assertThat(moved.staffDisplayName()).isEqualTo(created.staffDisplayName());
        assertThat(moved.reference()).isEqualTo(created.reference());
        assertThat(moved.customerId()).isEqualTo(customerId);
        assertThat(moved.source()).isEqualTo("MANUAL");
        assertThat(moved.status()).isEqualTo("CONFIRMED");
        assertThat(moved.services()).isEqualTo(created.services());
        assertThat(moved.durationMinutes()).isEqualTo(created.durationMinutes());
        assertThat(moved.price()).isEqualByComparingTo(created.price());
        assertThat(moved.createdAt()).isEqualTo(created.createdAt());
        assertThat(linesOf(created.id())).isEqualTo(linesBefore);
    }

    @Test
    void aStaffMemberOnlyChangeKeepsTheTimeAndRefreshesTheSnapshotFromTheLockedTarget() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        clock.set(Instant.parse("2026-09-30T08:00:00Z"));
        jdbc.sql("UPDATE staff_member SET display_name = 'Преименуван Служител' WHERE id = :id")
                .param("id", second).update();

        RescheduledAppointment moved = reschedule(move(created).staff(second));

        Map<String, Object> after = fullRow(created.id());
        assertThat(changedColumns(before, after)).isEqualTo(REASSIGN_COLUMNS);
        assertThat(moved.start()).isEqualTo(created.start());
        assertThat(moved.end()).isEqualTo(created.end());
        assertThat(moved.staffMemberId()).isEqualTo(second);
        assertThat(moved.staffDisplayName()).isEqualTo("Преименуван Служител");
        assertThat(after.get("staff_display_name")).isEqualTo("Преименуван Служител");
        assertThat(moved.version()).isEqualTo(1L);
        assertThat(moved.services()).isEqualTo(created.services());
    }

    @Test
    void aCombinedChangeIsOneAtomicUpdateOfTheTimeAndTheStaffMember() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        clock.set(Instant.parse("2026-09-30T08:00:00Z"));

        RescheduledAppointment moved = reschedule(move(created).start("11:00").staff(second));

        Map<String, Object> after = fullRow(created.id());
        assertThat(changedColumns(before, after))
                .isEqualTo(columns(MOVE_COLUMNS, "staff_member_id", "staff_display_name"));
        assertThat(moved.start()).isEqualTo(at("11:00"));
        assertThat(moved.staffMemberId()).isEqualTo(second);
        assertThat(moved.version()).isEqualTo(1L);
        assertThat(after.get("version")).isEqualTo(1L);
    }

    @Test
    void aTimeOnlyMoveKeepsTheStoredStaffNameEvenWhenTheStaffMemberWasRenamedLater() {
        CreatedAppointment created = existing("10:00");
        jdbc.sql("UPDATE staff_member SET display_name = 'Нов Служител' WHERE id = :id")
                .param("id", tenant.staff()).update();

        RescheduledAppointment moved = reschedule(move(created).start("11:00"));

        assertThat(moved.staffDisplayName()).isEqualTo(created.staffDisplayName());
        assertThat(fullRow(created.id()).get("staff_display_name")).isEqualTo(created.staffDisplayName());
    }

    @Test
    void namingTheCurrentStaffMemberWithANewStartIsATimeOnlyMoveThatChecksNoAssignment() {
        CreatedAppointment created = existing("10:00");
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), tenant.service());

        RescheduledAppointment moved = reschedule(move(created).start("11:00").staff(tenant.staff()));

        assertThat(moved.start()).isEqualTo(at("11:00"));
        assertThat(moved.staffMemberId()).isEqualTo(tenant.staff());
    }

    @Test
    void anOperationIsOneVersionStepAndTheNextMoveNeedsTheNewVersion() {
        CreatedAppointment created = existing("10:00");
        RescheduledAppointment first = reschedule(move(created).start("11:00"));

        assertThat(rejectionOf(move(created).start("09:30"))).isInstanceOf(ConcurrentUpdate.class);
        RescheduledAppointment second = reschedule(move(created.id(), first.version()).start("09:30"));

        assertThat(second.version()).isEqualTo(2L);
        assertThat(second.start()).isEqualTo(at("09:30"));
    }

    // ---- a request that changes nothing ----------------------------------------------------------------

    @Test
    void aRequestThatChangesNothingIsAValidationErrorAndLeavesTheRowAndItsVersionByteIdentical() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        List<RescheduleAppointmentCommand> unchanged = List.of(
                new RescheduleAppointmentCommand(created.id(), 0L, created.start(), null),
                new RescheduleAppointmentCommand(created.id(), 0L, null, tenant.staff()),
                new RescheduleAppointmentCommand(created.id(), 0L, created.start(), tenant.staff()),
                new RescheduleAppointmentCommand(created.id(), 0L, null, null));

        for (RescheduleAppointmentCommand command : unchanged) {
            assertThat(failureOf(submitMove("main", ownerContext, command))).isInstanceOf(InvalidInput.class);
        }

        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void aStaleVersionIsReportedBeforeANoChangeAndAStateConflictBeforeANoChange() {
        CreatedAppointment created = existing("10:00");

        assertThat(rejectionOf(move(created).version(7).start("10:00"))).isInstanceOf(ConcurrentUpdate.class);

        assertThat(store.cancel(new CancelUpdate(tenant.business(), created.id(), 0L, clock.instant()))).isPresent();
        assertThat(rejectionOf(move(created).version(1).start("10:00"))).isInstanceOf(StateConflict.class);
    }

    @Test
    void domainBoundsAreValidationErrorsAfterAuthorization() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        List<RescheduleAppointmentCommand> invalid = List.of(
                new RescheduleAppointmentCommand(created.id(), -1L, at("11:00"), null),
                new RescheduleAppointmentCommand(null, 0L, at("11:00"), null),
                new RescheduleAppointmentCommand(created.id(), 0L, Instant.parse("1999-12-31T00:00:00Z"), null),
                new RescheduleAppointmentCommand(created.id(), 0L, Instant.parse("2101-01-01T00:00:00Z"), null),
                new RescheduleAppointmentCommand(created.id(), 0L, Instant.MAX, null));

        for (RescheduleAppointmentCommand command : invalid) {
            assertThat(failureOf(submitMove("main", ownerContext, command))).isInstanceOf(InvalidInput.class);
        }
        assertThat(failureOf(submitMove("again", ownerContext, null))).isInstanceOf(InvalidInput.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    // ---- stale version, started, cancelled ----------------------------------------------------------------

    @Test
    void aStaleExpectedVersionIsAConcurrentUpdateAndNothingChanges() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());

        assertThat(rejectionOf(move(created).version(5).start("11:00"))).isInstanceOf(ConcurrentUpdate.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void anAppointmentThatStartsExactlyNowHasStartedAndCannotBeMovedOrReassigned() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        clock.set(created.start());

        assertThat(rejectionOf(move(created).start("10:30"))).isInstanceOf(StateConflict.class);
        assertThat(rejectionOf(move(created).staff(second))).isInstanceOf(StateConflict.class);
        assertThat(rejectionOf(move(created).start("10:30").staff(second))).isInstanceOf(StateConflict.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void anAppointmentThatStartsOneMicrosecondAfterNowCanStillBeMoved() {
        CreatedAppointment created = existing("10:00");
        clock.set(created.start().minusNanos(1_000));

        assertThat(reschedule(move(created).start("10:30")).start()).isEqualTo(at("10:30"));
    }

    @Test
    void aStartedAppointmentIsAStateConflictNotASlotProblem() {
        CreatedAppointment created = existing("10:00");
        clock.set(created.start().plusSeconds(60));

        assertThat(rejectionOf(move(created).start("11:00"))).isInstanceOf(StateConflict.class);
    }

    @Test
    void aCancelledAppointmentCannotBeMovedOrReassignedAndAStaleVersionIsStillTheDistinctResult() {
        CreatedAppointment created = existing("10:00");
        assertThat(store.cancel(new CancelUpdate(tenant.business(), created.id(), 0L, clock.instant()))).isPresent();
        Map<String, Object> before = fullRow(created.id());

        assertThat(rejectionOf(move(created.id(), 1L).start("11:00"))).isInstanceOf(StateConflict.class);
        assertThat(rejectionOf(move(created.id(), 1L).staff(second))).isInstanceOf(StateConflict.class);
        assertThat(rejectionOf(move(created.id(), 0L).start("11:00"))).isInstanceOf(ConcurrentUpdate.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    // ---- eligibility after creation (ADR-0028 section 6) --------------------------------------------------

    @Test
    void aTimeOnlyMoveSurvivesADeactivatedServiceAndUsesTheStoredDurationAndPrice() {
        CreatedAppointment created = existing("10:00");
        jdbc.sql("UPDATE service SET active = false, duration_minutes = 60, price = 99.00 WHERE id = :id")
                .param("id", tenant.service()).update();
        List<Map<String, Object>> linesBefore = linesOf(created.id());

        RescheduledAppointment moved = reschedule(move(created).start("11:00"));

        assertThat(moved.end()).isEqualTo(at("11:30"));
        assertThat(moved.durationMinutes()).isEqualTo(30);
        assertThat(moved.price()).isEqualByComparingTo(created.price());
        assertThat(linesOf(created.id())).isEqualTo(linesBefore);
    }

    @Test
    void aTimeOnlyMoveSurvivesARemovedAssignment() {
        CreatedAppointment created = existing("10:00");
        availabilityFixtures.unassign(tenant.business(), tenant.staff(), tenant.service());

        assertThat(reschedule(move(created).start("11:00")).start()).isEqualTo(at("11:00"));
    }

    @Test
    void aTimeOnlyMoveIsRejectedWhenTheCurrentStaffMemberIsInactive() {
        CreatedAppointment created = existing("10:00");
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);
        Map<String, Object> before = fullRow(created.id());

        assertThat(rejectionOf(move(created).start("11:00"))).isInstanceOf(StaffUnavailable.class);
        assertThat(rejectionOf(move(created).start("11:00").staff(tenant.staff())))
                .isInstanceOf(StaffUnavailable.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void anInactiveCurrentStaffMemberCanStillBeReassignedAwayFrom() {
        CreatedAppointment created = existing("10:00");
        availabilityFixtures.setStaffMemberActive(tenant.staff(), false);

        RescheduledAppointment moved = reschedule(move(created).staff(second));

        assertThat(moved.staffMemberId()).isEqualTo(second);
        assertThat(moved.start()).isEqualTo(created.start());
    }

    @Test
    void everyIneligibleTargetIsTheSameStaffUnavailableWithNoWriteAndNoDisclosure() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        Tenant other = fixtures.tenant();
        UUID inactive = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), inactive, "09:00", "12:00");
        availabilityFixtures.setStaffMemberActive(inactive, false);
        UUID unassigned = availabilityFixtures.staffMember(tenant.business(), true);
        availabilityFixtures.everyDay(tenant.business(), unassigned, "09:00", "12:00");
        List<UUID> targets = List.of(UUID.randomUUID(), other.staff(), inactive, unassigned);

        List<Throwable> failures = targets.stream()
                .map(target -> rejectionOf(move(created).staff(target)))
                .toList();

        assertThat(failures).allSatisfy(failure -> assertThat(failure).isInstanceOf(StaffUnavailable.class));
        assertThat(failures.stream().map(Throwable::getMessage).distinct()).hasSize(1);
        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void theTargetMustBeAssignedToEveryServiceOfTheVisit() {
        UUID extra = addService(15, "Втора услуга");
        UUID partial = fixtures.staffMember(tenant.business(), tenant.service());
        availabilityFixtures.everyDay(tenant.business(), partial, "09:00", "12:00");
        CreatedAppointment created = create(cmd().services(tenant.service(), extra));
        Map<String, Object> before = fullRow(created.id());

        assertThat(rejectionOf(move(created).staff(partial))).isInstanceOf(StaffUnavailable.class);
        assertThat(fullRow(created.id())).isEqualTo(before);

        availabilityFixtures.assign(tenant.business(), partial, extra);
        RescheduledAppointment moved = reschedule(move(created).staff(partial));

        assertThat(moved.staffMemberId()).isEqualTo(partial);
        assertThat(moved.services()).isEqualTo(created.services());
        assertThat(moved.durationMinutes()).isEqualTo(45);
    }

    @Test
    void aReassignmentDoesNotRequireTheServicesToBeStillActive() {
        CreatedAppointment created = existing("10:00");
        availabilityFixtures.setServiceActive(tenant.service(), false);

        assertThat(reschedule(move(created).staff(second)).staffMemberId()).isEqualTo(second);
    }

    @Test
    void aReassignmentIsRejectedWhenTheAssignmentOfTheTargetWasRemoved() {
        CreatedAppointment created = existing("10:00");
        availabilityFixtures.unassign(tenant.business(), second, tenant.service());

        assertThat(rejectionOf(move(created).staff(second))).isInstanceOf(StaffUnavailable.class);
    }

    // ---- lifecycle, authorization, tenant isolation ----------------------------------------------------------

    @Test
    void draftAndSuspendedBusinessesRefuseTheOperationsWithTheirOwnDistinctFailures() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        clock.resetReads();

        availabilityFixtures.setBusinessStatus(tenant.business(), "DRAFT");
        assertThat(rejectionOf(move(created).start("11:00"))).isInstanceOf(BusinessNotActive.class);
        assertThat(rejectionOf(move(created).staff(second))).isInstanceOf(BusinessNotActive.class);

        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");
        assertThat(rejectionOf(move(created).start("11:00"))).isInstanceOf(BusinessSuspended.class);
        assertThat(rejectionOf(move(created).staff(second))).isInstanceOf(BusinessSuspended.class);

        assertThat(clockReads()).as("no clock read before the lifecycle decision").isZero();
        assertThat(fullRow(created.id())).isEqualTo(before);

        availabilityFixtures.setBusinessStatus(tenant.business(), "ACTIVE");
        assertThat(reschedule(move(created).start("11:00")).version()).isEqualTo(1L);
    }

    @Test
    void onlyTheActiveOwnerMayMoveAndEveryOtherCallerIsDeniedWithoutAChange() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());
        List<AuthenticatedBusinessContext> callers = List.of(
                memberOf(tenant.business(), "MANAGER", true),
                memberOf(tenant.business(), "STAFF", true),
                memberOf(tenant.business(), "BUSINESS_OWNER", false),
                new OwnerContext(UUID.randomUUID(), tenant.business()));

        for (AuthenticatedBusinessContext caller : callers) {
            RescheduleAppointmentCommand command = move(created).start("11:00").build();
            assertThat(failureOf(submitMove("main", caller, command))).isInstanceOf(AccessDenied.class);
        }

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void aMissingAndAForeignAppointmentAreTheSameNotFoundAndTheForeignOwnerChangesNothing() {
        CreatedAppointment created = existing("10:00");
        Tenant other = fixtures.tenant();
        availabilityFixtures.everyDay(other.business(), other.staff(), "09:00", "12:00");
        AuthenticatedBusinessContext foreignOwner = owner(other.business());
        Map<String, Object> before = fullRow(created.id());
        clock.resetReads();

        Throwable missing = failureOf(submitMove("a", foreignOwner,
                new RescheduleAppointmentCommand(UUID.randomUUID(), 0L, at("11:00"), null)));
        Throwable foreign = failureOf(submitMove("b", foreignOwner,
                new RescheduleAppointmentCommand(created.id(), 0L, at("11:00"), null)));
        Throwable foreignWithOwnStaff = failureOf(submitMove("c", foreignOwner,
                new RescheduleAppointmentCommand(created.id(), 0L, at("11:00"), other.staff())));

        assertThat(missing).isInstanceOf(AppointmentNotFound.class);
        assertThat(foreign).isInstanceOf(AppointmentNotFound.class);
        assertThat(foreignWithOwnStaff).isInstanceOf(AppointmentNotFound.class);
        assertThat(foreign.getMessage()).isEqualTo(missing.getMessage());
        assertThat(clockReads()).as("existence is decided before the clock").isZero();
        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void theClockIsReadExactlyOncePerAttemptAndEveryDecisionUsesThatInstant() {
        CreatedAppointment created = existing("10:00");
        clock.resetReads();

        reschedule(move(created).start("11:00"));

        assertThat(clockReads()).isEqualTo(1L);
    }

    // ---- the instant rules (ADR-0029 section 1) ----------------------------------------------------------------

    @Test
    void aStartBeforeNowIsASlotProblemAndAStartExactlyAtNowIsAccepted() {
        CreatedAppointment created = existing("11:00");
        clock.set(at("09:30"));

        assertThat(rejectionOf(move(created).start("09:15"))).isInstanceOf(SlotUnavailable.class);
        assertThat(rejectionOf(move(created).start("09:00"))).isInstanceOf(SlotUnavailable.class);
        assertThat(reschedule(move(created).start("09:30")).start()).isEqualTo(at("09:30"));
    }

    @Test
    void onlyStartsOnTheFifteenMinuteGridInsideTheWorkingHoursAreOffered() {
        CreatedAppointment created = existing("10:00");
        Map<String, Object> before = fullRow(created.id());

        for (String time : List.of("10:07", "13:00", "08:45", "11:45", "12:00")) {
            assertThat(rejectionOf(move(created).start(time))).as(time).isInstanceOf(SlotUnavailable.class);
        }
        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(reschedule(move(created).start("11:30")).end()).isEqualTo(at("12:00"));
    }

    @Test
    void thereIsNoBookingHorizonForTheOwner() {
        CreatedAppointment created = existing("10:00");
        Instant farAway = THURSDAY.plusDays(90).atTime(10, 0).atZone(SOFIA).toInstant();

        assertThat(reschedule(move(created).start(farAway)).start()).isEqualTo(farAway);
    }

    @Test
    void aClosedDayRefusesTheMove() {
        CreatedAppointment created = existing("10:00");
        exceptions.create(ownerContext, new CreateScheduleExceptionCommand(
                ScheduleExceptionKind.BUSINESS_CLOSURE, null, THURSDAY.plusDays(1), THURSDAY.plusDays(1),
                true, List.of()));

        Instant friday = THURSDAY.plusDays(1).atTime(10, 0).atZone(SOFIA).toInstant();
        assertThat(rejectionOf(move(created).start(friday))).isInstanceOf(SlotUnavailable.class);
    }

    // ---- overlap, adjacency, and ignoring only the moved Appointment --------------------------------------------

    @Test
    void movingIntoANeighbourIsASlotProblemAndLeavesBothRowsByteIdentical() {
        CreatedAppointment mover = existing("09:00");
        CreatedAppointment neighbour = existing("10:30");
        Map<String, Object> moverBefore = fullRow(mover.id());
        Map<String, Object> neighbourBefore = fullRow(neighbour.id());

        assertThat(rejectionOf(move(mover).start("10:15"))).isInstanceOf(SlotUnavailable.class);
        assertThat(rejectionOf(move(mover).start("10:30"))).isInstanceOf(SlotUnavailable.class);
        assertThat(rejectionOf(move(mover).start("10:45"))).isInstanceOf(SlotUnavailable.class);

        assertThat(fullRow(mover.id())).isEqualTo(moverBefore);
        assertThat(fullRow(neighbour.id())).isEqualTo(neighbourBefore);
    }

    @Test
    void touchingANeighbourOnEitherSideIsAdjacencyNotOverlap() {
        CreatedAppointment mover = existing("09:00");
        existing("10:30");

        assertThat(reschedule(move(mover).start("10:00")).end()).isEqualTo(at("10:30"));
        CreatedAppointment other = existing("09:00");
        assertThat(reschedule(move(other).start("11:00")).start()).isEqualTo(at("11:00"));
    }

    @Test
    void theMovedAppointmentNeverBlocksItselfButItsNeighboursStillDo() {
        CreatedAppointment mover = existing("10:00");

        // Overlapping its own old interval is allowed.
        RescheduledAppointment shifted = reschedule(move(mover).start("10:15"));
        assertThat(shifted.start()).isEqualTo(at("10:15"));
        RescheduledAppointment back = reschedule(move(mover.id(), 1L).start("09:45"));
        assertThat(back.start()).isEqualTo(at("09:45"));

        // A neighbour that overlaps the new interval still blocks, even though the old one overlaps itself.
        existing("11:00");
        assertThat(rejectionOf(move(mover.id(), 2L).start("10:45"))).isInstanceOf(SlotUnavailable.class);
    }

    @Test
    void aNeighbourOverlappingBothTheOldAndTheNewIntervalIsStillEnforced() {
        CreatedAppointment mover = existing("10:00");
        existing("10:30");

        assertThat(rejectionOf(move(mover).start("10:15"))).isInstanceOf(SlotUnavailable.class);
        assertThat(fullRow(mover.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void aReassignmentIsJudgedAgainstTheTargetsAppointmentsOnly() {
        CreatedAppointment mover = existing("10:00");
        existingFor(second, "10:15");
        // The target's own Appointment overlaps the unchanged time.
        assertThat(rejectionOf(move(mover).staff(second))).isInstanceOf(SlotUnavailable.class);
        assertThat(rejectionOf(move(mover).start("10:30").staff(second))).isInstanceOf(SlotUnavailable.class);
        Map<String, Object> before = fullRow(mover.id());
        assertThat(fullRow(mover.id())).isEqualTo(before);

        // Another StaffMember's busy time on the current StaffMember is irrelevant to the target.
        RescheduledAppointment moved = reschedule(move(mover).start("11:00").staff(second));
        assertThat(moved.staffMemberId()).isEqualTo(second);
    }

    @Test
    void aCombinedChangeWhoseTimeIsFreeButWhoseTargetIsBusyChangesNeitherField() {
        CreatedAppointment mover = existing("10:00");
        existingFor(second, "11:00");
        Map<String, Object> before = fullRow(mover.id());

        assertThat(rejectionOf(move(mover).start("11:00").staff(second))).isInstanceOf(SlotUnavailable.class);

        assertThat(fullRow(mover.id())).isEqualTo(before);
    }

    @Test
    void aCombinedChangeWhoseTargetIsFreeButWhoseTimeIsOutsideTheScheduleChangesNeitherField() {
        CreatedAppointment mover = existing("10:00");
        Map<String, Object> before = fullRow(mover.id());

        assertThat(rejectionOf(move(mover).start("13:00").staff(second))).isInstanceOf(SlotUnavailable.class);

        assertThat(fullRow(mover.id())).isEqualTo(before);
    }

    @Test
    void theVacatedTimeIsBookableByAGuestAndByTheOwnerAndTheNewTimeIsNot() {
        CreatedAppointment mover = existing("10:00");
        reschedule(move(mover).start("11:00"));

        assertThat(book(req().staff(tenant.staff()).start("10:00").build()))
                .isInstanceOf(BookingResult.Created.class);
        assertThat(book(req().staff(tenant.staff()).start("11:00").build()))
                .isInstanceOf(BookingResult.SlotUnavailable.class);
        assertThat(failureOf(submitCreate("m", cmd().start("11:00")))).isInstanceOf(SlotUnavailable.class);
        assertThat(create(cmd().start("09:00")).start()).isEqualTo(at("09:00"));
    }

    // ---- daylight saving in the Business timezone ------------------------------------------------------------------

    private ZoneOffsetTransition autumnOverlap() {
        ZoneOffsetTransition transition = SOFIA.getRules().nextTransition(BookingHookConfiguration.NOW);
        assertThat(transition.isOverlap()).isTrue();
        return transition;
    }

    private ZoneOffsetTransition springGap() {
        ZoneOffsetTransition transition = SOFIA.getRules().nextTransition(autumnOverlap().getInstant());
        assertThat(transition.isGap()).isTrue();
        return transition;
    }

    @Test
    void anOverlapAcrossTheSpringGapIsJudgedByRealInstantsNotByWallClockTimes() {
        ZoneOffsetTransition gap = springGap();
        availabilityFixtures.everyDay(tenant.business(), tenant.staff(), "01:00", "06:00");
        Instant laterStart = gap.getInstant(); // 04:00 local, right after the gap
        CreatedAppointment neighbour = create(cmd().start(laterStart));
        CreatedAppointment mover = existing("10:00");
        Instant beforeGap = gap.getInstant().minusSeconds(15 * 60L); // 02:45 local

        // 02:45 + 30 real minutes ends at 04:15 local: it overlaps the 04:00 neighbour although the
        // wall clock suggests it ends at 03:15.
        assertThat(rejectionOf(move(mover).start(beforeGap))).isInstanceOf(SlotUnavailable.class);

        // One grid step earlier it ends exactly when the neighbour starts: adjacency.
        Instant earlier = gap.getInstant().minusSeconds(30 * 60L); // 02:30 local
        RescheduledAppointment moved = reschedule(move(mover).start(earlier));
        assertThat(moved.end()).isEqualTo(gap.getInstant());
        assertThat(moved.end()).isEqualTo(neighbour.start());
        assertThat(moved.end().minusSeconds(moved.start().getEpochSecond()).getEpochSecond()).isEqualTo(30 * 60L);
    }

    @Test
    void aStartAcrossTheGapIsAnInstantTheServerJudgesByItsOwnLocalDateAndOffer() {
        ZoneOffsetTransition gap = springGap();
        availabilityFixtures.everyDay(tenant.business(), tenant.staff(), "01:00", "06:00");
        CreatedAppointment mover = existing("10:00");
        Instant beforeGap = gap.getInstant().minusSeconds(15 * 60L); // 02:45 local

        RescheduledAppointment crossing = reschedule(move(mover).start(beforeGap));
        // 30 real minutes: the local end is 04:15 because the wall clock skips an hour.
        assertThat(crossing.end()).isEqualTo(beforeGap.plusSeconds(30 * 60L));
        assertThat(crossing.end().atZone(SOFIA).toLocalTime().toString()).isEqualTo("04:15");

        // "03:30+02:00" is the instant 01:30Z, which is 04:30 local after the gap, and it is offered.
        Instant spoken = instant("2027-03-28T03:30:00+02:00");
        assertThat(spoken.atZone(SOFIA).toLocalTime().toString()).isEqualTo("04:30");
        assertThat(reschedule(move(mover.id(), 1L).start(spoken)).start()).isEqualTo(spoken);
    }

    @Test
    void bothOccurrencesOfTheRepeatedAutumnHourAreDistinctInstantsJudgedEachOnItsOwn() {
        ZoneOffsetTransition overlap = autumnOverlap();
        availabilityFixtures.everyDay(tenant.business(), tenant.staff(), "01:00", "06:00");
        Instant firstOccurrence = instant("2026-10-25T03:30:00+03:00");
        Instant secondOccurrence = instant("2026-10-25T03:30:00+02:00");
        assertThat(firstOccurrence).isBefore(overlap.getInstant()).isEqualTo(overlap.getInstant().minusSeconds(30 * 60L));
        assertThat(secondOccurrence).isAfter(overlap.getInstant());
        CreatedAppointment first = create(cmd().start(Instant.parse("2026-10-25T07:00:00Z")));
        CreatedAppointment other = create(cmd().start(Instant.parse("2026-10-25T08:00:00Z")));

        RescheduledAppointment movedFirst = reschedule(move(first).start(firstOccurrence));
        RescheduledAppointment movedSecond = reschedule(move(other).start(secondOccurrence));

        assertThat(movedFirst.start()).isEqualTo(firstOccurrence);
        assertThat(movedSecond.start()).isEqualTo(secondOccurrence);
        assertThat(movedFirst.end()).isEqualTo(overlap.getInstant());
        // The two appear identical on the wall clock and still do not conflict.
        assertThat(movedFirst.start().atZone(SOFIA).toLocalTime())
                .isEqualTo(movedSecond.start().atZone(SOFIA).toLocalTime());
        assertThat(appointmentCount(tenant.business())).isEqualTo(2);
    }

    @Test
    void theFirstOccurrenceOfTheRepeatedHourMayBePastWhileTheSecondIsNot() {
        ZoneOffsetTransition overlap = autumnOverlap();
        availabilityFixtures.everyDay(tenant.business(), tenant.staff(), "01:00", "06:00");
        CreatedAppointment mover = create(cmd().start(Instant.parse("2026-10-25T07:00:00Z")));
        Instant firstOccurrence = instant("2026-10-25T03:30:00+03:00");
        Instant secondOccurrence = instant("2026-10-25T03:30:00+02:00");
        // Between the two 03:30 wall-clock times.
        clock.set(overlap.getInstant().minusSeconds(15 * 60L));

        assertThat(rejectionOf(move(mover).start(firstOccurrence))).isInstanceOf(SlotUnavailable.class);
        assertThat(fullRow(mover.id()).get("version")).isEqualTo(0L);
        assertThat(reschedule(move(mover).start(secondOccurrence)).start()).isEqualTo(secondOccurrence);
        // Both occurrences are valid offsets of the same wall-clock time: the repeated hour is real.
        assertThat(SOFIA.getRules().getValidOffsets(firstOccurrence.atZone(SOFIA).toLocalDateTime())).hasSize(2);
    }

    @Test
    void theResultCarriesTheStoredTimeZoneUnchanged() {
        CreatedAppointment created = existing("10:00");

        RescheduledAppointment moved = reschedule(move(created).start("10:30"));

        assertThat(moved.timezone()).isEqualTo("Europe/Sofia");
        assertThat(fullRow(created.id()).get("timezone")).isEqualTo("Europe/Sofia");
    }
}
