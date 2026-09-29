package bg.spotyourslot.scheduling.domain;

import java.time.Duration;

/**
 * Fixed MVP availability policy. Business-configurable horizon and minimum
 * notice are a documented follow-up and deliberately not modelled here.
 */
public final class AvailabilityPolicy {
    /** Candidate starts are aligned to the Business-local wall clock at this step. */
    public static final int SLOT_STEP_MINUTES = 15;

    /** The earliest candidate start is {@code now + MINIMUM_NOTICE}, inclusive. */
    public static final Duration MINIMUM_NOTICE = Duration.ofHours(2);

    /** Business-local calendar dates from today through {@code today + HORIZON_DAYS - 1}. */
    public static final int HORIZON_DAYS = 30;

    private AvailabilityPolicy() {
    }
}
