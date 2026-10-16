package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.AppointmentAdministrationException.AccessDenied;
import bg.spotyourslot.booking.AppointmentAdministrationException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentAdministrationException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentAdministrationException.ConcurrentUpdate;
import bg.spotyourslot.booking.AppointmentAdministrationException.InvalidInput;
import bg.spotyourslot.booking.AppointmentAdministrationException.StateConflict;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Business cancellation through the real orchestration against real PostgreSQL (ADR-0028 section
 * 8, ADR-0029 as amended by the Phase 6 note): the exact stored facts, immutability of everything
 * else, the started-Appointment boundary at the single clock instant, the distinct stale-version and
 * state results, authorization and lifecycle precedence, tenant isolation, the release of the time,
 * and guest replay. Appointments are created through the real owner creation.
 */
class AppointmentCancellationIntegrationTests extends LifecycleAppointmentTest {
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-4410";
    private static final String GUEST_NOTE = "СЕНТИНЕЛ-ГОСТ-БЕЛЕЖКА-2208";

    // ---- the stored facts ---------------------------------------------------------------------------

    @Test
    void aCancellationWritesExactlyTheApprovedFactsAndNothingElse() {
        CreatedAppointment created = create(cmd().note(NOTE));
        Instant now = Instant.parse("2026-09-30T08:00:00.123456789Z");
        clock.set(now);
        Map<String, Object> before = fullRow(created.id());
        List<Map<String, Object>> linesBefore = linesOf(created.id());
        String customersBefore = customerSnapshot();

        UpdatedAppointment cancelled = cancel(created);

        Instant stored = Instant.parse("2026-09-30T08:00:00.123456Z");
        Map<String, Object> after = fullRow(created.id());
        assertThat(changedColumns(before, after)).isEqualTo(CANCEL_COLUMNS);
        assertThat(after.get("status")).isEqualTo("CANCELLED");
        assertThat(after.get("cancelled_by")).isEqualTo("BUSINESS");
        assertThat(instantOf(after, "cancelled_at")).isEqualTo(stored);
        assertThat(instantOf(after, "updated_at")).isEqualTo(stored);
        assertThat(after.get("version")).isEqualTo(1L);
        assertThat(after.get("internal_note")).isEqualTo(NOTE);
        assertThat(linesOf(created.id())).isEqualTo(linesBefore);
        assertThat(customerSnapshot()).isEqualTo(customersBefore);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.version()).isEqualTo(1L);
        assertThat(cancelled.updatedAt()).isEqualTo(stored);
        assertThat(cancelled.start()).isEqualTo(created.start());
        assertThat(cancelled.staffMemberId()).isEqualTo(created.staffMemberId());
        assertThat(cancelled.customerId()).isEqualTo(created.customerId());
        assertThat(cancelled.services()).hasSameSizeAs(created.services());
        assertThat(cancelled.price()).isEqualByComparingTo(created.price());
        assertThat(cancelled.internalNote()).isEqualTo(NOTE);
        assertThat(cancelled.toString()).doesNotContain(NOTE);
        assertThat(AppointmentAdministrationRecords.UpdatedAppointment.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(name -> name.toLowerCase().contains("cancelledby"));
    }

    @Test
    void aMultiServiceVisitKeepsEveryLineAndSnapshotUnchanged() {
        UUID extra = addService(15, "Миене");
        CreatedAppointment created = create(cmd().services(tenant.service(), extra));
        clock.set(BookingHookConfiguration.NOW.plusSeconds(3600));
        Map<String, Object> before = fullRow(created.id());
        List<Map<String, Object>> linesBefore = linesOf(created.id());

        UpdatedAppointment cancelled = cancel(created);

        assertThat(linesOf(created.id())).isEqualTo(linesBefore).hasSize(2);
        assertThat(changedColumns(before, fullRow(created.id()))).isEqualTo(CANCEL_COLUMNS);
        assertThat(cancelled.services()).hasSize(2);
        assertThat(cancelled.durationMinutes()).isEqualTo(created.durationMinutes());
        assertThat(cancelled.price()).isEqualByComparingTo(created.price());
    }

