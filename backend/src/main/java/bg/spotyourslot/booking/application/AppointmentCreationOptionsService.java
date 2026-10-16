package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentCreationOptions;
import bg.spotyourslot.booking.AppointmentCreationOptionsException;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.AccessDenied;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.Failure;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.InvalidInput;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.ServiceUnavailable;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationAvailability;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationOptions;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.EligibleStaffMember;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.OfferedStart;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess;
import bg.spotyourslot.catalog.ServiceAvailabilityAccess.BookableService;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.BusinessAvailability;
import bg.spotyourslot.workforce.PublicStaffAccess;
import bg.spotyourslot.workforce.PublicStaffAccess.PublicStaffMember;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link AppointmentCreationOptions}. Every operation is one read-only
 * {@code REPEATABLE READ} transaction whose first statement (the Business context) fixes the
 * snapshot; the owner check, the Service durations, the StaffMember intersection, and the
 * availability reads all join it. It takes no lock, writes nothing, reads the clock once (truncated
 * to the microseconds PostgreSQL stores, as the creation does), and never logs.
 *
 * <p>Decision precedence, the creation's: authorization, lifecycle, domain bounds, then the Service
 * and StaffMember eligibility. The availability is the Business-side policy of ADR-0029 section 1
 * through {@link AvailabilityQuery#calculateForBusiness}, so the offered starts are exactly the ones
 * the creation accepts: zero notice, the summed duration, no ignored Appointment.
 *
 * <p><b>Statements</b> (each bounded and independent of the number of Services, StaffMembers,
 * Appointments, and slots): options 4 (context, owner, Service durations, StaffMember
 * intersection); availability 3 before the engine (context, owner, Service durations), the
 * intersection (1), and the engine's own bounded reads.
 */
@Service
public class AppointmentCreationOptionsService implements AppointmentCreationOptions {
    static final int MAX_SERVICES = 5;
    static final long MAX_MINUTES = 480;
    static final LocalDate FIRST_SUPPORTED_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate LAST_SUPPORTED_DATE = LocalDate.of(2100, 12, 31);

    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final ServiceAvailabilityAccess services;
    private final PublicStaffAccess staff;
    private final AvailabilityQuery availability;
    private final Clock clock;

    public AppointmentCreationOptionsService(
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners,
            ServiceAvailabilityAccess services,
            PublicStaffAccess staff,
            AvailabilityQuery availability,
            Clock clock) {
        this.businesses = businesses;
        this.owners = owners;
        this.services = services;
        this.staff = staff;
        this.availability = availability;
        this.clock = clock;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public CreationOptions options(AuthenticatedBusinessContext context, Collection<UUID> serviceIds) {
        return guarded(() -> {
            Access access = authorize(context);
            requireSelection(serviceIds);
            long minutes = durationOf(access.businessId(), serviceIds);
            List<EligibleStaffMember> eligible = eligible(access.businessId(), serviceIds);
            return new CreationOptions(access.zone(), (int) minutes, eligible);
        });
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public CreationAvailability availability(
            AuthenticatedBusinessContext context,
            Collection<UUID> serviceIds,
            UUID staffMemberId,
            LocalDate date) {
        return guarded(() -> {
            Access access = authorize(context);
            requireSelection(serviceIds);
            if (staffMemberId == null
                    || date == null
                    || date.isBefore(FIRST_SUPPORTED_DATE)
                    || date.isAfter(LAST_SUPPORTED_DATE)) {
                throw new InvalidInput();
            }
            long minutes = durationOf(access.businessId(), serviceIds);
            boolean eligible = eligible(access.businessId(), serviceIds).stream()
                    .anyMatch(member -> member.id().equals(staffMemberId));
            if (!eligible) {
                throw new StaffUnavailable();
            }
            // One clock read, truncated to what PostgreSQL stores, like the creation attempt.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            BusinessAvailability offered;
            try {
                offered = availability.calculateForBusiness(
                        access.businessId(),
                        Duration.ofMinutes(minutes),
                        staffMemberId,
                        date,
                        date,
                        now,
                        null);
            } catch (StaffMemberNotEligible notEligible) {
                throw new StaffUnavailable();
            }
            ZoneId zone = offered.timezone();
            List<OfferedStart> starts = new ArrayList<>();
            for (AvailabilitySlot slot : offered.slots()) {
                if (slot.staffMemberIds().contains(staffMemberId)
                        && LocalDate.ofInstant(slot.start(), zone).equals(date)) {
                    starts.add(new OfferedStart(slot.start(), slot.end()));
                }
            }
            return new CreationAvailability(zone, date, (int) minutes, starts);
        });
    }

    private record Access(UUID businessId, ZoneId zone) {
    }

    /**
     * The Business context is the first statement and fixes the snapshot; an unknown Business or a
     * non-owner is {@link AccessDenied}. Only an {@code ACTIVE} Business offers choices.
     */
    private Access authorize(AuthenticatedBusinessContext context) {
        if (context == null || context.userId() == null) {
            throw new AccessDenied();
        }
        UUID businessId = context.selectedBusinessId().orElseThrow(SelectedBusinessRequired::new);
        BusinessScheduleContext business = businesses.findScheduleContext(businessId)
                .orElseThrow(AccessDenied::new);
        if (owners.authorize(context.userId(), businessId) != Authorization.GRANTED) {
            throw new AccessDenied();
        }
        LifecycleStatus status = business.status();
        if (status == LifecycleStatus.DRAFT) {
            throw new BusinessNotActive();
        }
        if (status == LifecycleStatus.SUSPENDED) {
            throw new BusinessSuspended();
        }
        return new Access(businessId, business.timezone());
    }

    private static void requireSelection(Collection<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty() || serviceIds.size() > MAX_SERVICES) {
            throw new InvalidInput();
        }
        Set<UUID> distinct = new HashSet<>();
        for (UUID id : serviceIds) {
            if (id == null || !distinct.add(id)) {
                throw new InvalidInput();
            }
        }
    }

    /** The summed current durations; a missing, foreign, or inactive Service is one result. */
    private long durationOf(UUID businessId, Collection<UUID> serviceIds) {
        List<BookableService> found = services.findBookableServices(businessId, serviceIds);
        if (found.size() != serviceIds.size()) {
            throw new ServiceUnavailable();
        }
        long minutes = 0;
        for (BookableService service : found) {
            minutes += service.duration().toMinutes();
        }
        if (minutes < 1 || minutes > MAX_MINUTES) {
            throw new InvalidInput();
        }
        return minutes;
    }

    private List<EligibleStaffMember> eligible(UUID businessId, Collection<UUID> serviceIds) {
        List<EligibleStaffMember> members = new ArrayList<>();
        for (PublicStaffMember member : staff.findBookableStaffForServices(businessId, serviceIds)) {
            members.add(new EligibleStaffMember(member.id(), member.displayName()));
        }
        return members;
    }

    /** Typed contract failures and the identity selection failure pass; anything else is sanitized. */
    private static <T> T guarded(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AppointmentCreationOptionsException | SelectedBusinessRequired known) {
            throw known;
        } catch (RuntimeException unexpected) {
            throw new Failure();
        }
    }
}
