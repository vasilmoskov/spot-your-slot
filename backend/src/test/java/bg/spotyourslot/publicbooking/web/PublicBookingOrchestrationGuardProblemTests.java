package bg.spotyourslot.publicbooking.web;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.BookingOrchestrationFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/** The orchestration-guard failure is internal: the public problem is the fixed generic one and never carries its message. */
class PublicBookingOrchestrationGuardProblemTests {
    @Test
    void theGuardFailureIsTheGenericPublicInternalProblemWithoutItsMessage() {
        BookingOrchestrationFailure failure = new BookingOrchestrationFailure();
        ProblemDetail problem = new PublicBookingExceptionHandler().unexpected();

        assertThat(failure.getMessage()).isEqualTo("Booking orchestration requires no active caller transaction");
        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(problem.getDetail()).isEqualTo("Възникна неочаквана грешка.");
        assertThat(String.valueOf(problem)).doesNotContain(failure.getMessage()).doesNotContain("orchestration");
    }
}
