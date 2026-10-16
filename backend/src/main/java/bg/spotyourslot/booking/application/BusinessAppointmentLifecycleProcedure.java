package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CancelAppointmentCommand;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.EditInternalNoteCommand;
import bg.spotyourslot.booking.application.BusinessAppointmentCreationProcedure.Rejection;
import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentTransitions;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Applied;
import bg.spotyourslot.booking.domain.AppointmentTransitions.CancelUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.NoteUpdate;
import bg.spotyourslot.booking.domain.AppointmentTransitions.Transition;
import bg.spotyourslot.booking.infrastructure.AppointmentStore;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The bodies of one owner cancellation and one owner internal-note edit (ADR-0028 sections 8 and 9,
 * ADR-0029 sections 3, 4 and the Phase 6 note). Each must run inside the single {@code READ
 * COMMITTED}, read-write transaction that {@link BusinessAppointmentLifecycleService} begins; every
 * collaborator joins it and none opens another. A body returns an outcome or throws and never
 * swallows a failure, so a transaction that threw is never continued.
 *
 * <p>Statement order, a prefix of the total lock order of ADR-0025 and ADR-0029 section 3 (levels L1,
 * L2, L7 only; no StaffMember, schedule revision, Service, or Customer row is touched): the Business
 * schedule context {@code FOR SHARE} (L1), the owner Membership {@code FOR SHARE} (L2), a plain
 * Appointment read, one clock read, and the conditional {@code UPDATE} carrying the expected version
 * (L7). A conditional {@code UPDATE} that matches no row is classified by one read in the same
 * transaction. The last statement of a body that wrote is a read-your-write.
 *
 * <p>Decisions in this precedence: authorization, lifecycle, the domain bounds of the command,
 * existence, version, state, then (for the note only) the note rule. The clock is read once, after
 * the Appointment exists.
 */
@Component
class BusinessAppointmentLifecycleProcedure {
    /** What one attempt produced. */
    sealed interface Outcome permits Changed, Unchanged, Rejected {
    }

    /** The facts that the verification read must find after a write. */
    sealed interface Written permits CancelWritten, NoteWritten {
        UUID appointmentId();

        long expectedVersion();
    }

    record CancelWritten(UUID appointmentId, long expectedVersion, Instant cancelledAt) implements Written {
    }

    record NoteWritten(UUID appointmentId, long expectedVersion, String note, Instant updatedAt)
            implements Written {
        @Override
        public String toString() {
            return "NoteWritten[redacted]";
        }
    }

    /** A conditional {@code UPDATE} matched and was read back inside the transaction. */
    record Changed(Written written) implements Outcome {
    }

    /** The submitted note equals the stored one: no {@code UPDATE} was issued. */
    record Unchanged(NoteWritten expected) implements Outcome {
    }

    /** Nothing was written; the transaction ends by rollback. */
    record Rejected(Rejection reason) implements Outcome {
    }

    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final AppointmentStore appointments;
    private final Clock clock;

    BusinessAppointmentLifecycleProcedure(
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners,
            AppointmentStore appointments,
            Clock clock) {
        this.businesses = businesses;
        this.owners = owners;
        this.appointments = appointments;
        this.clock = clock;
    }

    Outcome cancel(UUID userId, UUID businessId, CancelAppointmentCommand command) {
        Rejection gate = gate(userId, businessId);
        if (gate != null) {
            return new Rejected(gate);
        }
        if (command == null || command.appointmentId() == null || command.expectedVersion() < 0) {
            return new Rejected(Rejection.INVALID_INPUT);
        }
        Optional<Appointment> found = appointments.find(businessId, command.appointmentId());
        if (found.isEmpty()) {
            return new Rejected(Rejection.APPOINTMENT_NOT_FOUND);
        }
        Appointment current = found.get();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Transition<CancelUpdate> transition = AppointmentTransitions.cancel(current, command.expectedVersion(), now);
        if (!(transition instanceof Applied<CancelUpdate> applied)) {
            return new Rejected(refusal(
                    ((AppointmentTransitions.Rejected<CancelUpdate>) transition).reason()));
        }
        Optional<Appointment> updated = appointments.cancel(applied.update());
        if (updated.isEmpty()) {
            return new Rejected(classifyMissedUpdate(businessId, current.id(), command.expectedVersion()));
        }
        readBack(businessId, updated.get());
        return new Changed(new CancelWritten(current.id(), command.expectedVersion(), now));
    }

