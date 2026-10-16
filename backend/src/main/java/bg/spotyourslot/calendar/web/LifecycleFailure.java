package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationException;

/**
 * Carries a failure of the cancellation or internal-note route to
 * {@link BusinessAppointmentExceptionHandler}, so that each route publishes wording that is true for
 * its own operation. It is internal: never serialized, the cause is never exposed, and only the
 * cause's type and the operation select the fixed public problem.
 */
final class LifecycleFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The route that failed. */
    enum Operation {
        CANCEL,
        INTERNAL_NOTE
    }

    private final Operation operation;

    LifecycleFailure(Operation operation, AppointmentAdministrationException failure) {
        super("Appointment lifecycle operation failed", failure, false, false);
        this.operation = operation;
    }

    Operation operation() {
        return operation;
    }

    AppointmentAdministrationException failure() {
        return (AppointmentAdministrationException) getCause();
    }
}
