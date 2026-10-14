package bg.spotyourslot.scheduling.application;

import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
import bg.spotyourslot.scheduling.AvailabilityApplicationException;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.AvailabilityFailure;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.SelectionTooLong;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.scheduling.AvailabilityRecords.BusinessAvailability;
import bg.spotyourslot.scheduling.AvailabilityRecords.SelectionAvailability;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
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
 * <p>Real SQL statements, in order: Business context, the selected active Services (one
 * bulk statement), eligible StaffMembers with their periods (one joined statement), schedule
 * exceptions; the count does not depend on the number of Services (ADR-0027). The busy-interval call
 * is a fifth bulk read: the booking-owned {@link BusyIntervalSource} reads the persisted
 * Appointments of the eligible StaffMembers in one statement.
 */
@Service
public class AvailabilityQueryService implements AvailabilityQuery {
    private static final long MIN_SERVICE_MINUTES = 1;
    private static final long MAX_SERVICE_MINUTES = 480;
    private static final int MAX_SERVICES = 5;
    private static final long MAX_VISIT_MINUTES = 480;

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
        Objects.requireNonNull(serviceId, "serviceId");
        return calculate(businessId, List.of(serviceId), staffMemberIdOrNull).snapshot();
    }

    @Override
    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true,
            noRollbackFor = {
                BusinessNotBookable.class,
                ServiceNotBookable.class,
                SelectionTooLong.class,
                StaffMemberNotEligible.class
            })
    public SelectionAvailability calculate(
            UUID businessId, Collection<UUID> serviceIds, UUID staffMemberIdOrNull) {
        Objects.requireNonNull(businessId, "businessId");
        Set<UUID> selection = validatedSelection(serviceIds);
        requireSnapshotIsolation();
        Instant calculatedAt = clock.instant();
        try {
            return calculateSelection(businessId, selection, staffMemberIdOrNull, calculatedAt);
        } catch (AvailabilityApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AvailabilityFailure(exception);
        }
    }

    @Override
    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            readOnly = true,
            noRollbackFor = {BusinessNotBookable.class, StaffMemberNotEligible.class})
    public BusinessAvailability calculateForBusiness(
            UUID businessId,
            Duration occupiedDuration,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            Instant now,
            UUID ignoredAppointmentIdOrNull) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(staffMemberId, "staffMemberId");
        Objects.requireNonNull(now, "now");
        requireBusinessSideInputs(occupiedDuration, firstDate, lastDate);
        requireSnapshotIsolation();
        try {
            return calculateBusinessSide(
                    businessId, occupiedDuration, staffMemberId, firstDate, lastDate, now,
                    ignoredAppointmentIdOrNull);
        } catch (AvailabilityApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AvailabilityFailure(exception);
        }
    }

    /** Caller errors of the Business-side entry, rejected before any data read. */
    private static void requireBusinessSideInputs(
            Duration occupiedDuration, LocalDate firstDate, LocalDate lastDate) {
        Objects.requireNonNull(occupiedDuration, "occupiedDuration");
        Objects.requireNonNull(firstDate, "firstDate");
        Objects.requireNonNull(lastDate, "lastDate");
        long minutes = occupiedDuration.toMinutes();
        if (!occupiedDuration.equals(Duration.ofMinutes(minutes))
                || minutes < MIN_SERVICE_MINUTES
                || minutes > MAX_VISIT_MINUTES) {
            throw new IllegalArgumentException("The occupied duration must be 1 to 480 whole minutes");
        }
        if (firstDate.isBefore(AvailabilityPolicy.BUSINESS_FIRST_SUPPORTED_DATE)
                || lastDate.isAfter(AvailabilityPolicy.BUSINESS_LAST_SUPPORTED_DATE)) {
            throw new IllegalArgumentException("The date window is outside the supported dates");
        }
        if (lastDate.isBefore(firstDate)
                || ChronoUnit.DAYS.between(firstDate, lastDate) + 1
                        > AvailabilityPolicy.BUSINESS_MAX_DATES) {
            throw new IllegalArgumentException("The date window must hold between one and 31 dates");
        }
    }

    private BusinessAvailability calculateBusinessSide(
            UUID businessId,
            Duration occupiedDuration,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            Instant now,
            UUID ignoredAppointmentId) {
        BusinessScheduleContext context = businesses.findScheduleContext(businessId)
                .filter(found -> found.status() == LifecycleStatus.ACTIVE)
                .orElseThrow(BusinessNotBookable::new);
        ZoneId zone = context.timezone();

        EligibleStaffMember member = staffMembers.findActiveWithWeeklyPeriods(businessId, staffMemberId)
                .orElseThrow(StaffMemberNotEligible::new);
        if (!member.id().equals(staffMemberId)) {
            throw new IllegalStateException("Workforce returned another StaffMember");
        }

        // Zone-rule midnights, so a daylight-saving day is 23 or 25 hours.
        Instant windowStart = firstDate.atStartOfDay(zone).toInstant();
        Instant windowEnd = lastDate.plusDays(1).atStartOfDay(zone).toInstant();
        List<ScheduleException> storedExceptions = exceptions.findOverlappingForStaff(
                businessId, firstDate, lastDate, List.of(staffMemberId));
        requireSameBusiness(storedExceptions, businessId);
        Map<UUID, List<BusyInterval>> busyByStaff = busyIntervalsFor(
                businessId, List.of(staffMemberId), windowStart, windowEnd, ignoredAppointmentId);

        List<AvailableSlot> slots = AvailabilityEngine.calculate(new AvailabilityRequest(
                zone,
                occupiedDuration,
                now,
                AvailabilityPolicy.BUSINESS_MINIMUM_NOTICE,
                firstDate,
                (int) (ChronoUnit.DAYS.between(firstDate, lastDate) + 1),
                ScheduleExceptionInputs.businessClosures(storedExceptions),
                List.of(new StaffAvailabilityInput(
                        staffMemberId,
                        recurringPeriods(member.weeklyPeriods()),
                        ScheduleExceptionInputs.overrides(staffMemberId, storedExceptions),
                        ScheduleExceptionInputs.additionalPeriods(staffMemberId, storedExceptions),
                        ScheduleExceptionInputs.timeOff(staffMemberId, storedExceptions),
                        busyByStaff.getOrDefault(staffMemberId, List.of())))));
        return new BusinessAvailability(
                zone, now, occupiedDuration, firstDate, lastDate, publishedSlots(slots));
    }

    /** A caller error, rejected before any clock or data read: 1..5 distinct non-null identifiers. */
    private static Set<UUID> validatedSelection(Collection<UUID> serviceIds) {
        Objects.requireNonNull(serviceIds, "serviceIds");
        if (serviceIds.isEmpty() || serviceIds.size() > MAX_SERVICES) {
            throw new IllegalArgumentException("A selection holds one to five Services");
        }
        Set<UUID> distinct = new HashSet<>();
        for (UUID serviceId : serviceIds) {
            Objects.requireNonNull(serviceId, "serviceId");
            if (!distinct.add(serviceId)) {
                throw new IllegalArgumentException("A selection holds each Service once");
            }
        }
        return Set.copyOf(distinct);
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

    private SelectionAvailability calculateSelection(
            UUID businessId, Set<UUID> serviceIds, UUID staffMemberIdOrNull, Instant calculatedAt) {
        BusinessScheduleContext context = businesses.findScheduleContext(businessId)
                .filter(found -> found.status() == LifecycleStatus.ACTIVE)
                .orElseThrow(BusinessNotBookable::new);
        ZoneId zone = context.timezone();

        Duration occupiedDuration = visitDuration(
                serviceIds, services.findBookableServices(businessId, serviceIds));

        List<EligibleStaffMember> eligible = selectEligible(
                staffMembers.findEligibleForServices(businessId, serviceIds), staffMemberIdOrNull);
        if (eligible.isEmpty()) {
            return new SelectionAvailability(
                    snapshot(zone, calculatedAt, occupiedDuration, List.of()), List.of());
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
                businessId, staffMemberIds, windowStart, windowEnd, null);

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
        return new SelectionAvailability(
                snapshot(zone, calculatedAt, occupiedDuration, publishedSlots(slots)),
                staffMemberIds);
    }

    /**
     * Every requested Service must come back exactly once; a missing, foreign, or inactive one
     * (absent from the bookable list) makes the whole selection unbookable. The visit lasts the
     * sum of the individually verified durations and may not exceed the visit ceiling.
     */
    private static Duration visitDuration(Set<UUID> requested, List<BookableService> bookable) {
        Set<UUID> seen = new HashSet<>();
        long totalMinutes = 0;
        for (BookableService service : bookable) {
            if (!requested.contains(service.id()) || !seen.add(service.id())) {
                throw new IllegalStateException("Catalog returned an unrequested or repeated Service");
            }
            totalMinutes += verifiedMinutes(service);
        }
        if (seen.size() != requested.size()) {
            throw new ServiceNotBookable();
        }
        if (totalMinutes > MAX_VISIT_MINUTES) {
            throw new SelectionTooLong();
        }
        return Duration.ofMinutes(totalMinutes);
    }

    private static long verifiedMinutes(BookableService service) {
        long minutes = service.duration().toMinutes();
        if (!service.duration().equals(Duration.ofMinutes(minutes))
                || minutes < MIN_SERVICE_MINUTES
                || minutes > MAX_SERVICE_MINUTES) {
            throw new IllegalStateException("Service duration violates the committed invariant");
        }
        return minutes;
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
            UUID businessId,
            List<UUID> staffMemberIds,
            Instant from,
            Instant to,
            UUID ignoredAppointmentIdOrNull) {
        Map<UUID, List<BusyWindow>> returned = ignoredAppointmentIdOrNull == null
                ? busyIntervals.findBusyWindows(businessId, List.copyOf(staffMemberIds), from, to)
                : busyIntervals.findBusyWindows(
                        businessId, List.copyOf(staffMemberIds), from, to, ignoredAppointmentIdOrNull);
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
