package bg.spotyourslot.scheduling;

import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ScheduleExceptionRecords {
    private ScheduleExceptionRecords() {
    }

    public record ExceptionPeriod(LocalTime startTime, LocalTime endTime) {
    }

    /** Fields are nullable so the application validator, not the caller, rejects omissions. */
    public record CreateScheduleExceptionCommand(
            ScheduleExceptionKind kind,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            Boolean allDay,
            List<ExceptionPeriod> periods) {
        public CreateScheduleExceptionCommand {
            if (periods != null) {
                periods = Collections.unmodifiableList(new ArrayList<>(periods));
            }
        }
    }

    /** Kind and StaffMember are immutable and are taken from the stored aggregate. */
    public record ReplaceScheduleExceptionCommand(
            Long expectedVersion,
            LocalDate firstDate,
            LocalDate lastDate,
            Boolean allDay,
            List<ExceptionPeriod> periods) {
        public ReplaceScheduleExceptionCommand {
            if (periods != null) {
                periods = Collections.unmodifiableList(new ArrayList<>(periods));
            }
        }
    }

    public record ScheduleExceptionDetails(
            UUID id,
            ScheduleExceptionKind kind,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            boolean allDay,
            List<ExceptionPeriod> periods,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public ScheduleExceptionDetails {
            periods = List.copyOf(periods);
        }
    }

    public record ScheduleExceptionAdministrationDetails(
            ScheduleExceptionDetails exception, ZoneId timezone) {
    }

    public record ScheduleExceptionWindow(
            LocalDate from,
            LocalDate to,
            ZoneId timezone,
            List<ScheduleExceptionDetails> exceptions) {
        public ScheduleExceptionWindow {
            exceptions = List.copyOf(exceptions);
        }
    }
}
