package bg.spotyourslot.scheduling.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bg.spotyourslot.scheduling.BusyIntervalSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NoBookingBusyIntervalSourceTests {
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-31T00:00:00Z");
    private final BusyIntervalSource source = new NoBookingBusyIntervalSource();

    @Test
    void reportsNoBusyTimeAsAnImmutableEmptyMap() {
        Map<UUID, List<BusyIntervalSource.BusyWindow>> windows = source.findBusyWindows(
                UUID.randomUUID(), List.of(UUID.randomUUID()), FROM, TO);

        assertThat(windows).isEmpty();
        assertThatThrownBy(() -> windows.put(UUID.randomUUID(), List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullArguments() {
        UUID business = UUID.randomUUID();

        assertThatThrownBy(() -> source.findBusyWindows(null, List.of(), FROM, TO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> source.findBusyWindows(business, null, FROM, TO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> source.findBusyWindows(business, List.of(), null, TO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> source.findBusyWindows(business, List.of(), FROM, null))
                .isInstanceOf(NullPointerException.class);
    }
}
