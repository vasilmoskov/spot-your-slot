package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.BookedAppointment;
import bg.spotyourslot.booking.BookingField;
import bg.spotyourslot.booking.BookingResult;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.booking.domain.NormalizedBookingRequest;
import bg.spotyourslot.booking.domain.StaffAssignmentPolicy;
import bg.spotyourslot.booking.domain.StaffAssignmentPolicy.Candidate;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessBookingAccess;
import bg.spotyourslot.business.BusinessBookingAccess.BookingBusiness;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.catalog.ServiceBookingAccess;
import bg.spotyourslot.catalog.ServiceBookingAccess.BookingService;
import bg.spotyourslot.customer.CustomerIdentification;
import bg.spotyourslot.customer.CustomerIdentity;
import bg.spotyourslot.customer.CustomerMatchOutcome;
import bg.spotyourslot.customer.CustomerMatchOutcome.CreatedCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.ExistingCustomer;
import bg.spotyourslot.customer.CustomerMatchOutcome.IdentityConflict;
import bg.spotyourslot.customer.CustomerMatchOutcome.InvalidIdentity;
import bg.spotyourslot.customer.IdentityField;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.BusinessNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.ServiceNotBookable;
import bg.spotyourslot.scheduling.AvailabilityApplicationException.StaffMemberNotEligible;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySlot;
import bg.spotyourslot.scheduling.AvailabilityRecords.AvailabilitySnapshot;
import bg.spotyourslot.workforce.StaffBookingAccess;
import bg.spotyourslot.workforce.StaffBookingAccess.BookingStaffMember;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The body of one booking attempt (ADR-0023). It must run inside the single repeatable-read,
 * read-write transaction that {@link GuestBookingService} begins for the attempt; every collaborator
 * joins that transaction ({@code MANDATORY}) and none opens another. The method returns an outcome
 * or throws; it never swallows a failure, so a transaction that threw is never continued.
 *
 * <p>Order (a total lock order shared with every schedule and administration mutation, ADR-0025):
 * <ol>
 *   <li>lock the Business {@code FOR SHARE} by slug at any lifecycle status; this is the first
 *       statement and fixes the snapshot;
 *   <li>replay lookup by (Business, attempt hash): a replay holds only that Business lock, takes no
 *       other lock, calls neither availability nor Customer, and writes nothing;
 *   <li>otherwise require an {@code ACTIVE} Business; lock every qualifying StaffMember ({@code
 *       FOR SHARE}, identifier order; guest booking has no Membership lock); lock the Business
 *       schedule revision {@code FOR SHARE}; lock the Service {@code FOR SHARE} and verify it is
 *       active;
 *   <li>only now compute availability, so the guard is held before any availability validation;
 *       the start must be one of the freshly offered slots;
 *   <li>choose the requested or the deterministically assigned StaffMember, resolve the Customer
 *       conservatively, and insert the Appointment.
 * </ol>
 */
@Component
class BookingAttemptProcedure {
    private final BusinessBookingAccess businesses;
    private final StaffBookingAccess staffMembers;
    private final ScheduleRevisionGuard scheduleRevision;
    private final ServiceBookingAccess services;
    private final AvailabilityQuery availability;
    private final CustomerIdentification customers;
    private final AppointmentStore appointments;
    private final RequestFingerprinter fingerprinter;
    private final PublicReferenceSource references;
    private final BookingDiagnostics diagnostics;

    BookingAttemptProcedure(
            BusinessBookingAccess businesses,
            StaffBookingAccess staffMembers,
            ScheduleRevisionGuard scheduleRevision,
            ServiceBookingAccess services,
            AvailabilityQuery availability,
            CustomerIdentification customers,
            AppointmentStore appointments,
            RequestFingerprinter fingerprinter,
            PublicReferenceSource references,
            BookingDiagnostics diagnostics) {
        this.businesses = businesses;
        this.staffMembers = staffMembers;
        this.scheduleRevision = scheduleRevision;
        this.services = services;
        this.availability = availability;
        this.customers = customers;
        this.appointments = appointments;
        this.fingerprinter = fingerprinter;
        this.references = references;
        this.diagnostics = diagnostics;
    }

