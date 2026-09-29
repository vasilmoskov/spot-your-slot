package bg.spotyourslot.scheduling.application;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
import bg.spotyourslot.scheduling.AvailabilityApplicationException;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.AvailabilityFailure;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import bg.spotyourslot.scheduling.BusyIntervalSource.BusyWindow;
import bg.spotyourslot.scheduling.domain.AvailabilityEngine;
import bg.spotyourslot.scheduling.domain.AvailabilityPolicy;
import bg.spotyourslot.scheduling.domain.AvailabilityRequest;
import bg.spotyourslot.scheduling.domain.AvailableSlot;
import bg.spotyourslot.scheduling.domain.BusyInterval;
import bg.spotyourslot.scheduling.domain.LocalBlock;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.ScheduleException;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionInputs;
import bg.spotyourslot.scheduling.domain.StaffAvailabilityInput;
import bg.spotyourslot.scheduling.infrastructure.ScheduleExceptionStore;
import bg.spotyourslot.workforce.StaffAvailabilityAccess;
import bg.spotyourslot.workforce.StaffAvailabilityAccess.EligibleStaffMember;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import java.sql.Connection;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Assembles the committed Business, Service, StaffMember, recurring-schedule,
 * exception, and busy-interval data into one call of the pure
 * {@link AvailabilityEngine}. It owns no availability rule: notice, horizon,
 * grid, DST resolution, overlap subtraction, deduplication, and ordering all
 * remain in the engine (ADR-0013). Snapshot and query design is ADR-0016,
 * including the enforced repeatable-read precondition.
 *
 * <p>Real SQL statements, in order: Business context, active Service, eligible
 * StaffMembers with their periods, schedule exceptions. The busy-interval call
 * is a fifth bulk read that currently issues no SQL.
 */
@Service
public class AvailabilityQueryService implements AvailabilityQuery {
    private static final long MIN_SERVICE_MINUTES = 1;
    private static final long MAX_SERVICE_MINUTES = 480;

    private final BusinessScheduleContextAccess businesses;
    private final ServiceAvailabilityAccess services;
    private final StaffAvailabilityAccess staffMembers;
    private final ScheduleExceptionStore exceptions;
    private final BusyIntervalSource busyIntervals;
    private final Clock clock;

    public AvailabilityQueryService(
            BusinessScheduleContextAccess businesses,
            ServiceAvailabilityAccess services,
            StaffAvailabilityAccess staffMembers,
            ScheduleExceptionStore exceptions,
            BusyIntervalSource busyIntervals,
            Clock clock) {
        this.businesses = businesses;
        this.services = services;
        this.staffMembers = staffMembers;
        this.exceptions = exceptions;
        this.busyIntervals = busyIntervals;
        this.clock = clock;
    }

