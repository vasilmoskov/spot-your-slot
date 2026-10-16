package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedService;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduleAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Outcome;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejected;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejection;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rescheduled;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentServiceLine;
import bg.spotyourslot.booking.domain.AppointmentTransitions;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Applied;
import bg.spotyourslot.booking.domain.AppointmentTransitions.RescheduleUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.StaffTarget;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Transition;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.BusinessAvailability;
import bg.spotyourslot.workforce.StaffBookingAccess;
import bg.spotyourslot.workforce.StaffBookingAccess.BookingStaffMember;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The body of one owner reschedule or reassignment attempt (ADR-0028 sections 3 and 6, ADR-0029
 * section 4). It must run inside the single repeatable-read, read-write transaction that
 * {@link BusinessAppointmentAdministrationService} begins for the attempt; every collaborator joins
 * it and none opens another. It returns an outcome or throws and never swallows a failure, so a
 * transaction that threw is never continued.
 *
 * <p>Statement order, the total lock order of ADR-0025 and ADR-0029 section 3: the Business
 * schedule context {@code FOR SHARE} (the first statement, which fixes the snapshot), the owner
 * Membership {@code FOR SHARE}, a plain Appointment read, the target StaffMember {@code FOR SHARE}
 * (for a reassignment through the eligibility lock over the stored Service identifiers, for a
 * time-only move through the active-StaffMember lock with no assignment check), the schedule
 * revision {@code FOR SHARE}, the Business-side availability for the stored duration that ignores
 * only this Appointment, and last the conditional {@code UPDATE} carrying the expected version, which
 * the exclusion constraint judges. No Service row is locked and no Service is checked: a Service
 * deactivated later, or an assignment removed later, does not by itself block a time-only move.
 *
 * <p>Decisions in this precedence: authorization, lifecycle, domain bounds, existence, version,
 * state, no-change, StaffMember eligibility, a start before the attempt instant, and last whether
 * the requested start is offered. The attempt reads the clock once, after the Appointment exists.
 */
@Component
class BusinessAppointmentRescheduleProcedure {
    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final StaffBookingAccess staffMembers;
    private final ScheduleRevisionGuard scheduleRevision;
    private final AvailabilityQuery availability;
    private final AppointmentStore appointments;
    private final Clock clock;

    BusinessAppointmentRescheduleProcedure(
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners,
            StaffBookingAccess staffMembers,
            ScheduleRevisionGuard scheduleRevision,
            AvailabilityQuery availability,
            AppointmentStore appointments,
            Clock clock) {
        this.businesses = businesses;
        this.owners = owners;
        this.staffMembers = staffMembers;
        this.scheduleRevision = scheduleRevision;
        this.availability = availability;
        this.appointments = appointments;
        this.clock = clock;
    }