    @Test
    void theAttemptReadsTheClockExactlyOnce() {
        CreatedAppointment created = create(cmd());
        clock.resetReads();

        cancel(created);

        assertThat(clock.reads()).isEqualTo(1);
    }

    // ---- the started boundary --------------------------------------------------------------------------

    @Test
    void anAppointmentIsCancellableUntilExactlyItsStartAndRefusedAtAndAfterIt() {
        CreatedAppointment future = create(cmd().start("10:00"));
        Instant start = future.start();
        Map<String, Object> before = fullRow(future.id());

        clock.set(start);
        assertThat(cancelFailure(future.id(), 0L)).isInstanceOf(StateConflict.class);
        assertThat(fullRow(future.id())).isEqualTo(before);
        clock.set(start.plusSeconds(1));
        assertThat(cancelFailure(future.id(), 0L)).isInstanceOf(StateConflict.class);
        assertThat(fullRow(future.id())).isEqualTo(before);

        clock.set(start.minusNanos(1_000));
        assertThat(cancel(future.id(), 0L).status()).isEqualTo("CANCELLED");
    }

    @Test
    void aFutureAppointmentFarAheadIsCancellable() {
        CreatedAppointment created = create(cmd().start("10:00"));
        clock.set(created.start().minusSeconds(60L * 60 * 24));

        assertThat(cancel(created).version()).isEqualTo(1L);
    }

    // ---- state and version ----------------------------------------------------------------------------------

    @Test
    void anAlreadyCancelledAppointmentIsAStateConflictNotASilentSuccess() {
        CreatedAppointment created = create(cmd());
        UpdatedAppointment first = cancel(created);
        Map<String, Object> afterFirst = fullRow(created.id());

        assertThat(cancelFailure(created.id(), first.version())).isInstanceOf(StateConflict.class);

        assertThat(fullRow(created.id())).isEqualTo(afterFirst);
        assertThat(afterFirst.get("version")).isEqualTo(1L);
    }

    @Test
    void aRepeatedCancellationWithTheOldVersionIsTheStaleVersionResult() {
        CreatedAppointment created = create(cmd());
        cancel(created);

        assertThat(cancelFailure(created.id(), 0L)).isInstanceOf(ConcurrentUpdate.class);
    }

