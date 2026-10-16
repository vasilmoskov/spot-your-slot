package bg.spotyourslot.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * The pure state-transition rules of Business-side Appointment administration (ADR-0028 sections 3,
 * 6 to 9; ADR-0029 section 4). Each rule reads the current {@link Appointment}, the caller's
 * {@code expectedVersion}, and the single clock instant of the attempt, and returns either the exact
 * update to persist or the reason it is refused. It reads no clock, repository, or security
 * context, performs no availability or eligibility check (those need locks and belong to the
 * orchestration), and never throws for a refusal.
 *
 * <p>Refusals are checked in the order of ADR-0029 section 11: version, then state. A stale version
 * is always reported before a state conflict. What never changes in any transition: the Customer,
 * the Services and lines, the duration and price, the {@code source}, the public reference, the
 * guest note and idempotency columns, and the creation time. The instant is truncated to
 * microseconds, the precision PostgreSQL stores, so the value returned by the database equals the
 * value the rule produced.
 */
public final class AppointmentTransitions {
    private AppointmentTransitions() {
    }

    /** Why a transition is refused. */
    public enum Rejection {
        /** The expected version is not the current one. */
        STALE_VERSION,
        /** The Appointment is not {@code CONFIRMED}. */
        NOT_CONFIRMED,
        /** The Appointment starts at or before the attempt instant. */
        ALREADY_STARTED,
        /** The request changes nothing. */
        NO_CHANGE,
        /** The requested start is before the attempt instant. */
        START_BEFORE_NOW,
        /** The submitted note violates the plain-text note rule. */
        INVALID_NOTE
    }

    /** The result of a rule: the update to persist, or the reason for refusing it. */
    public sealed interface Transition<T> permits Applied, Rejected {
    }

    public record Applied<T>(T update) implements Transition<T> {
        public Applied {
            Objects.requireNonNull(update, "update");
        }
    }

    public record Rejected<T>(Rejection reason) implements Transition<T> {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The StaffMember a reassignment moves the Appointment to. The display name is the canonical
     * name read from the locked target row; it becomes the new snapshot.
     */
    public record StaffTarget(UUID staffMemberId, String displayName) {
        public StaffTarget {
            Objects.requireNonNull(staffMemberId, "staffMemberId");
            Objects.requireNonNull(displayName, "displayName");
            AppointmentRules.requireSnapshotName(displayName);
        }

        @Override
        public String toString() {
            return "StaffTarget[redacted]";
        }
    }

    /**
     * The persisted change of a reschedule and/or reassignment. The expected version, StaffMember,
     * and start are the optimistic predicates of the conditional {@code UPDATE}; the rest is what it
     * writes. {@code version + 1} is applied by the statement itself.
     */
    public record RescheduleUpdate(
            UUID businessId,
            UUID appointmentId,
            long expectedVersion,
            UUID expectedStaffMemberId,
            Instant expectedStartAt,
            Instant startAt,
            Instant endAt,
            Instant occupiedUntil,
            UUID staffMemberId,
            String staffDisplayName,
            Instant updatedAt) {
        public RescheduleUpdate {
            Objects.requireNonNull(businessId, "businessId");
            Objects.requireNonNull(appointmentId, "appointmentId");
            Objects.requireNonNull(expectedStaffMemberId, "expectedStaffMemberId");
            Objects.requireNonNull(expectedStartAt, "expectedStartAt");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            Objects.requireNonNull(occupiedUntil, "occupiedUntil");
            Objects.requireNonNull(staffMemberId, "staffMemberId");
            Objects.requireNonNull(staffDisplayName, "staffDisplayName");
            Objects.requireNonNull(updatedAt, "updatedAt");
            requireExpectedVersion(expectedVersion);
        }

        @Override
        public String toString() {
            return "RescheduleUpdate[redacted]";
        }
    }

    /** The persisted change of a Business cancellation: {@code CANCELLED} by {@code BUSINESS}. */
    public record CancelUpdate(
            UUID businessId, UUID appointmentId, long expectedVersion, Instant cancelledAt) {
        public CancelUpdate {
            Objects.requireNonNull(businessId, "businessId");
            Objects.requireNonNull(appointmentId, "appointmentId");
            Objects.requireNonNull(cancelledAt, "cancelledAt");
            requireExpectedVersion(expectedVersion);
        }

        @Override
        public String toString() {
            return "CancelUpdate[redacted]";
        }
    }

