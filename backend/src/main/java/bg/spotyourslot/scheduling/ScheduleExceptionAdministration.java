package bg.spotyourslot.scheduling;

import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionAdministrationDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionWindow;
import java.time.LocalDate;
import java.util.UUID;

/** Business-owner administration of schedule exceptions. */
public interface ScheduleExceptionAdministration {
    ScheduleExceptionWindow list(
            AuthenticatedBusinessContext context, LocalDate from, LocalDate to);

    ScheduleExceptionAdministrationDetails get(
            AuthenticatedBusinessContext context, UUID exceptionId);

    ScheduleExceptionAdministrationDetails create(
            AuthenticatedBusinessContext context, CreateScheduleExceptionCommand command);

    ScheduleExceptionAdministrationDetails replace(
            AuthenticatedBusinessContext context,
            UUID exceptionId,
            ReplaceScheduleExceptionCommand command);

    void delete(AuthenticatedBusinessContext context, UUID exceptionId, Long expectedVersion);
}
