package bg.spotyourslot.business.domain;

import java.time.ZoneId;
import java.util.Set;

public record BusinessTimezone(String value) {
    public static final String DEFAULT_ZONE_ID = "Europe/Sofia";

    private static final Set<String> NAMED_IANA_ZONE_IDS = ZoneId.getAvailableZoneIds();

    public BusinessTimezone {
        if (value == null || !NAMED_IANA_ZONE_IDS.contains(value)) {
            throw new IllegalArgumentException("Business timezone must be a named IANA timezone");
        }
    }

    public static BusinessTimezone defaultTimezone() {
        return new BusinessTimezone(DEFAULT_ZONE_ID);
    }

    public ZoneId toZoneId() {
        return ZoneId.of(value);
    }
}
