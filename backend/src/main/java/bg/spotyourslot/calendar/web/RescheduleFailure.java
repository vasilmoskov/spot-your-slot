package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationException;

/**
 * Carries a failure of the reschedule route to {@link BusinessAppointmentExceptionHandler}, so that
 * the route publishes wording that is true for changing the time or the StaffMember of an existing
 * Appointment and never speaks of creating or saving one. It is internal: it is never serialized, the
 * cause is never exposed, and only the cause's type selects the fixed public problem.
 */
final class RescheduleFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    RescheduleFailure(AppointmentAdministrationException failure) {
        super("Reschedule failed", failure, false, false);
    }

    AppointmentAdministrationException failure() {
        return (AppointmentAdministrationException) getCause();
    }
}
