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
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.UpdatedAppointment;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;

/**
 * The private internal-note edit through the real orchestration against real PostgreSQL (ADR-0028
 * section 9): create, replace and clear through the shared note policy, canonical whitespace and
 * Unicode, the length limit without truncation, every Appointment status, the unchanged-note no-op
 * that issues no {@code UPDATE}, the stale version, authorization, lifecycle, and isolation.
 */
class AppointmentInternalNoteIntegrationTests extends LifecycleAppointmentTest {
    private static final String NOTE = "СЕНТИНЕЛ-ВЪТРЕШНА-БЕЛЕЖКА-5531";
    private static final String OTHER = "СЕНТИНЕЛ-ДРУГА-БЕЛЕЖКА-6642";

    private Object storedNote(UUID id) {
        return fullRow(id).get("internal_note");
    }

    // ---- create, replace, clear -----------------------------------------------------------------------

    @Test
    void aNoteIsCreatedReplacedAndClearedAndEachChangeAdvancesTheVersionOnce() {
        CreatedAppointment created = create(cmd());
        clock.set(BookingHookConfiguration.NOW.plusSeconds(3600));
        Map<String, Object> before = fullRow(created.id());

        UpdatedAppointment first = editNote(created.id(), 0L, NOTE);
        assertThat(first.internalNote()).isEqualTo(NOTE);
        assertThat(first.version()).isEqualTo(1L);
        assertThat(changedColumns(before, fullRow(created.id()))).isEqualTo(NOTE_COLUMNS);

        UpdatedAppointment replaced = editNote(created.id(), 1L, OTHER);
        assertThat(replaced.internalNote()).isEqualTo(OTHER);
        assertThat(replaced.version()).isEqualTo(2L);

        UpdatedAppointment cleared = editNote(created.id(), 2L, "");
        assertThat(cleared.internalNote()).isNull();
        assertThat(cleared.version()).isEqualTo(3L);
        assertThat(storedNote(created.id())).isNull();
    }

    @Test
    void aBlankNoteClearsWhateverTheBlankIs() {
        CreatedAppointment created = create(cmd().note(NOTE));

        UpdatedAppointment cleared = editNote(created.id(), 0L, " \t\r\n   ");

        assertThat(cleared.internalNote()).isNull();
        assertThat(storedNote(created.id())).isNull();
    }

    @Test
    void theNoteIsNormalizedByTheSharedPolicyBeforeItIsStored() {
        CreatedAppointment created = create(cmd());

        UpdatedAppointment edited = editNote(created.id(), 0L, "   Първи ред\r\nВтори ред\rТрети ред  \n");

        assertThat(edited.internalNote()).isEqualTo("Първи ред\nВтори ред\nТрети ред");
        assertThat(storedNote(created.id())).isEqualTo("Първи ред\nВтори ред\nТрети ред");
    }

    @Test
    void unicodeIsStoredExactlyAndTabsAndLineFeedsAreKept() {
        CreatedAppointment created = create(cmd());
        String text = "Е́ клиент 😀 мюсюлманин\tколона\nнов ред — „кавички“";

        assertThat(editNote(created.id(), 0L, text).internalNote()).isEqualTo(text);
        assertThat(storedNote(created.id())).isEqualTo(text);
    }

    // ---- invalid and overlong ----------------------------------------------------------------------------

