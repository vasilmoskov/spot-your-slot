package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreateAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedService;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.ReviewedService;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.InvalidManualVisit;
import bg.spotyourslot.booking.domain.ManualVisitTerms;
import bg.spotyourslot.booking.domain.ManualVisitTerms.Claim;
import bg.spotyourslot.booking.domain.ManualVisitTerms.LockedService;
import bg.spotyourslot.booking.domain.ManualVisitTerms.Visit;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.catalog.ServiceBookingAccess.BookingService;
import bg.spotyourslot.customer.CustomerReferenceAccess;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The body of one owner-created Appointment attempt (ADR-0028 section 4, ADR-0029 section 4). It
 * must run inside the single repeatable-read, read-write transaction that
 * {@link BusinessAppointmentAdministrationService} begins for the attempt; every collaborator joins
 * that transaction and none opens another. It returns an outcome or throws and never swallows a
 * failure, so a transaction that threw is never continued.
 *
 * <p>Explicit locks, in the total order of ADR-0025 and ADR-0029 section 3: the Business schedule
 * context {@code FOR SHARE} (the first statement, which fixes the snapshot), the owner Membership
 * {@code FOR SHARE}, the eligible StaffMember {@code FOR SHARE}, the schedule revision {@code FOR
 * SHARE}, all selected Services {@code FOR SHARE}; then the Appointment and its lines are written
 * (the Customer foreign key takes its implicit {@code KEY SHARE} there). The Customer reference
 * check before the locks is a plain read and not a lock level.
 *
 * <p>Decisions in this precedence: authorization, lifecycle, domain bounds, Customer, then, with all
 * locks held, Service availability, StaffMember eligibility, reviewed terms, and last the slot.
 */
@Component
class BusinessAppointmentCreationProcedure {
    static final LocalDate FIRST_SUPPORTED_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate LAST_SUPPORTED_DATE = LocalDate.of(2100, 12, 31);

    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final CustomerReferenceAccess customers;
    private final StaffBookingAccess staffMembers;
    private final ScheduleRevisionGuard scheduleRevision;
    private final ServiceBookingAccess services;
    private final AvailabilityQuery availability;
    private final AppointmentStore appointments;
    private final PublicReferenceSource references;
    private final Clock clock;

    BusinessAppointmentCreationProcedure(
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners,
            CustomerReferenceAccess customers,
            StaffBookingAccess staffMembers,
            ScheduleRevisionGuard scheduleRevision,
            ServiceBookingAccess services,
            AvailabilityQuery availability,
            AppointmentStore appointments,
            PublicReferenceSource references,
            Clock clock) {
        this.businesses = businesses;
        this.owners = owners;
        this.customers = customers;
        this.staffMembers = staffMembers;
        this.scheduleRevision = scheduleRevision;
        this.services = services;
        this.availability = availability;
        this.appointments = appointments;
        this.references = references;
        this.clock = clock;
    }

    /** Why an attempt wrote nothing and was rolled back. */
    enum Rejection {
        ACCESS_DENIED,
        BUSINESS_NOT_ACTIVE,
        BUSINESS_SUSPENDED,
        INVALID_INPUT,
        CUSTOMER_NOT_FOUND,
        SERVICE_UNAVAILABLE,
        STAFF_UNAVAILABLE,
        REVIEW_CHANGED,
        SLOT_UNAVAILABLE
    }

    sealed interface Outcome permits Created, Rejected {
    }

    record Created(CreatedAppointment appointment) implements Outcome {
        @Override
        public String toString() {
            return "Created[redacted]";
        }
    }

    record Rejected(Rejection reason) implements Outcome {
    }

    /** Filled by a created attempt with the identifiers the verification read needs. */
    static final class CreatedIds {
        private UUID businessId;
        private UUID appointmentId;

        void record(UUID businessId, UUID appointmentId) {
            this.businessId = businessId;
            this.appointmentId = appointmentId;
        }

        UUID businessId() {
            return businessId;
        }

        UUID appointmentId() {
            return appointmentId;
        }
    }

