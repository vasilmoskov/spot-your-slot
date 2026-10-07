package bg.spotyourslot.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.booking.domain.StaffAssignmentPolicy.Candidate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaffAssignmentPolicyTests {
    private static final UUID LOW = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID MID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID HIGH = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final Instant EARLY = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void theFewestAppointmentsOnTheDateWinsEvenOverAnEarlierCreation() {
        assertThat(StaffAssignmentPolicy.choose(List.of(
                new Candidate(LOW, EARLY, 3),
                new Candidate(HIGH, LATE, 1),
                new Candidate(MID, EARLY, 2)))).isEqualTo(HIGH);
    }

    @Test
    void anEqualCountIsDecidedByTheEarlierCreationTime() {
        assertThat(StaffAssignmentPolicy.choose(List.of(
                new Candidate(LOW, LATE, 2), new Candidate(HIGH, EARLY, 2)))).isEqualTo(HIGH);
    }

    @Test
    void anEqualCountAndCreationTimeIsDecidedByTheSmallerIdentifier() {
        assertThat(StaffAssignmentPolicy.choose(List.of(
                new Candidate(HIGH, EARLY, 0), new Candidate(LOW, EARLY, 0), new Candidate(MID, EARLY, 0))))
                .isEqualTo(LOW);
    }

    @Test
    void theResultNeverDependsOnTheOrderOfTheCandidates() {
        List<Candidate> candidates = new ArrayList<>(List.of(
                new Candidate(LOW, LATE, 1), new Candidate(MID, EARLY, 1), new Candidate(HIGH, EARLY, 1)));
        UUID expected = StaffAssignmentPolicy.choose(candidates);
        Random random = new Random(42);
        for (int round = 0; round < 50; round++) {
            Collections.shuffle(candidates, random);
            assertThat(StaffAssignmentPolicy.choose(candidates)).isEqualTo(expected);
        }
        assertThat(expected).isEqualTo(MID);
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> StaffAssignmentPolicy.choose(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Candidate(LOW, EARLY, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
