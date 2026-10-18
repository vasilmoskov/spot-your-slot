package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.EligibleStaffMember;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.OfferedStart;
import bg.spotyourslot.booking.AppointmentRescheduleOptions;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.AccessDenied;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.BusinessNotActive;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.BusinessSuspended;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.Failure;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.InvalidInput;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.StaffUnavailable;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.StateConflict;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.CurrentStaffMember;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleAvailability;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleOptions;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentServiceLine;
import bg.spotyourslot.booking.domain.AppointmentTransitions;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
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
import bg.spotyourslot.workforce.StaffAvailabilityAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link AppointmentRescheduleOptions}. Every operation is one read-only
 * {@code REPEATABLE READ} transaction whose first statement (the Business context) fixes the
 * snapshot; the owner check, the stored Appointment, the StaffMember reads, and the availability read
 * all join it. It takes no lock, writes nothing, reads the clock once (after the Appointment exists,
 * truncated to the microseconds PostgreSQL stores, like the reschedule), and never logs.
 *
 * <p>Decision precedence, the reschedule's: authorization, lifecycle, domain bounds, existence, the
 * Appointment state, then the StaffMember. The duration and the Service identities are the
 * Appointment's stored ones; no Service row is read, so a Service deactivated or repriced later
 * changes nothing. The StaffMember rules are the reschedule's: keeping the current StaffMember needs
 * only that it is still active (no assignment check), a replacement must be active and assigned to
 * every stored Service. The availability is the Business-side policy of ADR-0029 section 1 through
 * {@link AvailabilityQuery#calculateForBusiness}, with zero notice, the stored duration, and exactly
 * this Appointment ignored, so every offered start is one the reschedule accepts.
 *
 * <p><b>Statements</b> (each bounded and independent of the number of Service lines, StaffMembers,
 * Appointments, and slots): options 6 (context, owner, Appointment, its lines, the replacement
 * intersection, the current StaffMember's activity); availability 5 before the engine for a
 * replacement (context, owner, Appointment, lines, intersection) or 4 for the current StaffMember,
 * and the engine's own bounded reads.
 */
@Service
public class AppointmentRescheduleOptionsService implements AppointmentRescheduleOptions {
    static final LocalDate FIRST_SUPPORTED_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate LAST_SUPPORTED_DATE = LocalDate.of(2100, 12, 31);

    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final AppointmentStore appointments;
    private final PublicStaffAccess staff;
    private final StaffAvailabilityAccess staffActivity;
    private final AvailabilityQuery availability;
    private final Clock clock;

    public AppointmentRescheduleOptionsService(
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners,
            AppointmentStore appointments,
            PublicStaffAccess staff,
            StaffAvailabilityAccess staffActivity,
            AvailabilityQuery availability,
            Clock clock) {
        this.businesses = businesses;
        this.owners = owners;
        this.appointments = appointments;
        this.staff = staff;
        this.staffActivity = staffActivity;
        this.availability = availability;
        this.clock = clock;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public RescheduleOptions options(AuthenticatedBusinessContext context, UUID appointmentId) {
        return guarded(() -> {
            Access access = authorize(context);
            if (appointmentId == null) {
                throw new InvalidInput();
            }
            Appointment current = stored(access.businessId(), appointmentId);
            requireMovable(current, clock.instant().truncatedTo(ChronoUnit.MICROS));
            List<EligibleStaffMember> replacements = new ArrayList<>();
            for (PublicStaffMember member : eligible(access.businessId(), current)) {
                if (!member.id().equals(current.staffMemberId())) {
                    replacements.add(new EligibleStaffMember(member.id(), member.displayName()));
                }
            }
            boolean active = staffActivity
                    .findActiveWithWeeklyPeriods(access.businessId(), current.staffMemberId())
                    .isPresent();
            return new RescheduleOptions(
                    access.zone(),
                    current.durationMinutes(),
                    new CurrentStaffMember(current.staffMemberId(), current.staffDisplayName(), active),
                    replacements);
        });
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public RescheduleAvailability availability(
            AuthenticatedBusinessContext context, UUID appointmentId, UUID staffMemberId, LocalDate date) {
        return guarded(() -> {
            Access access = authorize(context);
            if (appointmentId == null
                    || staffMemberId == null
                    || date == null
                    || date.isBefore(FIRST_SUPPORTED_DATE)
                    || date.isAfter(LAST_SUPPORTED_DATE)) {
                throw new InvalidInput();
            }
            Appointment current = stored(access.businessId(), appointmentId);
            // One clock read, truncated to what PostgreSQL stores, like the reschedule attempt.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            requireMovable(current, now);
            boolean keep = staffMemberId.equals(current.staffMemberId());
            if (!keep && eligible(access.businessId(), current).stream()
                    .noneMatch(member -> member.id().equals(staffMemberId))) {
                throw new StaffUnavailable();
            }
            BusinessAvailability offered;
            try {
                offered = availability.calculateForBusiness(
                        access.businessId(),
                        Duration.ofMinutes(current.durationMinutes()),
                        staffMemberId,
                        date,
                        date,
                        now,
                        current.id());
            } catch (StaffMemberNotEligible notEligible) {
                throw new StaffUnavailable();
            }
            if (offered.occupiedDuration().toMinutes() != current.durationMinutes()) {
                throw new IllegalStateException("Availability and the stored duration disagree");
            }
            ZoneId zone = offered.timezone();
            List<OfferedStart> starts = new ArrayList<>();
            for (AvailabilitySlot slot : offered.slots()) {
                if (!slot.staffMemberIds().contains(staffMemberId)
                        || !LocalDate.ofInstant(slot.start(), zone).equals(date)) {
                    continue;
                }
                // Moving to the same start with the same StaffMember changes nothing and is refused.
                if (keep && slot.start().equals(current.startAt())) {
                    continue;
                }
                starts.add(new OfferedStart(slot.start(), slot.end()));
            }
            return new RescheduleAvailability(zone, date, current.durationMinutes(), starts);
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

    /** The stored Appointment of the Business; a foreign one is as missing as an absent one. */
    private Appointment stored(UUID businessId, UUID appointmentId) {
        return appointments.find(businessId, appointmentId).orElseThrow(AppointmentNotFound::new);
    }

    /** The reschedule's own state check at the attempt instant: confirmed and not yet started. */
    private static void requireMovable(Appointment current, Instant now) {
        if (AppointmentTransitions.rescheduleStateRefusal(current, now) != null) {
            throw new StateConflict();
        }
    }

    /** The active StaffMembers assigned to every stored Service line, the reassignment eligibility. */
    private List<PublicStaffMember> eligible(UUID businessId, Appointment current) {
        List<UUID> serviceIds = current.serviceLines().stream().map(AppointmentServiceLine::serviceId).toList();
        return staff.findBookableStaffForServices(businessId, serviceIds);
    }

    /** Typed contract failures and the identity selection failure pass; anything else is sanitized. */
    private static <T> T guarded(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AppointmentRescheduleOptionsException | SelectedBusinessRequired known) {
            throw known;
        } catch (RuntimeException unexpected) {
            throw new Failure();
        }
    }
}