    Outcome editInternalNote(UUID userId, UUID businessId, EditInternalNoteCommand command) {
        Rejection gate = gate(userId, businessId);
        if (gate != null) {
            return new Rejected(gate);
        }
        if (command == null
                || command.appointmentId() == null
                || command.expectedVersion() < 0
                || command.internalNote() == null) {
            return new Rejected(Rejection.INVALID_INPUT);
        }
        Optional<Appointment> found = appointments.find(businessId, command.appointmentId());
        if (found.isEmpty()) {
            return new Rejected(Rejection.APPOINTMENT_NOT_FOUND);
        }
        Appointment current = found.get();
        if (current.version() != command.expectedVersion()) {
            // Judged before the note rule, so a stale form is never told its note is invalid.
            return new Rejected(Rejection.CONCURRENT_UPDATE);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Transition<NoteUpdate> transition = AppointmentTransitions.editInternalNote(
                current, command.expectedVersion(), command.internalNote(), now);
        if (!(transition instanceof Applied<NoteUpdate> applied)) {
            AppointmentTransitions.Rejection reason =
                    ((AppointmentTransitions.Rejected<NoteUpdate>) transition).reason();
            if (reason == AppointmentTransitions.Rejection.NO_CHANGE) {
                return new Unchanged(new NoteWritten(
                        current.id(), current.version(), current.internalNote(), current.updatedAt()));
            }
            return new Rejected(refusal(reason));
        }
        Optional<Appointment> updated = appointments.updateInternalNote(applied.update());
        if (updated.isEmpty()) {
            return new Rejected(classifyMissedUpdate(businessId, current.id(), command.expectedVersion()));
        }
        readBack(businessId, updated.get());
        return new Changed(new NoteWritten(
                current.id(), command.expectedVersion(), applied.update().internalNote(), now));
    }

    /** L1 and L2, then the lifecycle: the decisions that precede every domain validation. */
    private Rejection gate(UUID userId, UUID businessId) {
        Optional<BusinessScheduleContext> context = businesses.lockScheduleContextSanitized(businessId);
        if (context.isEmpty()) {
            return Rejection.ACCESS_DENIED;
        }
        if (owners.lockAndAuthorizeSanitized(userId, businessId) != Authorization.GRANTED) {
            return Rejection.ACCESS_DENIED;
        }
        LifecycleStatus status = context.get().status();
        if (status == LifecycleStatus.DRAFT) {
            return Rejection.BUSINESS_NOT_ACTIVE;
        }
        if (status == LifecycleStatus.SUSPENDED) {
            return Rejection.BUSINESS_SUSPENDED;
        }
        return null;
    }

    private static Rejection refusal(AppointmentTransitions.Rejection reason) {
        return switch (reason) {
            case STALE_VERSION -> Rejection.CONCURRENT_UPDATE;
            case NOT_CONFIRMED, ALREADY_STARTED -> Rejection.STATE_CONFLICT;
            case INVALID_NOTE -> Rejection.INVALID_INPUT;
            default -> throw new IllegalStateException("Unexpected lifecycle refusal");
        };
    }

    /**
     * The conditional {@code UPDATE} matched no row, which at {@code READ COMMITTED} means another
     * transaction changed the row after the plain read. One read in the same transaction decides, in
     * the order of ADR-0029 section 11: gone, a different version, otherwise the state forbids it.
     */
    private Rejection classifyMissedUpdate(UUID businessId, UUID appointmentId, long expectedVersion) {
        Optional<Appointment> now = appointments.find(businessId, appointmentId);
        if (now.isEmpty()) {
            return Rejection.APPOINTMENT_NOT_FOUND;
        }
        if (now.get().version() != expectedVersion) {
            return Rejection.CONCURRENT_UPDATE;
        }
        return Rejection.STATE_CONFLICT;
    }

    /**
     * Read-your-write as the last statement of a body that wrote: a collaborator that swallowed a
     * failed statement must surface here and not as a false success.
     */
    private void readBack(UUID businessId, Appointment updated) {
        Optional<Appointment> stored = appointments.find(businessId, updated.id());
        if (stored.isEmpty() || stored.get().version() != updated.version()) {
            throw new IllegalStateException("The updated Appointment is not visible");
        }
    }
}
