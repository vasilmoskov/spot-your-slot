package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import bg.spotyourslot.booking.AppointmentFixtures;
import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentCancelledBy;
import bg.spotyourslot.booking.domain.AppointmentStatus;
import bg.spotyourslot.booking.domain.AppointmentTransitions;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Applied;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.NoteUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.RescheduleUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.StaffTarget;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Transition;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.InvalidData;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.OverlapConflict;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The conditional Appointment updates of the store against real PostgreSQL (ADR-0028, ADR-0029): what
 * each statement writes and leaves alone, the optimistic predicates, the exclusion constraint on
 * {@code UPDATE} (a time-changing move, a StaffMember-changing move, and a shift inside the row's
 * own interval), the rollback guarantees, and the sanitized failures.
 */
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AppointmentAdministrationStoreIntegrationTests extends PostgresIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-10T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");
    private static final String SENTINEL = "СЕНТИНЕЛ-вътрешна-бележка-4417";

    @Autowired
    AppointmentStore store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AppointmentFixtures fixtures;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        fixtures = new AppointmentFixtures(jdbc);
        tenant = fixtures.tenant();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private Optional<Appointment> inTx(Supplier<Optional<Appointment>> operation) {
        return transaction().execute(status -> operation.get());
    }

    private Appointment committed(Instant start, int minutes) {
        return committed(tenant, start, minutes);
    }

    private Appointment committed(Tenant forTenant, Instant start, int minutes) {
        return transaction().execute(status ->
                store.insert(AppointmentFixtures.manual(forTenant, start, minutes)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T applied(Transition<T> transition) {
        assertThat(transition).isInstanceOf(Applied.class);
        return ((Applied<T>) transition).update();
    }

    private RescheduleUpdate move(Appointment current, Instant start, StaffTarget target) {
        return applied(AppointmentTransitions.reschedule(
                current, current.version(), start, target, NOW));
    }

    private Optional<Appointment> reschedule(RescheduleUpdate update) {
        return transaction().execute(status -> store.reschedule(update));
    }

    private String snapshotOf(UUID id) {
        return jdbc.sql("SELECT to_jsonb(a)::text || ' xmin=' || a.xmin::text FROM appointment a WHERE a.id = :id")
                .param("id", id).query(String.class).single();
    }

    // ---- reschedule and reassign ---------------------------------------------

    @Test
    void aTimeOnlyMoveWritesOnlyTheScheduleVersionAndUpdateTime() {
        Appointment before = committed(START, 45);
        Instant target = START.plusSeconds(3600);

        Appointment after = reschedule(move(before, target, null)).orElseThrow();

        assertThat(after.startAt()).isEqualTo(target);
        assertThat(after.endAt()).isEqualTo(target.plus(Duration.ofMinutes(45)));
        assertThat(after.occupiedUntil()).isEqualTo(after.endAt());
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.updatedAt()).isEqualTo(NOW);
        // Everything else is the agreed visit, untouched.
        assertThat(after.staffMemberId()).isEqualTo(before.staffMemberId());
        assertThat(after.staffDisplayName()).isEqualTo(before.staffDisplayName());
        assertThat(after.customerId()).isEqualTo(before.customerId());
        assertThat(after.serviceLines()).isEqualTo(before.serviceLines());
        assertThat(after.durationMinutes()).isEqualTo(before.durationMinutes());
        assertThat(after.priceEur()).isEqualByComparingTo(before.priceEur());
        assertThat(after.source()).isEqualTo(before.source());
        assertThat(after.publicReference()).isEqualTo(before.publicReference());
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(after.cancelledAt()).isNull();
        assertThat(after.cancelledBy()).isNull();
        assertThat(store.find(tenant.business(), before.id())).contains(after);
    }

    @Test
    void aReassignmentWritesTheNewStaffMemberAndRefreshesTheSnapshotFromTheTarget() {
        UUID other = fixtures.staffMember(tenant.business(), tenant.service());
        Appointment before = committed(START, 30);

        Appointment after = reschedule(move(before, START, new StaffTarget(other, "Иван Тестов")))
                .orElseThrow();

        assertThat(after.staffMemberId()).isEqualTo(other);
        assertThat(after.staffDisplayName()).isEqualTo("Иван Тестов");
        assertThat(after.startAt()).isEqualTo(START);
        assertThat(after.version()).isEqualTo(1);
    }

    @Test
    void aTimeChangingUpdateIntoALiveNeighbourIsRefusedAndLeavesBothRowsUnchanged() {
        Appointment neighbour = committed(START, 60);
        Appointment moving = committed(START.plusSeconds(2 * 3600L), 60);
        String neighbourBefore = snapshotOf(neighbour.id());
        String movingBefore = snapshotOf(moving.id());
        RescheduleUpdate update = move(moving, START.plusSeconds(1800), null);

        Throwable failure = catchThrowable(() -> reschedule(update));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(snapshotOf(neighbour.id())).isEqualTo(neighbourBefore);
        assertThat(snapshotOf(moving.id())).isEqualTo(movingBefore);
    }

    @Test
    void aStaffMemberChangingUpdateIntoALiveNeighbourOfTheTargetIsRefusedAndLeavesTheRowUnchanged() {
        UUID other = fixtures.staffMember(tenant.business(), tenant.service());
        Tenant otherTenant = new Tenant(tenant.business(), tenant.service(), other, tenant.customer());
        Appointment neighbourOfTarget = committed(otherTenant, START.plusSeconds(1800), 60);
        Appointment moving = committed(START, 60);
        String neighbourBefore = snapshotOf(neighbourOfTarget.id());
        String movingBefore = snapshotOf(moving.id());
        RescheduleUpdate update = move(moving, START, new StaffTarget(other, "Иван Тестов"));

        Throwable failure = catchThrowable(() -> reschedule(update));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(snapshotOf(neighbourOfTarget.id())).isEqualTo(neighbourBefore);
        assertThat(snapshotOf(moving.id())).isEqualTo(movingBefore);
    }

    @Test
    void aShiftInsideTheRowsOwnIntervalSucceedsBecauseItNeverConflictsWithItself() {
        Appointment own = committed(START, 60);

        Appointment shifted = reschedule(move(own, START.plusSeconds(15 * 60L), null)).orElseThrow();
        Appointment shiftedBack = reschedule(move(shifted, START.minusSeconds(15 * 60L), null))
                .orElseThrow();

        assertThat(shifted.startAt()).isEqualTo(START.plusSeconds(15 * 60L));
        assertThat(shiftedBack.startAt()).isEqualTo(START.minusSeconds(15 * 60L));
        assertThat(shiftedBack.version()).isEqualTo(2);
        assertThat(blockingRows()).isEqualTo(1L);
    }

    @Test
    void aMoveExactlyAdjacentToANeighbourIsAllowedButOneMinuteInsideIsNot() {
        Appointment neighbour = committed(START, 60);
        Appointment moving = committed(START.plusSeconds(4 * 3600L), 30);

        Appointment adjacent = reschedule(move(moving, neighbour.endAt(), null)).orElseThrow();
        assertThat(adjacent.startAt()).isEqualTo(neighbour.endAt());

        Throwable inside = catchThrowable(() -> reschedule(
                move(adjacent, neighbour.endAt().minusSeconds(60), null)));
        assertThat(inside).isInstanceOf(OverlapConflict.class);
    }

    @Test
    void aFailedStatementAbortsTheTransactionSoNothingMayContinueInIt() {
        Appointment neighbour = committed(START, 60);
        Appointment moving = committed(START.plusSeconds(2 * 3600L), 60);
        RescheduleUpdate update = move(moving, START.plusSeconds(1800), null);

        Throwable failure = catchThrowable(() -> transaction().execute(status -> {
            try {
                store.reschedule(update);
            } catch (OverlapConflict expected) {
                // The transaction is now aborted: the next statement must fail with 25P02.
            }
            return jdbc.sql("SELECT 1").query(Integer.class).single();
        }));

        assertThat(sqlStateOf(failure)).isEqualTo("25P02");
        assertThat(store.find(tenant.business(), neighbour.id())).contains(neighbour);
    }

    @Test
    void aFailedMoveRollsBackAnEarlierSuccessfulUpdateOfTheSameTransaction() {
        Appointment blocker = committed(START, 60);
        Appointment first = committed(START.plusSeconds(3 * 3600L), 30);
        Appointment second = committed(START.plusSeconds(5 * 3600L), 30);
        String firstBefore = snapshotOf(first.id());
        String secondBefore = snapshotOf(second.id());
        RescheduleUpdate fine = move(first, START.plusSeconds(7 * 3600L), null);
        RescheduleUpdate refused = move(second, START.plusSeconds(1800), null);

        Throwable failure = catchThrowable(() -> transaction().execute(status -> {
            store.reschedule(fine);
            return store.reschedule(refused);
        }));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(snapshotOf(first.id())).isEqualTo(firstBefore);
        assertThat(snapshotOf(second.id())).isEqualTo(secondBefore);
        assertThat(store.find(tenant.business(), blocker.id())).contains(blocker);
    }

    @Test
    void everyOptimisticPredicateIsEnforcedAndANonMatchingUpdateChangesNothingAndKeepsTheTransactionUsable() {
        Appointment current = committed(START, 30);
        String before = snapshotOf(current.id());
        Instant target = START.plusSeconds(900);
        UUID staff = current.staffMemberId();
        List<RescheduleUpdate> stale = List.of(
                update(current, current.version() + 1, staff, current.startAt(), target),
                update(current, current.version(), UUID.randomUUID(), current.startAt(), target),
                update(current, current.version(), staff, current.startAt().plusSeconds(60), target));

        for (RescheduleUpdate update : stale) {
            Optional<Appointment> result = transaction().execute(status -> {
                Optional<Appointment> outcome = store.reschedule(update);
                // Zero rows do not abort the transaction: a classification read still works.
                assertThat(store.find(tenant.business(), current.id())).contains(current);
                return outcome;
            });
            assertThat(result).isEmpty();
        }
        // Another Business's identifier behaves exactly like a missing Appointment.
        RescheduleUpdate foreign = new RescheduleUpdate(
                fixtures.tenant().business(), current.id(), current.version(), staff,
                current.startAt(), target, target.plus(Duration.ofMinutes(30)),
                target.plus(Duration.ofMinutes(30)), staff, current.staffDisplayName(), NOW);
        assertThat(reschedule(foreign)).isEmpty();
        assertThat(snapshotOf(current.id())).isEqualTo(before);
    }

    private RescheduleUpdate update(
            Appointment current, long version, UUID staff, Instant readStart, Instant target) {
        Instant end = target.plus(Duration.ofMinutes(current.durationMinutes()));
        return new RescheduleUpdate(
                current.businessId(), current.id(), version, staff, readStart, target, end, end,
                current.staffMemberId(), current.staffDisplayName(), NOW);
    }

    @Test
    void aCancelledAppointmentIsNeverMovedByTheStore() {
        Appointment current = committed(START, 30);
        RescheduleUpdate staleView = move(current, START.plusSeconds(900), null);
        transaction().executeWithoutResult(status -> store.cancel(cancel(current)));
        String cancelledSnapshot = snapshotOf(current.id());

        // The caller's earlier read said CONFIRMED at version 0; the row has moved on.
        assertThat(reschedule(staleView)).isEmpty();
        assertThat(snapshotOf(current.id())).isEqualTo(cancelledSnapshot);
    }

    // ---- cancellation --------------------------------------------------------

    private CancelUpdate cancel(Appointment current) {
        return applied(AppointmentTransitions.cancel(current, current.version(), NOW));
    }

    @Test
    void cancellationWritesTheBusinessAttributionAndReleasesTheTime() {
        Appointment before = committed(START, 60);

        Appointment after = inTx(() -> store.cancel(cancel(before))).orElseThrow();

        assertThat(after.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(after.cancelledBy()).isEqualTo(AppointmentCancelledBy.BUSINESS);
        assertThat(after.cancelledAt()).isEqualTo(NOW);
        assertThat(after.updatedAt()).isEqualTo(NOW);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.startAt()).isEqualTo(before.startAt());
        assertThat(after.serviceLines()).isEqualTo(before.serviceLines());
        // The time is released: the same StaffMember can take it again.
        committed(START, 60);
        assertThat(blockingRows()).isEqualTo(1L);
    }

    @Test
    void cancellationOnlyMatchesAConfirmedFutureAppointmentAtTheExpectedVersion() {
        Appointment current = committed(START, 30);
        Instant atStart = START;
        Instant afterStart = START.plusSeconds(1);
        String before = snapshotOf(current.id());

        assertThat(inTx(() -> store.cancel(
                new CancelUpdate(current.businessId(), current.id(), current.version(), atStart))))
                .as("an Appointment starting exactly now has started").isEmpty();
        assertThat(inTx(() -> store.cancel(
                new CancelUpdate(current.businessId(), current.id(), current.version(), afterStart))))
                .isEmpty();
        assertThat(inTx(() -> store.cancel(
                new CancelUpdate(current.businessId(), current.id(), current.version() + 1, NOW))))
                .as("a stale version").isEmpty();
        assertThat(snapshotOf(current.id())).isEqualTo(before);

        assertThat(inTx(() -> store.cancel(cancel(current)))).isPresent();
        assertThat(inTx(() -> store.cancel(
                new CancelUpdate(current.businessId(), current.id(), current.version() + 1, NOW))))
                .as("an already cancelled Appointment").isEmpty();
    }

    // ---- internal note -------------------------------------------------------

    private Optional<Appointment> note(Appointment current, String text) {
        NoteUpdate update = applied(AppointmentTransitions.editInternalNote(
                current, current.version(), text, NOW));
        return inTx(() -> store.updateInternalNote(update));
    }

    @Test
    void theNoteIsWrittenAndClearedOnConfirmedCancelledAndStartedAppointmentsAlike() {
        Appointment confirmed = committed(START, 30);
        Appointment withNote = note(confirmed, SENTINEL).orElseThrow();
        assertThat(withNote.internalNote()).isEqualTo(SENTINEL);
        assertThat(withNote.version()).isEqualTo(1);
        assertThat(withNote.updatedAt()).isEqualTo(NOW);
        assertThat(withNote.startAt()).isEqualTo(confirmed.startAt());
        assertThat(withNote.status()).isEqualTo(AppointmentStatus.CONFIRMED);

        Appointment cleared = note(withNote, "   ").orElseThrow();
        assertThat(cleared.internalNote()).isNull();
        assertThat(cleared.version()).isEqualTo(2);

        Appointment cancelled = inTx(() -> store.cancel(cancel(cleared))).orElseThrow();
        Appointment cancelledNote = note(cancelled, "след отказа").orElseThrow();
        assertThat(cancelledNote.internalNote()).isEqualTo("след отказа");
        assertThat(cancelledNote.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelledNote.cancelledBy()).isEqualTo(AppointmentCancelledBy.BUSINESS);
        assertThat(cancelledNote.cancelledAt()).isEqualTo(NOW);

        Instant startedNow = START.plusSeconds(3600);
        Appointment started = committed(startedNow, 30);
        Instant afterStart = startedNow.plusSeconds(600);
        Appointment startedNoted = inTx(() -> store.updateInternalNote(
                applied(AppointmentTransitions.editInternalNote(
                        started, started.version(), "по време на часа", afterStart)))).orElseThrow();
        assertThat(startedNoted.internalNote()).isEqualTo("по време на часа");
        assertThat(startedNoted.startAt()).isEqualTo(startedNow);
    }

    @Test
    void aStaleVersionOrAnotherBusinessNeverWritesTheNote() {
        Appointment current = committed(START, 30);
        String before = snapshotOf(current.id());

        assertThat(inTx(() -> store.updateInternalNote(new NoteUpdate(
                current.businessId(), current.id(), current.version() + 1, "x", NOW)))).isEmpty();
        assertThat(inTx(() -> store.updateInternalNote(new NoteUpdate(
                fixtures.tenant().business(), current.id(), current.version(), "x", NOW)))).isEmpty();
        assertThat(snapshotOf(current.id())).isEqualTo(before);
    }

    // ---- failures ------------------------------------------------------------

    @Test
    void theDatabaseRulesOfTheNewColumnsTranslateToInvalidDataWithoutLeakingTheValue() {
        Appointment current = committed(START, 30);

        Throwable emptyNote = catchThrowable(() -> jdbc.sql(
                "UPDATE appointment SET internal_note = '' WHERE id = :id")
                .param("id", current.id()).update());
        Throwable missingActor = catchThrowable(() -> jdbc.sql(
                "UPDATE appointment SET status = 'CANCELLED' WHERE id = :id")
                .param("id", current.id()).update());
        Throwable tooLong = catchThrowable(() -> jdbc.sql(
                "UPDATE appointment SET internal_note = :note WHERE id = :id")
                .param("note", SENTINEL.repeat(40)).param("id", current.id()).update());

        for (Throwable driverFailure : List.of(emptyNote, missingActor, tooLong)) {
            AppointmentPersistenceException translated = AppointmentStore.translate(driverFailure);
            assertThat(translated).isInstanceOf(InvalidData.class);
            assertThat(translated.getMessage()).doesNotContain(SENTINEL).isEqualTo("Appointment data was rejected");
            assertThat(translated.getCause()).isNull();
            assertThat(translated.getSuppressed()).isEmpty();
        }
    }

    @Test
    void anOverlapFromARealUpdateIsSanitized() {
        committed(START, 60);
        Appointment moving = committed(START.plusSeconds(2 * 3600L), 60);
        Appointment noted = note(moving, SENTINEL).orElseThrow();
        RescheduleUpdate update = move(noted, START.plusSeconds(1800), null);

        Throwable failure = catchThrowable(() -> reschedule(update));

        assertThat(failure).isInstanceOf(OverlapConflict.class);
        assertThat(failure.getMessage()).doesNotContain(SENTINEL, noted.id().toString());
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void everyAppointmentRowReadBackCarriesTheAdministrationFacts() {
        Appointment base = committed(START, 30);
        Appointment noted = note(base, SENTINEL).orElseThrow();

        Map<String, Object> row = jdbc.sql("""
                        SELECT cancelled_at, cancelled_by, internal_note FROM appointment WHERE id = :id
                        """).param("id", base.id())
                .query((resultSet, rowNumber) -> Map.<String, Object>of(
                        "note", resultSet.getString("internal_note")))
                .single();

        assertThat(row.get("note")).isEqualTo(SENTINEL);
        assertThat(noted.toString()).isEqualTo("Appointment[redacted]").doesNotContain(SENTINEL);
    }

    private long blockingRows() {
        return jdbc.sql("SELECT count(*) FROM appointment WHERE status = 'CONFIRMED'")
                .query(Long.class).single();
    }

    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertThat(current).as("a PostgreSQL error is expected").isNotNull();
        return ((SQLException) current).getSQLState();
    }
}
