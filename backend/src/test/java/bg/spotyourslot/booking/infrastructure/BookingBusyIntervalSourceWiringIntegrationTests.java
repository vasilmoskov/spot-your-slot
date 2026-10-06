package bg.spotyourslot.booking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

/** Startup wiring: exactly one busy-interval source is wired, and it is the Booking-owned one. */
class BookingBusyIntervalSourceWiringIntegrationTests extends PostgresIntegrationTest {
    @Autowired
    Map<String, BusyIntervalSource> sources;

    @Autowired
    AvailabilityQuery availability;

    @Test
    void exactlyOneImplementationIsWiredAndItIsTheBookingOwnedOne() {
        assertThat(sources).hasSize(1);
        assertThat(AopUtils.getTargetClass(sources.values().iterator().next()))
                .isEqualTo(BookingBusyIntervalSource.class);
    }

    @Test
    void thePublishedAvailabilityContractIsBackedByTheOrchestrator() {
        assertThat(AopUtils.getTargetClass(availability).getName())
                .isEqualTo("bg.spotyourslot.scheduling.application.AvailabilityQueryService");
    }
}