    @Override
    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true,
            noRollbackFor = {
                BusinessNotBookable.class,
                ServiceNotBookable.class,
                StaffMemberNotEligible.class
            })
    public AvailabilitySnapshot calculate(
            UUID businessId, UUID serviceId, UUID staffMemberIdOrNull) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(serviceId, "serviceId");
        requireSnapshotIsolation();
        Instant calculatedAt = clock.instant();
        try {
            return calculateSnapshot(businessId, serviceId, staffMemberIdOrNull, calculatedAt);
        } catch (AvailabilityApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AvailabilityFailure(exception);
        }
    }

    /**
     * REQUIRED joins an existing transaction and silently ignores this method's
     * declared isolation, so the effective isolation is checked before any read.
     * Only a repeatable-read or serializable transaction gives the four SQL reads
     * one snapshot. A missing transaction, an unexposed isolation level, or a
     * weaker level fails before the clock or any data is read. The level exposed
     * by Spring is the one the transaction was started with; a transaction
     * started with the driver default is rejected because its level is unknown.
     */
    private static void requireSnapshotIsolation() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        boolean snapshotCoherent = TransactionSynchronizationManager.isActualTransactionActive()
                && isolation != null
                && (isolation == Connection.TRANSACTION_REPEATABLE_READ
                        || isolation == Connection.TRANSACTION_SERIALIZABLE);
        if (!snapshotCoherent) {
            throw new AvailabilityFailure(new IllegalStateException(
                    "Availability requires a repeatable-read or serializable transaction"));
        }
    }

    private AvailabilitySnapshot calculateSnapshot(
            UUID businessId, UUID serviceId, UUID staffMemberIdOrNull, Instant calculatedAt) {
        BusinessScheduleContext context = businesses.findScheduleContext(businessId)
                .filter(found -> found.status() == LifecycleStatus.ACTIVE)
                .orElseThrow(BusinessNotBookable::new);
        ZoneId zone = context.timezone();

        BookableService service = services.findBookableService(businessId, serviceId)
                .orElseThrow(ServiceNotBookable::new);
        Duration occupiedDuration = verifiedDuration(service, serviceId);

        List<EligibleStaffMember> eligible = selectEligible(
                staffMembers.findEligibleForService(businessId, serviceId), staffMemberIdOrNull);
        if (eligible.isEmpty()) {
            return snapshot(zone, calculatedAt, occupiedDuration, List.of());
        }
        List<UUID> staffMemberIds = eligible.stream().map(EligibleStaffMember::id).toList();

        // Local dates first through last (exactly 30) come from the engine's policy.
        LocalDate firstDate = LocalDate.ofInstant(calculatedAt, zone);
        LocalDate lastDate = firstDate.plusDays(AvailabilityPolicy.HORIZON_DAYS - 1L);
        // Zone-rule midnights, never 30 x 24 hours, so a DST day is 23 or 25 hours.
        Instant windowStart = firstDate.atStartOfDay(zone).toInstant();
        Instant windowEnd = firstDate
                .plusDays(AvailabilityPolicy.HORIZON_DAYS)
                .atStartOfDay(zone)
                .toInstant();

        List<ScheduleException> storedExceptions = exceptions.findOverlappingForStaff(
                businessId, firstDate, lastDate, staffMemberIds);
        requireSameBusiness(storedExceptions, businessId);
        Map<UUID, List<BusyInterval>> busyByStaff = busyIntervalsFor(
                businessId, staffMemberIds, windowStart, windowEnd);

        List<LocalBlock> closures = ScheduleExceptionInputs.businessClosures(storedExceptions);
        List<StaffAvailabilityInput> inputs = new ArrayList<>(eligible.size());
        for (EligibleStaffMember member : eligible) {
            inputs.add(new StaffAvailabilityInput(
                    member.id(),
                    recurringPeriods(member.weeklyPeriods()),
                    ScheduleExceptionInputs.overrides(member.id(), storedExceptions),
                    ScheduleExceptionInputs.additionalPeriods(member.id(), storedExceptions),
                    ScheduleExceptionInputs.timeOff(member.id(), storedExceptions),
                    busyByStaff.getOrDefault(member.id(), List.of())));
        }

        List<AvailableSlot> slots = AvailabilityEngine.calculate(new AvailabilityRequest(
                zone, occupiedDuration, calculatedAt, closures, inputs));
        return snapshot(zone, calculatedAt, occupiedDuration, publishedSlots(slots));
    }

    private static Duration verifiedDuration(BookableService service, UUID requestedServiceId) {
        if (!requestedServiceId.equals(service.id())) {
            throw new IllegalStateException("Catalog returned a different Service");
        }
        long minutes = service.duration().toMinutes();
        if (!service.duration().equals(Duration.ofMinutes(minutes))
                || minutes < MIN_SERVICE_MINUTES
                || minutes > MAX_SERVICE_MINUTES) {
            throw new IllegalStateException("Service duration violates the committed invariant");
        }
        return service.duration();
    }

    /** Validates the published list, then narrows it to the requested StaffMember. */
    private static List<EligibleStaffMember> selectEligible(
            List<EligibleStaffMember> eligible, UUID staffMemberIdOrNull) {
        Set<UUID> seen = new HashSet<>();
        for (EligibleStaffMember member : eligible) {
            if (!seen.add(member.id())) {
                throw new IllegalStateException("Workforce returned a StaffMember twice");
            }
        }
        if (staffMemberIdOrNull == null) {
            return eligible;
        }
        return eligible.stream()
                .filter(member -> member.id().equals(staffMemberIdOrNull))
                .findFirst()
                .map(List::of)
                .orElseThrow(StaffMemberNotEligible::new);
    }

    private static void requireSameBusiness(List<ScheduleException> stored, UUID businessId) {
        for (ScheduleException exception : stored) {
            if (!businessId.equals(exception.businessId())) {
                throw new IllegalStateException("Exception belongs to another Business");
            }
        }
    }

    private Map<UUID, List<BusyInterval>> busyIntervalsFor(
            UUID businessId, List<UUID> staffMemberIds, Instant from, Instant to) {
        Map<UUID, List<BusyWindow>> returned = busyIntervals.findBusyWindows(
                businessId, List.copyOf(staffMemberIds), from, to);
        Objects.requireNonNull(returned, "busy windows");
        Set<UUID> requested = new HashSet<>(staffMemberIds);
        Map<UUID, List<BusyInterval>> result = new HashMap<>();
        for (Map.Entry<UUID, List<BusyWindow>> entry : returned.entrySet()) {
            UUID staffMemberId = Objects.requireNonNull(entry.getKey(), "busy StaffMember");
            if (!requested.contains(staffMemberId)) {
                throw new IllegalStateException("Busy windows returned for an unrequested StaffMember");
            }
            List<BusyWindow> windows = Objects.requireNonNull(entry.getValue(), "busy list");
            List<BusyInterval> intervals = new ArrayList<>(windows.size());
            BusyWindow previous = null;
            for (BusyWindow window : windows) {
                Objects.requireNonNull(window, "busy window");
                if (previous != null && isAfter(previous, window)) {
                    throw new IllegalStateException("Busy windows are not ordered");
                }
                intervals.add(new BusyInterval(window.start(), window.end()));
                previous = window;
            }
            result.put(staffMemberId, List.copyOf(intervals));
        }
        return result;
    }

    private static boolean isAfter(BusyWindow previous, BusyWindow next) {
        int byStart = previous.start().compareTo(next.start());
        return byStart > 0 || (byStart == 0 && previous.end().isAfter(next.end()));
    }

    private static Map<DayOfWeek, List<LocalPeriod>> recurringPeriods(List<WorkingPeriod> weekly) {
        Map<DayOfWeek, List<LocalPeriod>> byDay = new EnumMap<>(DayOfWeek.class);
        for (WorkingPeriod period : weekly) {
            byDay.computeIfAbsent(period.weekday(), day -> new ArrayList<>())
                    .add(new LocalPeriod(period.startTime(), period.endTime()));
        }
        return byDay;
    }

    private static List<AvailabilitySlot> publishedSlots(List<AvailableSlot> slots) {
        List<AvailabilitySlot> published = new ArrayList<>(slots.size());
        for (AvailableSlot slot : slots) {
            published.add(new AvailabilitySlot(
                    slot.start(), slot.end(), slot.startOffset(), slot.staffMemberIds()));
        }
        return published;
    }

    private static AvailabilitySnapshot snapshot(
            ZoneId zone, Instant calculatedAt, Duration duration, List<AvailabilitySlot> slots) {
        return new AvailabilitySnapshot(zone, calculatedAt, duration, slots);
    }
}