    /**
     * Filled by a {@code Created} attempt with the identifiers that the verification read after the
     * commit needs; left empty by every other result.
     */
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

    BookingResult execute(String businessSlug, NormalizedBookingRequest request, CreatedIds createdIds) {
        return run(businessSlug, request, createdIds);
    }

    private BookingResult run(String businessSlug, NormalizedBookingRequest request, CreatedIds createdIds) {
        Optional<BookingBusiness> locked = businesses.lockBySlug(businessSlug);
        if (locked.isEmpty()) {
            return new BookingResult.BusinessUnavailable();
        }
        BookingBusiness business = locked.get();
        UUID businessId = business.businessId();

        Optional<Appointment> earlier = appointments.findByAttemptHash(
                businessId, request.attemptId().hash());
        if (earlier.isPresent()) {
            return replay(businessId, request, earlier.get());
        }
        if (business.status() != LifecycleStatus.ACTIVE) {
            return new BookingResult.BusinessUnavailable();
        }
        return book(businessId, request, createdIds);
    }

    private BookingResult replay(UUID businessId, NormalizedBookingRequest request, Appointment stored) {
        BookingAttempt attempt = stored.attempt();
        if (attempt == null) {
            // Found by attempt hash, so the idempotency columns exist; a row without them is corrupt.
            throw new IllegalStateException("Replayed Appointment has no booking attempt");
        }
        try {
            if (fingerprinter.matches(businessId, request, attempt)) {
                return new BookingResult.Replayed(booked(stored));
            }
            return new BookingResult.AttemptMismatch();
        } catch (RequestFingerprinter.UnverifiableFingerprint unverifiable) {
            // The Appointment exists but cannot be verified: a technical failure, never a mismatch.
            diagnostics.event("replay-unverifiable", unverifiable);
            return new BookingResult.OutcomeUncertain();
        }
    }

    private BookingResult book(UUID businessId, NormalizedBookingRequest request, CreatedIds createdIds) {
        UUID requestedStaffId = request.requestedStaffMemberId();
        List<BookingStaffMember> lockedStaff = staffMembers.lockEligibleForBooking(
                businessId, request.serviceId(), requestedStaffId);
        scheduleRevision.lockShared(businessId);
        Optional<BookingService> lockedService = services.lockForBooking(businessId, request.serviceId());
        if (lockedService.isEmpty() || !lockedService.get().active()) {
            return new BookingResult.ServiceUnavailable();
        }
        BookingService service = lockedService.get();
        if (lockedStaff.isEmpty()) {
            return requestedStaffId != null
                    ? new BookingResult.StaffMemberUnavailable()
                    : new BookingResult.SlotUnavailable();
        }

        AvailabilitySnapshot snapshot;
        try {
            snapshot = availability.calculate(businessId, request.serviceId(), requestedStaffId);
        } catch (BusinessNotBookable notBookable) {
            return new BookingResult.BusinessUnavailable();
        } catch (ServiceNotBookable notBookable) {
            return new BookingResult.ServiceUnavailable();
        } catch (StaffMemberNotEligible notEligible) {
            return new BookingResult.StaffMemberUnavailable();
        }
        if (!snapshot.occupiedDuration().equals(Duration.ofMinutes(service.durationMinutes()))) {
            throw new IllegalStateException("Availability and the locked Service disagree on the duration");
        }
        Optional<AvailabilitySlot> slot = snapshot.slots().stream()
                .filter(offered -> offered.start().equals(request.start()))
                .findFirst();
        if (slot.isEmpty()) {
            return new BookingResult.SlotUnavailable();
        }

        Map<UUID, BookingStaffMember> lockedById = new HashMap<>();
        lockedStaff.forEach(member -> lockedById.put(member.id(), member));
        Optional<BookingStaffMember> assigned = assign(
                businessId, request, slot.get(), snapshot.timezone(), lockedById);
        if (assigned.isEmpty()) {
            return new BookingResult.SlotUnavailable();
        }

        CustomerMatchOutcome customer = customers.findOrCreate(
                businessId,
                new CustomerIdentity(
                        request.customerName(), request.customerPhone(), request.customerEmail()));
        UUID customerId;
        switch (customer) {
            case ExistingCustomer existing -> customerId = existing.customerId();
            case CreatedCustomer created -> customerId = created.customerId();
            case InvalidIdentity invalid -> {
                return new BookingResult.InvalidRequest(fields(invalid.fields()));
            }
            case IdentityConflict conflict -> {
                return new BookingResult.IdentityConflict();
            }
        }

        BookingAttempt attempt = fingerprinter.newAttempt(businessId, request);
        Instant createdAt = snapshot.calculatedAt();
        Appointment created = appointments.insert(new NewAppointment(
                UUID.randomUUID(),
                businessId,
                customerId,
                service.id(),
                assigned.get().id(),
                AppointmentSource.ONLINE,
                request.start(),
                service.durationMinutes(),
                service.price(),
                snapshot.timezone().getId(),
                service.name(),
                assigned.get().displayName(),
                request.note(),
                references.next(),
                attempt,
                createdAt));
        // Read-your-write as the last statement of the body. PostgreSQL answers COMMIT of an aborted
        // transaction with a silent ROLLBACK that the driver does not report, so a collaborator that
        // swallowed a failed statement must surface here (SQLState 25P02) and not as a false success.
        if (appointments.find(businessId, created.id()).isEmpty()) {
            throw new IllegalStateException("The inserted Appointment is not visible");
        }
        createdIds.record(businessId, created.id());
        return new BookingResult.Created(booked(created));
    }

