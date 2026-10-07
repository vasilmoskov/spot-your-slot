package bg.spotyourslot.booking.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The approved deterministic assignment for "Без предпочитание" (docs/product-spec.md, ADR-0023):
 * among the StaffMembers a freshly computed slot lists as free, the one with the fewest
 * non-cancelled (in the two-status model, {@code CONFIRMED}) Appointments on the slot's
 * Business-local date, then the earliest creation time, then the smallest identifier. The result
 * never depends on the order of the candidates.
 */
public final class StaffAssignmentPolicy {
    private static final Comparator<Candidate> ORDER = Comparator
            .comparingLong(Candidate::confirmedOnDate)
            .thenComparing(Candidate::createdAt)
            .thenComparing(Candidate::id);

    private StaffAssignmentPolicy() {
    }

    public static UUID choose(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return candidates.stream()
                .min(ORDER)
                .map(Candidate::id)
                .orElseThrow(() -> new IllegalArgumentException("At least one candidate is required"));
    }

    /**
     * @param confirmedOnDate the number of {@code CONFIRMED} Appointments of the StaffMember that
     *        start on the slot's Business-local date
     */
    public record Candidate(UUID id, Instant createdAt, long confirmedOnDate) {
        public Candidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(createdAt, "createdAt");
            if (confirmedOnDate < 0) {
                throw new IllegalArgumentException("The Appointment count must not be negative");
            }
        }
    }
}