    Outcome execute(UUID userId, UUID businessId, RescheduleAppointmentCommand command) {
        Optional<BusinessScheduleContext> context = businesses.lockScheduleContextSanitized(businessId);
        if (context.isEmpty()) {
            return new Rejected(Rejection.ACCESS_DENIED);
        }
        if (owners.lockAndAuthorizeSanitized(userId, businessId) != Authorization.GRANTED) {
            return new Rejected(Rejection.ACCESS_DENIED);
        }
        LifecycleStatus status = context.get().status();
        if (status == LifecycleStatus.DRAFT) {
            return new Rejected(Rejection.BUSINESS_NOT_ACTIVE);
        }
        if (status == LifecycleStatus.SUSPENDED) {
            return new Rejected(Rejection.BUSINESS_SUSPENDED);
        }
        ZoneId zone = context.get().timezone();
        if (!validShape(command, zone)) {
            return new Rejected(Rejection.INVALID_INPUT);
        }

        Optional<Appointment> found = appointments.find(businessId, command.appointmentId());
        if (found.isEmpty()) {
            return new Rejected(Rejection.APPOINTMENT_NOT_FOUND);
        }
        Appointment current = found.get();
        // One clock read, truncated to the precision PostgreSQL stores, so the row returned by the
        // database equals what was decided on.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant requestedStart = command.start() == null ? current.startAt() : command.start();
        UUID requestedStaff = command.staffMemberId();

        AppointmentTransitions.Rejection refusal = AppointmentTransitions.rescheduleRefusal(
                current, command.expectedVersion(), requestedStart, requestedStaff, now);
        if (refusal != null) {
            return new Rejected(switch (refusal) {
                case STALE_VERSION -> Rejection.CONCURRENT_UPDATE;
                case NOT_CONFIRMED, ALREADY_STARTED -> Rejection.STATE_CONFLICT;
                case NO_CHANGE -> Rejection.INVALID_INPUT;
                default -> throw new IllegalStateException("Unexpected reschedule refusal");
            });
        }

        // The identifiers only name the selection; the locking statements decide the order of every
        // lock (ADR-0027 section 7).
        boolean reassigned = requestedStaff != null && !requestedStaff.equals(current.staffMemberId());
        UUID targetId = reassigned ? requestedStaff : current.staffMemberId();
        Optional<BookingStaffMember> target = lockTarget(businessId, current, targetId, reassigned);
        if (target.isEmpty()) {
            return new Rejected(Rejection.STAFF_UNAVAILABLE);
        }
        scheduleRevision.lockShared(businessId);

        Transition<RescheduleUpdate> transition = AppointmentTransitions.reschedule(
                current,
                command.expectedVersion(),
                requestedStart,
                reassigned ? new StaffTarget(target.get().id(), target.get().displayName()) : null,
                now);
        if (!(transition instanceof Applied<RescheduleUpdate> applied)) {
            AppointmentTransitions.Rejection late = ((AppointmentTransitions.Rejected<RescheduleUpdate>) transition)
                    .reason();
            if (late == AppointmentTransitions.Rejection.START_BEFORE_NOW) {
                return new Rejected(Rejection.SLOT_UNAVAILABLE);
            }
            throw new IllegalStateException("Unexpected late reschedule refusal");
        }

        LocalDate localDate = requestedStart.atZone(zone).toLocalDate();
        BusinessAvailability offered;
        try {
            offered = availability.calculateForBusiness(
                    businessId,
                    Duration.ofMinutes(current.durationMinutes()),
                    targetId,
                    localDate,
                    localDate,
                    now,
                    current.id());
        } catch (StaffMemberNotEligible notEligible) {
            return new Rejected(Rejection.STAFF_UNAVAILABLE);
        }
        if (offered.occupiedDuration().toMinutes() != current.durationMinutes()) {
            throw new IllegalStateException("Availability and the stored duration disagree");
        }
        Optional<AvailabilitySlot> slot = offered.slots().stream()
                .filter(candidate -> candidate.start().equals(requestedStart))
                .findFirst();
        if (slot.isEmpty() || !slot.get().staffMemberIds().contains(targetId)) {
            return new Rejected(Rejection.SLOT_UNAVAILABLE);
        }

        Optional<Appointment> updated = appointments.reschedule(applied.update());
        if (updated.isEmpty()) {
            // Inside the snapshot a concurrently changed row raises a serialization failure instead of
            // matching no row (ADR-0029 section 5); an empty result is an internal inconsistency.
            throw new IllegalStateException("The conditional update matched no row inside the snapshot");
        }
        // Read-your-write as the last statement of the body: a collaborator that swallowed a failed
        // statement must surface here and not as a false success (see the Phase 4 note).
        Optional<Appointment> stored = appointments.find(businessId, current.id());
        if (stored.isEmpty()
                || stored.get().version() != updated.get().version()
                || !stored.get().startAt().equals(requestedStart)
                || !stored.get().staffMemberId().equals(targetId)) {
            throw new IllegalStateException("The updated Appointment is not visible");
        }
        return new Rescheduled(rescheduled(updated.get()));
    }

    private Optional<BookingStaffMember> lockTarget(
            UUID businessId, Appointment current, UUID targetId, boolean reassigned) {
        if (!reassigned) {
            return staffMembers.lockActiveForBooking(businessId, targetId);
        }
        List<UUID> serviceIds = current.serviceLines().stream().map(AppointmentServiceLine::serviceId).toList();
        List<BookingStaffMember> locked = staffMembers.lockEligibleForBooking(businessId, serviceIds, targetId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        if (locked.size() != 1 || !locked.getFirst().id().equals(targetId)) {
            throw new IllegalStateException("Booking access locked an unrequested StaffMember");
        }
        return Optional.of(locked.getFirst());
    }

    /** The shape and domain bounds of the command; the field is deliberately never named. */
    private static boolean validShape(RescheduleAppointmentCommand command, ZoneId zone) {
        if (command == null
                || command.appointmentId() == null
                || command.expectedVersion() < 0
                || (command.start() == null && command.staffMemberId() == null)) {
            return false;
        }
        if (command.start() == null) {
            return true;
        }
        long second = command.start().getEpochSecond();
        if (second < -62_135_596_800L || second > 253_402_300_799L - 4L * 86_400L) {
            return false;
        }
        LocalDate date = command.start().atZone(zone).toLocalDate();
        return !date.isBefore(BusinessAppointmentCreationProcedure.FIRST_SUPPORTED_DATE)
                && !date.isAfter(BusinessAppointmentCreationProcedure.LAST_SUPPORTED_DATE);
    }

    private static RescheduledAppointment rescheduled(Appointment appointment) {
        List<CreatedService> lines = appointment.serviceLines().stream()
                .map(line -> new CreatedService(
                        line.position(), line.serviceName(), line.durationMinutes(), line.priceEur()))
                .toList();
        return new RescheduledAppointment(
                appointment.id(),
                appointment.publicReference(),
                appointment.status().name(),
                appointment.source().name(),
                appointment.startAt(),
                appointment.endAt(),
                appointment.timezone(),
                appointment.staffMemberId(),
                appointment.staffDisplayName(),
                appointment.customerId(),
                lines,
                appointment.durationMinutes(),
                appointment.priceEur(),
                appointment.internalNote(),
                appointment.version(),
                appointment.createdAt(),
                appointment.updatedAt());
    }
}
