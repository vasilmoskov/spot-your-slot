package bg.spotyourslot.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.ConcurrentUpdate;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.InvalidInput;
import bg.spotyourslot.workforce.StaffWorkingScheduleApplicationException.StaffWorkingScheduleNotFound;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.ReplaceWorkingPeriodsCommand;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingScheduleRow;
import bg.spotyourslot.workforce.infrastructure.StaffWorkingScheduleStore;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ADR-0025 participation of the weekly working-schedule replacement, without a database: the
 * schedule revision is advanced exactly once, after validation and the version check and before
 * the aggregate mutation, and a rejected replacement never reaches it. The transactional and
 * concurrent behavior is proven against PostgreSQL in {@code ScheduleRevisionMutationIntegrationTests}.
 */
@ExtendWith(MockitoExtension.class)
class StaffWorkingScheduleServiceRevisionTests {
    private static final UUID BUSINESS_ID = UUID.fromString("00000000-0000-0000-0000-000000000c01");
    private static final UUID STAFF_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000c02");
    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    @Mock StaffWorkingScheduleStore store;
    @Mock ScheduleRevisionBump scheduleRevision;

    private StaffWorkingScheduleService service;

    @BeforeEach
    void setUp() {
        service = new StaffWorkingScheduleService(
                store,
                new StaffWorkingScheduleInputValidator(),
                scheduleRevision,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anAcceptedReplacementAdvancesTheRevisionOnceBetweenTheChecksAndTheAggregateWrite() {
        when(store.findByBusinessIdAndStaffMemberId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(row(2)));
        when(store.advanceScheduleVersion(BUSINESS_ID, STAFF_MEMBER_ID, 2L, NOW))
                .thenReturn(Optional.of(row(3)));

        var details = service.replace(BUSINESS_ID, STAFF_MEMBER_ID, command(2L));

        assertThat(details.version()).isEqualTo(3);
        InOrder order = Mockito.inOrder(store, scheduleRevision);
        order.verify(store).findByBusinessIdAndStaffMemberId(BUSINESS_ID, STAFF_MEMBER_ID);
        order.verify(scheduleRevision).advance(BUSINESS_ID);
        order.verify(store).advanceScheduleVersion(BUSINESS_ID, STAFF_MEMBER_ID, 2L, NOW);
        order.verify(store).replacePeriods(any(), any(), any());
        verify(scheduleRevision, Mockito.times(1)).advance(any());
    }

    @Test
    void aStaleVersionOrInvalidInputNeverReachesTheRevision() {
        when(store.findByBusinessIdAndStaffMemberId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(row(5)));

        assertThatThrownBy(() -> service.replace(BUSINESS_ID, STAFF_MEMBER_ID, command(4L)))
                .isInstanceOf(ConcurrentUpdate.class);
        assertThatThrownBy(() -> service.replace(
                        BUSINESS_ID, STAFF_MEMBER_ID, new ReplaceWorkingPeriodsCommand(List.of(), null)))
                .isInstanceOf(InvalidInput.class);

        verifyNoInteractions(scheduleRevision);
        verify(store, never()).advanceScheduleVersion(any(), any(), anyLong(), any());
        verify(store, never()).replacePeriods(any(), any(), any());
    }

    @Test
    void aMissingScheduleNeverReachesTheRevision() {
        when(store.findByBusinessIdAndStaffMemberId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(BUSINESS_ID, STAFF_MEMBER_ID, command(0L)))
                .isInstanceOf(StaffWorkingScheduleNotFound.class);

        verifyNoInteractions(scheduleRevision);
    }

    @Test
    void aRevisionConcurrencyVictimIsTheExistingSanitizedConflictAndNothingIsWritten() {
        when(store.findByBusinessIdAndStaffMemberId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(row(0)));
        Mockito.doThrow(new ScheduleRevisionConcurrentConflict())
                .when(scheduleRevision).advance(BUSINESS_ID);

        assertThatThrownBy(() -> service.replace(BUSINESS_ID, STAFF_MEMBER_ID, command(0L)))
                .isInstanceOf(ConcurrentUpdate.class)
                .hasNoCause();

        verify(store, never()).advanceScheduleVersion(any(), any(), anyLong(), any());
        verify(store, never()).replacePeriods(any(), any(), any());
    }

    @Test
    void aRevisionFailureIsNotMaskedAndNothingIsWritten() {
        when(store.findByBusinessIdAndStaffMemberId(BUSINESS_ID, STAFF_MEMBER_ID))
                .thenReturn(Optional.of(row(0)));
        Mockito.doThrow(new ScheduleRevisionFailure()).when(scheduleRevision).advance(BUSINESS_ID);

        assertThatThrownBy(() -> service.replace(BUSINESS_ID, STAFF_MEMBER_ID, command(0L)))
                .isExactlyInstanceOf(ScheduleRevisionFailure.class);

        verify(store, never()).advanceScheduleVersion(any(), any(), anyLong(), any());
        verify(store, never()).replacePeriods(any(), any(), any());
    }

    private static StaffWorkingScheduleRow row(long version) {
        return new StaffWorkingScheduleRow(BUSINESS_ID, STAFF_MEMBER_ID, version, NOW, NOW);
    }

    private static ReplaceWorkingPeriodsCommand command(long expectedVersion) {
        return new ReplaceWorkingPeriodsCommand(
                List.of(new WorkingPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))),
                expectedVersion);
    }
}