    /**
     * The requested StaffMember must be among the slot's free members; for "no preference" the
     * deterministic rule picks among the slot's free members. Every candidate was locked above from
     * the same snapshot, so a candidate that was not locked is an internal inconsistency.
     */
    private Optional<BookingStaffMember> assign(
            UUID businessId,
            NormalizedBookingRequest request,
            AvailabilitySlot slot,
            ZoneId timezone,
            Map<UUID, BookingStaffMember> lockedById) {
        List<UUID> free = slot.staffMemberIds();
        for (UUID id : free) {
            if (!lockedById.containsKey(id)) {
                throw new IllegalStateException("Availability offered a StaffMember that was not locked");
            }
        }
        UUID requested = request.requestedStaffMemberId();
        if (requested != null) {
            return free.contains(requested) ? Optional.of(lockedById.get(requested)) : Optional.empty();
        }
        if (free.size() == 1) {
            return Optional.of(lockedById.get(free.get(0)));
        }
        LocalDate localDate = slot.start().atZone(timezone).toLocalDate();
        Instant dayStart = localDate.atStartOfDay(timezone).toInstant();
        Instant nextDayStart = localDate.plusDays(1).atStartOfDay(timezone).toInstant();
        Map<UUID, Long> counts = appointments.countConfirmedStartingBetween(
                businessId, free, dayStart, nextDayStart);
        List<Candidate> candidates = new ArrayList<>(free.size());
        for (UUID id : free) {
            candidates.add(new Candidate(id, lockedById.get(id).createdAt(), counts.getOrDefault(id, 0L)));
        }
        return Optional.of(lockedById.get(StaffAssignmentPolicy.choose(candidates)));
    }

    private static BookedAppointment booked(Appointment appointment) {
        return new BookedAppointment(
                appointment.publicReference(),
                BookedAppointment.Status.valueOf(appointment.status().name()),
                appointment.serviceName(),
                appointment.durationMinutes(),
                appointment.priceEur(),
                appointment.staffDisplayName(),
                appointment.startAt(),
                appointment.endAt(),
                appointment.timezone());
    }

    private static Set<BookingField> fields(Set<IdentityField> identityFields) {
        Set<BookingField> mapped = EnumSet.noneOf(BookingField.class);
        for (IdentityField field : identityFields) {
            mapped.add(switch (field) {
                case DISPLAY_NAME -> BookingField.DISPLAY_NAME;
                case PHONE -> BookingField.PHONE;
                case EMAIL -> BookingField.EMAIL;
                case CONTACT -> BookingField.CONTACT;
            });
        }
        return mapped;
    }
}
