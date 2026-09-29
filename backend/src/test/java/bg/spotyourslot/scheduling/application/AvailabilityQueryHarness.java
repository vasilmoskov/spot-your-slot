package bg.spotyourslot.scheduling.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import bg.spotyourslot.scheduling.BusyIntervalSource.BusyWindow;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import bg.spotyourslot.workforce.StaffAvailabilityAccess;
import bg.spotyourslot.workforce.StaffAvailabilityAccess.EligibleStaffMember;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Wires {@link AvailabilityQueryService} to mocked published contracts and a controllable clock. */
final class AvailabilityQueryHarness {
    static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    static final UUID BUSINESS = UUID.fromString("10000000-0000-0000-0000-000000000001");
    static final UUID SERVICE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    static final UUID STAFF_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID STAFF_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final Instant STORED_AT = Instant.parse("2026-01-01T00:00:00Z");

    final BusinessScheduleContextAccess businesses = mock(BusinessScheduleContextAccess.class);
    final ServiceAvailabilityAccess services = mock(ServiceAvailabilityAccess.class);
    final StaffAvailabilityAccess staffMembers = mock(StaffAvailabilityAccess.class);
    final ScheduleExceptionStore exceptions = mock(ScheduleExceptionStore.class);
    final BusyIntervalSource busy = mock(BusyIntervalSource.class);
    final MutableTestClock clock;
    private final ZoneId zone;

    AvailabilityQueryHarness(ZoneId zone, Instant now) {
        this.zone = zone;
        this.clock = new MutableTestClock(now);
        business(LifecycleStatus.ACTIVE);
        service(30);
        staff();
        exceptions();
        when(busy.findBusyWindows(any(), anyCollection(), any(), any())).thenReturn(Map.of());
    }

    AvailabilityQueryService subject() {
        return new AvailabilityQueryService(
                businesses, services, staffMembers, exceptions, busy, clock);
    }

    AvailabilitySnapshot calculate(UUID staffMemberIdOrNull) {
        return inRepeatableReadTransaction(
                () -> subject().calculate(BUSINESS, SERVICE, staffMemberIdOrNull));
    }

    /**
     * The unit tests call the service without Spring's proxy, so the transaction
     * state the proxy would establish is simulated around the call.
     */
    static <T> T inRepeatableReadTransaction(Supplier<T> action) {
        return inTransactionState(true, Connection.TRANSACTION_REPEATABLE_READ, action);
    }

    static <T> T inTransactionState(boolean active, Integer isolation, Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(active);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
        try {
            return action.get();
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    AvailabilityQueryHarness business(LifecycleStatus status) {
        when(businesses.findScheduleContext(BUSINESS))
                .thenReturn(Optional.of(new BusinessScheduleContext(BUSINESS, status, zone)));
        return this;
    }

    AvailabilityQueryHarness service(int minutes) {
        return service(Duration.ofMinutes(minutes));
    }

    AvailabilityQueryHarness service(Duration duration) {
        when(services.findBookableService(BUSINESS, SERVICE))
                .thenReturn(Optional.of(new BookableService(SERVICE, duration)));
        return this;
    }

    AvailabilityQueryHarness staff(EligibleStaffMember... members) {
        when(staffMembers.findEligibleForService(BUSINESS, SERVICE)).thenReturn(List.of(members));
        return this;
    }

    AvailabilityQueryHarness exceptions(ScheduleException... stored) {
        when(exceptions.findOverlappingForStaff(eq(BUSINESS), any(), any(), anyCollection()))
                .thenReturn(List.of(stored));
        return this;
    }

    AvailabilityQueryHarness busyWindows(Map<UUID, List<BusyWindow>> windows) {
        when(busy.findBusyWindows(any(), anyCollection(), any(), any())).thenReturn(windows);
        return this;
    }

    static EligibleStaffMember everyDay(UUID id, String start, String end) {
        List<WorkingPeriod> periods = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            periods.add(period(day, start, end));
        }
        return new EligibleStaffMember(id, periods);
    }

    static EligibleStaffMember withoutSchedule(UUID id) {
        return new EligibleStaffMember(id, List.of());
    }

    static WorkingPeriod period(DayOfWeek day, String start, String end) {
        return new WorkingPeriod(day, LocalTime.parse(start), LocalTime.parse(end));
    }

    static ScheduleException stored(ScheduleExceptionContent content) {
        return new ScheduleException(UUID.randomUUID(), BUSINESS, content, 0, STORED_AT, STORED_AT);
    }

    static Instant local(ZoneId zone, LocalDate date, String time) {
        return date.atTime(LocalTime.parse(time)).atZone(zone).toInstant();
    }

    static List<LocalTime> localStarts(AvailabilitySnapshot snapshot, LocalDate date) {
        List<LocalTime> starts = new ArrayList<>();
        for (AvailabilitySlot slot : snapshot.slots()) {
            if (slot.start().atZone(snapshot.timezone()).toLocalDate().equals(date)) {
                starts.add(slot.start().atZone(snapshot.timezone()).toLocalTime());
            }
        }
        return starts;
    }

    static List<LocalDate> distinctDates(AvailabilitySnapshot snapshot) {
        return snapshot.slots().stream()
                .map(slot -> slot.start().atZone(snapshot.timezone()).toLocalDate())
                .distinct()
                .toList();
    }
}