    Outcome execute(UUID userId, UUID businessId, CreateAppointmentCommand command, CreatedIds createdIds) {
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

        List<Claim> claims;
        String internalNote;
        try {
            claims = claims(command, zone);
            internalNote = ManualVisitTerms.normalizeInternalNote(command.internalNote());
        } catch (InvalidManualVisit invalid) {
            return new Rejected(Rejection.INVALID_INPUT);
        }
        UUID staffMemberId = command.staffMemberId();
        Instant start = command.start();

        if (customers.find(businessId, command.customerId()).isEmpty()) {
            return new Rejected(Rejection.CUSTOMER_NOT_FOUND);
        }

        // The identifiers only name the selection; the order of every lock is decided by the locking
        // statements themselves (ascending uuid), never by a Java sort (ADR-0027 section 7).
        List<UUID> serviceIds = claims.stream().map(Claim::serviceId).toList();
        List<BookingStaffMember> lockedStaff = staffMembers.lockEligibleForBooking(
                businessId, serviceIds, staffMemberId);
        scheduleRevision.lockShared(businessId);
        List<BookingService> lockedServices = services.lockAllForBooking(businessId, serviceIds);

        List<LockedService> authoritative = new ArrayList<>(lockedServices.size());
        for (BookingService service : lockedServices) {
            authoritative.add(new LockedService(
                    service.id(), service.name(), service.durationMinutes(), service.price(), service.active()));
        }
        if (!ManualVisitTerms.allAvailable(claims, authoritative)) {
            return new Rejected(Rejection.SERVICE_UNAVAILABLE);
        }
        if (lockedStaff.isEmpty()) {
            return new Rejected(Rejection.STAFF_UNAVAILABLE);
        }
        BookingStaffMember staff = lockedStaff.getFirst();
        if (lockedStaff.size() != 1 || !staff.id().equals(staffMemberId)) {
            throw new IllegalStateException("Booking access locked an unrequested StaffMember");
        }
        if (!ManualVisitTerms.termsMatch(claims, authoritative)) {
            return new Rejected(Rejection.REVIEW_CHANGED);
        }

        Visit visit = ManualVisitTerms.plan(authoritative);
        // Truncated to the precision PostgreSQL stores, so the returned row equals what was decided on.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (start.isBefore(now)) {
            return new Rejected(Rejection.SLOT_UNAVAILABLE);
        }
        LocalDate localDate = start.atZone(zone).toLocalDate();
        BusinessAvailability offered;
        try {
            offered = availability.calculateForBusiness(
                    businessId,
                    Duration.ofMinutes(visit.totalMinutes()),
                    staffMemberId,
                    localDate,
                    localDate,
                    now,
                    null);
        } catch (StaffMemberNotEligible notEligible) {
            return new Rejected(Rejection.STAFF_UNAVAILABLE);
        }
        if (offered.occupiedDuration().toMinutes() != visit.totalMinutes()) {
            throw new IllegalStateException("Availability and the locked Services disagree on the duration");
        }
        Optional<AvailabilitySlot> slot = offered.slots().stream()
                .filter(candidate -> candidate.start().equals(start))
                .findFirst();
        if (slot.isEmpty() || !slot.get().staffMemberIds().contains(staffMemberId)) {
            return new Rejected(Rejection.SLOT_UNAVAILABLE);
        }

        LockedService headline = authoritative.getFirst();
        Appointment created = appointments.insert(new NewAppointment(
                UUID.randomUUID(),
                businessId,
                command.customerId(),
                headline.id(),
                staff.id(),
                AppointmentSource.MANUAL,
                start,
                visit.totalMinutes(),
                visit.totalPrice(),
                zone.getId(),
                headline.name(),
                staff.displayName(),
                null,
                references.next(),
                null,
                now,
                visit.lines(),
                internalNote));
        // Read-your-write as the last statement of the body. PostgreSQL answers COMMIT of an aborted
        // transaction with a silent ROLLBACK that the driver does not report, so a collaborator that
        // swallowed a failed statement must surface here and not as a false success.
        if (appointments.find(businessId, created.id()).isEmpty()) {
            throw new IllegalStateException("The inserted Appointment is not visible");
        }
        createdIds.record(businessId, created.id());
        return new Created(created(created));
    }

    /** The claims, or an {@link InvalidManualVisit} when any domain bound of the request is violated. */
    private static List<Claim> claims(CreateAppointmentCommand command, ZoneId zone) {
        if (command == null
                || command.customerId() == null
                || command.staffMemberId() == null
                || command.start() == null
                || command.services() == null) {
            throw new InvalidManualVisit();
        }
        List<Claim> claims = new ArrayList<>(command.services().size());
        for (ReviewedService service : command.services()) {
            if (service == null || service.serviceId() == null || service.serviceName() == null) {
                throw new InvalidManualVisit();
            }
            claims.add(new Claim(
                    service.serviceId(), service.serviceName(), service.durationMinutes(), service.priceCents()));
        }
        ManualVisitTerms.requireValidClaims(claims);
        long second = command.start().getEpochSecond();
        if (second < -62_135_596_800L || second > 253_402_300_799L - 4L * 86_400L) {
            throw new InvalidManualVisit();
        }
        LocalDate date = command.start().atZone(zone).toLocalDate();
        if (date.isBefore(FIRST_SUPPORTED_DATE) || date.isAfter(LAST_SUPPORTED_DATE)) {
            throw new InvalidManualVisit();
        }
        return claims;
    }

    private static CreatedAppointment created(Appointment appointment) {
        List<CreatedService> lines = appointment.serviceLines().stream()
                .map(line -> new CreatedService(
                        line.position(), line.serviceName(), line.durationMinutes(), line.priceEur()))
                .toList();
        return new CreatedAppointment(
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
                appointment.createdAt());
    }
}