    /** The persisted change of the internal note; {@code internalNote} is {@code null} to clear it. */
    public record NoteUpdate(
            UUID businessId,
            UUID appointmentId,
            long expectedVersion,
            String internalNote,
            Instant updatedAt) {
        public NoteUpdate {
            Objects.requireNonNull(businessId, "businessId");
            Objects.requireNonNull(appointmentId, "appointmentId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            requireExpectedVersion(expectedVersion);
            AppointmentRules.requireNote(internalNote);
        }

        @Override
        public String toString() {
            return "NoteUpdate[redacted]";
        }
    }

    /**
     * Moves a {@code CONFIRMED}, not yet started Appointment to a new start, to another
     * StaffMember, or both. The duration is the stored one. A {@code null} target, or a target equal
     * to the current StaffMember, keeps the StaffMember and its snapshot.
     *
     * @throws IllegalArgumentException for a caller error: a start outside the supported instant
     *         range, or an attempt instant before the Appointment's creation
     */
    public static Transition<RescheduleUpdate> reschedule(
            Appointment current,
            long expectedVersion,
            Instant requestedStart,
            StaffTarget target,
            Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(requestedStart, "requestedStart");
        Instant instant = attemptInstant(current, now);
        AppointmentRules.requireInstant(requestedStart);
        Instant requestedEnd = requestedStart.plus(Duration.ofMinutes(current.durationMinutes()));
        AppointmentRules.requireInstant(requestedEnd);

        Rejection refusal = rescheduleRefusal(
                current, expectedVersion, requestedStart, target == null ? null : target.staffMemberId(), instant);
        if (refusal != null) {
            return new Rejected<>(refusal);
        }
        boolean reassigned = target != null && !target.staffMemberId().equals(current.staffMemberId());
        if (requestedStart.isBefore(instant)) {
            return new Rejected<>(Rejection.START_BEFORE_NOW);
        }
        return new Applied<>(new RescheduleUpdate(
                current.businessId(),
                current.id(),
                expectedVersion,
                current.staffMemberId(),
                current.startAt(),
                requestedStart,
                requestedEnd,
                requestedEnd,
                reassigned ? target.staffMemberId() : current.staffMemberId(),
                reassigned ? target.displayName() : current.staffDisplayName(),
                instant));
    }

    /**
     * The refusals of a reschedule or reassignment that depend only on the Appointment as read, in the
     * order of ADR-0029 section 11: a stale version, a state that is not {@code CONFIRMED}, an
     * Appointment that has started, and a request that changes nothing. It is the check the
     * orchestration makes <em>before</em> it takes any StaffMember lock, so it needs the target's
     * identifier only. The requested start is judged against the attempt instant later, after the
     * eligibility decisions ({@link Rejection#START_BEFORE_NOW}), exactly as {@link #reschedule}
     * does. Returns {@code null} when none applies.
     *
     * @param targetStaffMemberId the requested StaffMember, or {@code null} to keep the current one
     */
    public static Rejection rescheduleRefusal(
            Appointment current,
            long expectedVersion,
            Instant requestedStart,
            UUID targetStaffMemberId,
            Instant now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(requestedStart, "requestedStart");
        Instant instant = attemptInstant(current, now);
        Rejection refusal = stateRefusal(current, expectedVersion, instant);
        if (refusal != null) {
            return refusal;
        }
        boolean reassigned = targetStaffMemberId != null && !targetStaffMemberId.equals(current.staffMemberId());
        if (!reassigned && requestedStart.equals(current.startAt())) {
            return Rejection.NO_CHANGE;
        }
        return null;
    }

    /**
     * Cancels a {@code CONFIRMED}, not yet started Appointment as the Business. The cancellation
     * time and the update time are the attempt instant.
     */
    public static Transition<CancelUpdate> cancel(
            Appointment current, long expectedVersion, Instant now) {
        Objects.requireNonNull(current, "current");
        Instant instant = attemptInstant(current, now);
        Rejection refusal = stateRefusal(current, expectedVersion, instant);
        if (refusal != null) {
            return new Rejected<>(refusal);
        }
        return new Applied<>(new CancelUpdate(current.businessId(), current.id(), expectedVersion, instant));
    }

    /**
     * Replaces the internal note on any Appointment, whatever its status or start. The submitted
     * text is normalized with the shared note policy (a blank note clears it); a note equal to the
     * current one changes nothing.
     */
    public static Transition<NoteUpdate> editInternalNote(
            Appointment current, long expectedVersion, String submittedNote, Instant now) {
        Objects.requireNonNull(current, "current");
        Instant instant = attemptInstant(current, now);
        String note = NoteText.normalize(submittedNote);
        if (note != null && !NoteText.isValid(note)) {
            return new Rejected<>(Rejection.INVALID_NOTE);
        }
        if (current.version() != expectedVersion) {
            return new Rejected<>(Rejection.STALE_VERSION);
        }
        if (Objects.equals(note, current.internalNote())) {
            return new Rejected<>(Rejection.NO_CHANGE);
        }
        return new Applied<>(new NoteUpdate(current.businessId(), current.id(), expectedVersion, note, instant));
    }

    private static Rejection stateRefusal(Appointment current, long expectedVersion, Instant now) {
        if (current.version() != expectedVersion) {
            return Rejection.STALE_VERSION;
        }
        if (current.status() != AppointmentStatus.CONFIRMED) {
            return Rejection.NOT_CONFIRMED;
        }
        if (!current.startAt().isAfter(now)) {
            return Rejection.ALREADY_STARTED;
        }
        return null;
    }

    private static Instant attemptInstant(Appointment current, Instant now) {
        Objects.requireNonNull(now, "now");
        Instant instant = now.truncatedTo(ChronoUnit.MICROS);
        AppointmentRules.requireInstant(instant);
        if (instant.isBefore(current.createdAt())) {
            throw new IllegalArgumentException("The transition instant precedes the Appointment creation");
        }
        return instant;
    }

    private static void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Appointment version must not be negative");
        }
    }
}
