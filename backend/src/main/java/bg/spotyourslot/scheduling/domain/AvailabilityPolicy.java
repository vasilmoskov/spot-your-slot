package bg.spotyourslot.scheduling.domain;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Availability policy constants. The public policy is fixed in the MVP: Business-configurable
 * horizon and minimum notice are a documented follow-up and deliberately not modelled here. The
 * Business-side policy (ADR-0029 section 1) differs only in its inputs: no minimum notice and an
 * explicit date window; the grid and every other rule are shared.
 */
public final class AvailabilityPolicy {
    /** Candidate starts are aligned to the Business-local wall clock at this step. */
    public static final int SLOT_STEP_MINUTES = 15;

    /** The public earliest candidate start is {@code now + MINIMUM_NOTICE}, inclusive. */
    public static final Duration MINIMUM_NOTICE = Duration.ofHours(2);

    /** Public Business-local calendar dates from today through {@code today + HORIZON_DAYS - 1}. */
    public static final int HORIZON_DAYS = 30;

    /** Business-side: the earliest candidate start is {@code now} itself, inclusive. */
    public static final Duration BUSINESS_MINIMUM_NOTICE = Duration.ZERO;

    /** Business-side: one availability read covers at most this many Business-local dates. */
    public static final int BUSINESS_MAX_DATES = 31;

    /** Business-side: the first supported Business-local date (a technical bound, not a horizon). */
    public static final LocalDate BUSINESS_FIRST_SUPPORTED_DATE = LocalDate.of(2000, 1, 1);

    /** Business-side: the last supported Business-local date (a technical bound, not a horizon). */
    public static final LocalDate BUSINESS_LAST_SUPPORTED_DATE = LocalDate.of(2100, 12, 31);

    private AvailabilityPolicy() {
    }
}