    @Test
    void aStaleVersionIsReportedBeforeAStateConflictAndChangesNothing() {
        CreatedAppointment created = create(cmd());
        completed(submitNote("n", created.id(), 0L, NOTE));
        Map<String, Object> before = fullRow(created.id());

        assertThat(cancelFailure(created.id(), 0L)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(cancelFailure(created.id(), 7L)).isInstanceOf(ConcurrentUpdate.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(cancel(created.id(), 1L).version()).isEqualTo(2L);
    }

    @Test
    void aStartedAndStaleRequestIsTheStaleVersionResult() {
        CreatedAppointment created = create(cmd());
        completed(submitNote("n", created.id(), 0L, NOTE));
        clock.set(created.start().plusSeconds(60));

        assertThat(cancelFailure(created.id(), 0L)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(cancelFailure(created.id(), 1L)).isInstanceOf(StateConflict.class);
    }

    @Test
    void aNegativeVersionIsInvalidInputAfterAuthorizationAndLifecycle() {
        CreatedAppointment created = create(cmd());

        assertThat(cancelFailure(created.id(), -1L)).isInstanceOf(InvalidInput.class);
        assertThat(failureOf(submitCancel("main", ownerContext, new CancelAppointmentCommand(null, 0L))))
                .isInstanceOf(InvalidInput.class);
        assertThat(failureOf(submitCancel("main", ownerContext, null))).isInstanceOf(InvalidInput.class);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(0L);
    }

    // ---- tenant isolation ----------------------------------------------------------------------------------------

    @Test
    void aMissingAndAForeignAppointmentAreTheIndistinguishableNotFound() {
        Tenant other = fixtures.tenant();
        Map<String, Object> row = AppointmentFixtures.row(other, at("10:00"), 30);
        fixtures.insertRow(row);
        UUID foreign = (UUID) row.get("id");
        Map<String, Object> foreignBefore = fullRow(foreign);

        Throwable missing = cancelFailure(UUID.randomUUID(), 0L);
        Throwable foreignFailure = cancelFailure(foreign, 0L);

        assertThat(missing).isInstanceOf(AppointmentNotFound.class).hasNoCause();
        assertThat(foreignFailure).isInstanceOf(AppointmentNotFound.class).hasNoCause();
        assertThat(foreignFailure.getMessage()).isEqualTo(missing.getMessage());
        assertThat(foreignFailure.getClass()).isEqualTo(missing.getClass());
        assertThat(fullRow(foreign)).isEqualTo(foreignBefore);
    }

    @Test
    void theOwnerOfAnotherBusinessCannotCancelAndLearnsNothing() {
        CreatedAppointment created = create(cmd());
        AuthenticatedBusinessContext foreignOwner = owner(fixtures.tenant().business());
        Map<String, Object> before = fullRow(created.id());

        Throwable failure = failureOf(submitCancel(
                "main", foreignOwner, new CancelAppointmentCommand(created.id(), 0L)));

        assertThat(failure).isInstanceOf(AppointmentNotFound.class);
        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    // ---- authorization and lifecycle ------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void anActiveManagerOrStaffMemberIsDenied(String role) {
        CreatedAppointment created = create(cmd());
        Map<String, Object> before = fullRow(created.id());

        Throwable failure = failureOf(submitCancel(
                "main", memberOf(tenant.business(), role, true), new CancelAppointmentCommand(created.id(), 0L)));

        assertThat(failure).isInstanceOf(AccessDenied.class);
        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void anInactiveOwnerMembershipIsDeniedAndAnUnusableContextIsRejectedWithoutATransaction() {
        CreatedAppointment created = create(cmd());
        clock.resetReads();

        assertThat(failureOf(submitCancel(
                "main", memberOf(tenant.business(), "BUSINESS_OWNER", false),
                new CancelAppointmentCommand(created.id(), 0L)))).isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> lifecycle.cancel(null, new CancelAppointmentCommand(created.id(), 0L)))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> lifecycle.cancel(
                new NoSelection(UUID.randomUUID()), new CancelAppointmentCommand(created.id(), 0L)))
                .isInstanceOf(SelectedBusinessRequired.class);

        assertThat(fullRow(created.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void aDraftBusinessIsNotActiveAndASuspendedOneIsSuspendedBeforeAnyDomainValidation() {
        CreatedAppointment created = create(cmd());
        Map<String, Object> before = fullRow(created.id());

        availabilityFixtures.setBusinessStatus(tenant.business(), "DRAFT");
        assertThat(cancelFailure(created.id(), 0L)).isInstanceOf(BusinessNotActive.class);
        assertThat(cancelFailure(created.id(), -1L)).isInstanceOf(BusinessNotActive.class);
        assertThat(cancelFailure(UUID.randomUUID(), 0L)).isInstanceOf(BusinessNotActive.class);
        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");
        assertThat(cancelFailure(created.id(), 0L)).isInstanceOf(BusinessSuspended.class);
        assertThat(cancelFailure(created.id(), -1L)).isInstanceOf(BusinessSuspended.class);
        assertThat(fullRow(created.id())).isEqualTo(before);

        availabilityFixtures.setBusinessStatus(tenant.business(), "ACTIVE");
        assertThat(cancel(created).status()).isEqualTo("CANCELLED");
    }

    // ---- transaction and entry guard ---------------------------------------------------------------------------------

    @Test
    void anActiveCallerTransactionIsRejectedBeforeAnyClockReadAndNothingIsWritten() {
        CreatedAppointment created = create(cmd());
        Map<String, Object> before = fullRow(created.id());
        clock.resetReads();
        TransactionLog.clear();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                lifecycle.cancel(ownerContext, new CancelAppointmentCommand(created.id(), 0L))))
                .isInstanceOf(BookingOrchestrationFailure.class)
                .hasNoCause();

        assertThat(clock.reads()).isZero();
        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void theCallOwnsExactlyOneReadCommittedReadWriteRequiredTransactionAndNeverRetries() {
        CreatedAppointment created = create(cmd());
        List<String> isolation = new ArrayList<>();
        manualHooks.on(ManualBookingHooks.Point.AFTER_CLOCK_READ, invocation ->
                isolation.add(jdbc.sql("SHOW transaction_isolation").query(String.class).single()));

        completed(submitCancel("a", created));

        List<TransactionLog.Begin> begins = TransactionLog.begins(threadOf("a"));
        assertThat(begins).hasSize(1);
        assertThat(begins.get(0).isolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(begins.get(0).propagation()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(begins.get(0).readOnly()).isFalse();
        assertThat(isolation).containsExactly("read committed");
        assertThat(TransactionLog.endsOf(threadOf("a"))).containsExactly(threadOf("a") + ":commit");
    }

    // ---- the freed time ---------------------------------------------------------------------------------------------------

    @Test
    void aCancelledAppointmentNoLongerBlocksAndTheTimeIsOfferedAndBookableAgain() {
        CreatedAppointment created = create(cmd().start("10:00"));
        assertThat(book(req().staff(tenant.staff()).start("10:00").build())).isInstanceOf(BookingResult.SlotUnavailable.class);

        cancel(created);

        assertThat(book(req().staff(tenant.staff()).start("10:00").build())).isInstanceOf(BookingResult.Created.class);
        assertThat(jdbc.sql("SELECT count(*) FROM appointment WHERE business_id = :b AND status = 'CONFIRMED'")
                .param("b", tenant.business()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void theFreedIntervalAcceptsAnOwnerCreatedAppointmentAndTheCancelledRowStaysForHistory() {
        CreatedAppointment created = create(cmd().start("10:00"));
        cancel(created);

        CreatedAppointment replacement = create(cmd().start("10:00"));

        assertThat(replacement.id()).isNotEqualTo(created.id());
        assertThat(appointmentById(created.id()).get("status")).isEqualTo("CANCELLED");
        assertThat(appointmentCount(tenant.business())).isEqualTo(2L);
    }

    // ---- guest replay -------------------------------------------------------------------------------------------------------

    @Test
    void aGuestReplayAfterCancellationReturnsCancelledAndHidesTheNoteAndTheActor() {
        Req request = req().start("10:00").note(GUEST_NOTE);
        BookingResult.Created booked = (BookingResult.Created) book(request.build());
        UUID id = appointmentIdByReference(booked.appointment().reference()).orElseThrow();
        completed(submitNote("n", id, 0L, NOTE));
        cancel(id, 1L);

        BookingResult replay = book(request.copy().build());

        assertThat(replay).isInstanceOf(BookingResult.Replayed.class);
        BookedAppointment replayed = ((BookingResult.Replayed) replay).appointment();
        assertThat(replayed.status()).isEqualTo(BookedAppointment.Status.CANCELLED);
        assertThat(replayed.reference()).isEqualTo(booked.appointment().reference());
        assertThat(replayed.toString()).doesNotContain(NOTE).doesNotContain(GUEST_NOTE)
                .doesNotContain("BUSINESS");
        assertThat(java.util.Arrays.stream(BookedAppointment.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .noneMatch(name -> name.toLowerCase().contains("note") || name.toLowerCase().contains("cancel"));
        assertThat(appointmentById(id).get("internal_note")).isEqualTo(NOTE);
        assertThat(appointmentCount(tenant.business())).isEqualTo(1L);
    }

    private record NoSelection(UUID userId) implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.empty();
        }
    }
}