    @Test
    void exactlyFiveHundredCodePointsAreStoredWholeAndFiveHundredAndOneAreRefusedWithoutTruncation() {
        CreatedAppointment created = create(cmd());
        String limit = "😀".repeat(500);

        assertThat(editNote(created.id(), 0L, limit).internalNote()).isEqualTo(limit);
        Map<String, Object> before = fullRow(created.id());

        assertThat(noteFailure(created.id(), 1L, "😀".repeat(501))).isInstanceOf(InvalidInput.class);
        assertThat(noteFailure(created.id(), 1L, "а".repeat(501))).isInstanceOf(InvalidInput.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(storedNote(created.id())).isEqualTo(limit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0007b", "a\u0000b", "a\u001Fb", "a\u007Fb", "\uD800", "a\uDC00b", "a\u000Bb"})
    void controlCharactersAndUnpairedSurrogatesAreInvalidInputAndChangeNothing(String note) {
        CreatedAppointment created = create(cmd().note(NOTE));
        Map<String, Object> before = fullRow(created.id());

        assertThat(noteFailure(created.id(), 0L, note)).isInstanceOf(InvalidInput.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void aMissingNoteIsInvalidInputAfterAuthorizationAndLifecycle() {
        CreatedAppointment created = create(cmd());

        assertThat(failureOf(submitNote("main", ownerContext, new EditInternalNoteCommand(created.id(), 0L, null))))
                .isInstanceOf(InvalidInput.class);
        assertThat(failureOf(submitNote("main", ownerContext, new EditInternalNoteCommand(null, 0L, "x"))))
                .isInstanceOf(InvalidInput.class);
        assertThat(noteFailure(created.id(), -1L, "x")).isInstanceOf(InvalidInput.class);
        assertThat(failureOf(submitNote("main", ownerContext, null))).isInstanceOf(InvalidInput.class);
        assertThat(fullRow(created.id()).get("version")).isEqualTo(0L);
    }

    @Test
    void aStaleVersionIsReportedBeforeANoteRuleViolation() {
        CreatedAppointment created = create(cmd());

        assertThat(noteFailure(created.id(), 5L, "a\u0007b")).isInstanceOf(ConcurrentUpdate.class);
        assertThat(noteFailure(created.id(), 0L, "a\u0007b")).isInstanceOf(InvalidInput.class);
    }

    // ---- every status ----------------------------------------------------------------------------------------------

    @Test
    void aConfirmedAppointmentChangesOnlyItsNoteVersionAndUpdateTime() {
        CreatedAppointment created = create(cmd());
        clock.set(BookingHookConfiguration.NOW.plusSeconds(3600));
        Map<String, Object> before = fullRow(created.id());
        List<Map<String, Object>> linesBefore = linesOf(created.id());

        editNote(created.id(), 0L, NOTE);

        Map<String, Object> after = fullRow(created.id());
        assertThat(changedColumns(before, after)).isEqualTo(NOTE_COLUMNS);
        assertThat(after.get("status")).isEqualTo("CONFIRMED");
        assertThat(after.get("cancelled_at")).isNull();
        assertThat(after.get("cancelled_by")).isNull();
        assertThat(instantOf(after, "updated_at")).isEqualTo(BookingHookConfiguration.NOW.plusSeconds(3600));
        assertThat(linesOf(created.id())).isEqualTo(linesBefore);
    }

    @Test
    void aCancelledAppointmentKeepsItsCancellationFactsWhenItsNoteChanges() {
        CreatedAppointment created = create(cmd());
        clock.set(BookingHookConfiguration.NOW.plusSeconds(3600));
        cancel(created);
        clock.set(BookingHookConfiguration.NOW.plusSeconds(7200));
        Map<String, Object> before = fullRow(created.id());

        UpdatedAppointment edited = editNote(created.id(), 1L, NOTE);

        Map<String, Object> after = fullRow(created.id());
        assertThat(edited.status()).isEqualTo("CANCELLED");
        assertThat(edited.version()).isEqualTo(2L);
        assertThat(changedColumns(before, after)).isEqualTo(NOTE_COLUMNS);
        assertThat(after.get("cancelled_by")).isEqualTo("BUSINESS");
        assertThat(after.get("cancelled_at")).isEqualTo(before.get("cancelled_at"));
        assertThat(instantOf(after, "updated_at")).isEqualTo(BookingHookConfiguration.NOW.plusSeconds(7200));
    }

    @Test
    void aStartedAndAPastAppointmentStillAcceptsANote() {
        CreatedAppointment created = create(cmd());
        clock.set(created.start());
        assertThat(editNote(created.id(), 0L, NOTE).version()).isEqualTo(1L);
        clock.set(created.end().plusSeconds(86_400L * 30));
        assertThat(editNote(created.id(), 1L, OTHER).internalNote()).isEqualTo(OTHER);
    }

    // ---- no change ---------------------------------------------------------------------------------------------------

    @Test
    void anUnchangedNormalizedNoteIssuesNoUpdateAndAdvancesNothing() {
        CreatedAppointment created = create(cmd().note(NOTE));
        clock.set(BookingHookConfiguration.NOW.plusSeconds(3600));
        Map<String, Object> before = fullRow(created.id());

        UpdatedAppointment same = editNote(created.id(), 0L, "  " + NOTE + " \r\n");

        // The tuple version (xmin) is part of the row: an UPDATE of identical values would change it.
        assertThat(fullRow(created.id())).isEqualTo(before);
        assertThat(same.version()).isEqualTo(0L);
        assertThat(same.internalNote()).isEqualTo(NOTE);
        assertThat(same.updatedAt()).isEqualTo(created.createdAt());
        assertThat(same.status()).isEqualTo("CONFIRMED");
    }

    @Test
    void aBlankNoteOnAnAppointmentWithoutOneAndTheSameNoteOnACancelledOneAreNoOps() {
        CreatedAppointment created = create(cmd());
        Map<String, Object> before = fullRow(created.id());

        assertThat(editNote(created.id(), 0L, " \n ").version()).isEqualTo(0L);
        assertThat(fullRow(created.id())).isEqualTo(before);

        UpdatedAppointment edited = editNote(created.id(), 0L, NOTE);
        cancel(created.id(), edited.version());
        Map<String, Object> cancelled = fullRow(created.id());
        assertThat(editNote(created.id(), 2L, NOTE).version()).isEqualTo(2L);
        assertThat(fullRow(created.id())).isEqualTo(cancelled);
    }

    @Test
    void aNoOpIsJudgedAfterExistenceAndVersionSoAStaleFormStillLearnsItsFormIsOld() {
        CreatedAppointment created = create(cmd().note(NOTE));
        editNote(created.id(), 0L, OTHER);
        Map<String, Object> before = fullRow(created.id());

        assertThat(noteFailure(created.id(), 0L, OTHER)).isInstanceOf(ConcurrentUpdate.class);
        assertThat(noteFailure(UUID.randomUUID(), 0L, OTHER)).isInstanceOf(AppointmentNotFound.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void theNoOpStillReadsTheClockOnceAndOwnsOneTransaction() {
        CreatedAppointment created = create(cmd().note(NOTE));
        clock.resetReads();
        TransactionLog.clear();

        completed(submitNote("a", created.id(), 0L, NOTE));

        assertThat(clock.reads()).isEqualTo(1);
        assertThat(TransactionLog.begins(threadOf("a"))).hasSize(1);
    }

    // ---- stale and tenant isolation -----------------------------------------------------------------------------------

    @Test
    void aStaleVersionChangesNothing() {
        CreatedAppointment created = create(cmd());
        editNote(created.id(), 0L, NOTE);
        Map<String, Object> before = fullRow(created.id());

        assertThat(noteFailure(created.id(), 0L, OTHER)).isInstanceOf(ConcurrentUpdate.class);

        assertThat(fullRow(created.id())).isEqualTo(before);
    }

    @Test
    void aMissingAndAForeignAppointmentAreTheIndistinguishableNotFound() {
        Tenant other = fixtures.tenant();
        Map<String, Object> row = AppointmentFixtures.row(other, at("10:00"), 30);
        fixtures.insertRow(row);
        UUID foreign = (UUID) row.get("id");
        Map<String, Object> foreignBefore = fullRow(foreign);

        Throwable missing = noteFailure(UUID.randomUUID(), 0L, NOTE);
        Throwable foreignFailure = noteFailure(foreign, 0L, NOTE);

        assertThat(missing).isInstanceOf(AppointmentNotFound.class).hasNoCause();
        assertThat(foreignFailure.getClass()).isEqualTo(missing.getClass());
        assertThat(foreignFailure.getMessage()).isEqualTo(missing.getMessage());
        assertThat(fullRow(foreign)).isEqualTo(foreignBefore);
    }

    // ---- authorization and lifecycle -------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "STAFF"})
    void anActiveManagerOrStaffMemberIsDenied(String role) {
        CreatedAppointment created = create(cmd());

        Throwable failure = failureOf(submitNote(
                "main", memberOf(tenant.business(), role, true), new EditInternalNoteCommand(created.id(), 0L, NOTE)));

        assertThat(failure).isInstanceOf(AccessDenied.class);
        assertThat(storedNote(created.id())).isNull();
    }

    @Test
    void theOwnerOfAnotherBusinessLearnsNothingAndChangesNothing() {
        CreatedAppointment created = create(cmd());

        Throwable failure = failureOf(submitNote(
                "main", owner(fixtures.tenant().business()), new EditInternalNoteCommand(created.id(), 0L, NOTE)));

        assertThat(failure).isInstanceOf(AppointmentNotFound.class);
        assertThat(storedNote(created.id())).isNull();
    }

    @Test
    void aDraftAndASuspendedBusinessAreReadOnlyBeforeAnyDomainValidationAndAnActiveOneMayEdit() {
        CreatedAppointment created = create(cmd());
        Map<String, Object> before = fullRow(created.id());

        availabilityFixtures.setBusinessStatus(tenant.business(), "DRAFT");
        assertThat(noteFailure(created.id(), 0L, NOTE)).isInstanceOf(BusinessNotActive.class);
        assertThat(noteFailure(created.id(), -1L, "a\u0007")).isInstanceOf(BusinessNotActive.class);
        availabilityFixtures.setBusinessStatus(tenant.business(), "SUSPENDED");
        assertThat(noteFailure(created.id(), 0L, NOTE)).isInstanceOf(BusinessSuspended.class);
        assertThat(noteFailure(UUID.randomUUID(), 0L, "a\u0007")).isInstanceOf(BusinessSuspended.class);
        assertThat(fullRow(created.id())).isEqualTo(before);

        availabilityFixtures.setBusinessStatus(tenant.business(), "ACTIVE");
        assertThat(editNote(created.id(), 0L, NOTE).internalNote()).isEqualTo(NOTE);
    }

    @Test
    void anActiveCallerTransactionIsRejectedBeforeAnyClockRead() {
        CreatedAppointment created = create(cmd());
        clock.resetReads();

        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status ->
                        lifecycle.editInternalNote(ownerContext, new EditInternalNoteCommand(created.id(), 0L, NOTE))))
                .isInstanceOf(BookingOrchestrationFailure.class);

        assertThat(clock.reads()).isZero();
        assertThat(storedNote(created.id())).isNull();
    }

    @Test
    void theEditOwnsExactlyOneReadCommittedTransactionAndIsNeverRetried() {
        CreatedAppointment created = create(cmd());
        List<String> isolation = new java.util.ArrayList<>();
        manualHooks.on(ManualBookingHooks.Point.AFTER_CLOCK_READ, invocation ->
                isolation.add(jdbc.sql("SHOW transaction_isolation").query(String.class).single()));

        completed(submitNote("a", created.id(), 0L, NOTE));

        List<TransactionLog.Begin> begins = TransactionLog.begins(threadOf("a"));
        assertThat(begins).hasSize(1);
        assertThat(begins.get(0).isolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(begins.get(0).propagation()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(isolation).containsExactly("read committed");
    }

    // ---- privacy ------------------------------------------------------------------------------------------------------------

    @Test
    void theNoteNeverReachesAGuestReplayOrTheOtherPublicFacts() {
        Req request = req().staff(tenant.staff()).start("10:00").note("ГОСТ-СЕНТИНЕЛ-0017");
        BookingResult.Created booked = (BookingResult.Created) book(request.build());
        UUID id = appointmentIdByReference(booked.appointment().reference()).orElseThrow();

        editNote(id, 0L, NOTE);
        BookingResult replay = book(request.copy().build());

        assertThat(replay).isInstanceOf(BookingResult.Replayed.class);
        assertThat(replay.toString()).doesNotContain(NOTE);
        assertThat(((BookingResult.Replayed) replay).appointment()).isEqualTo(booked.appointment());
    }
}
