package bg.spotyourslot.scheduling.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.integration.PostgresIntegrationTest;
import bg.spotyourslot.scheduling.AvailabilityQuery;
import bg.spotyourslot.scheduling.BusyIntervalSource;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Delete this test together with {@link NoBookingBusyIntervalSource} when the
 * Booking module supplies the real {@link BusyIntervalSource}.
 */
class BusyIntervalSourceWiringIntegrationTests extends PostgresIntegrationTest {
    @Autowired Map<String, BusyIntervalSource> sources;
    @Autowired AvailabilityQuery availability;

    @Test
    void theTemporaryPlaceholderIsCurrentlyTheSingleImplementation() {
        assertThat(sources).hasSize(1);
        assertThat(sources.values().iterator().next())
                .isInstanceOf(NoBookingBusyIntervalSource.class);
    }

    @Test
    void thePublishedContractIsBackedByTheOrchestrator() {
        assertThat(availability.getClass().getName())
                .startsWith("bg.spotyourslot.scheduling.application.AvailabilityQueryService");
    }
}
