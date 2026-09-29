package bg.spotyourslot.scheduling.domain;

/**
 * The four explicit kinds of stored schedule exception (ADR-0013, ADR-0014).
 */
public enum ScheduleExceptionKind {
    /** Business-scoped block: full-day inclusive date range, or periods on one date. */
    BUSINESS_CLOSURE(false, true),
    /** StaffMember-scoped block: full-day inclusive date range, or periods on one date. */
    STAFF_TIME_OFF(true, true),
    /** StaffMember-scoped, one date; replaces the recurring periods, zero periods allowed. */
    WORKING_DAY_OVERRIDE(true, false),
    /** StaffMember-scoped, one date; one or more periods augment the base. */
    ADDITIONAL_WORKING_PERIODS(true, false);

    private final boolean staffScoped;
    private final boolean block;

    ScheduleExceptionKind(boolean staffScoped, boolean block) {
        this.staffScoped = staffScoped;
        this.block = block;
    }

    public boolean isStaffScoped() {
        return staffScoped;
    }

    /** Whether the kind subtracts availability; the working kinds add or replace it. */
    public boolean isBlock() {
        return block;
    }
}
