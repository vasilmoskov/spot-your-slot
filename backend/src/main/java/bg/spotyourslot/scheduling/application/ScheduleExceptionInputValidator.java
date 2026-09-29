package bg.spotyourslot.scheduling.application;

import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InputField;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.CreateScheduleExceptionCommand;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ReplaceScheduleExceptionCommand;
import bg.spotyourslot.scheduling.domain.LocalPeriod;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionContent;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Bounded application validation for schedule exceptions. The date bounds are
 * an MVP technical safety boundary that keeps extreme input away from
 * PostgreSQL; they are unrelated to the booking horizon of ADR-0013.
 */
@Component
public class ScheduleExceptionInputValidator {
    public static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    public static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);
    public static final int MAX_FULL_DAY_SPAN_DATES = 366;
    public static final int MAX_PERIODS = 24;
    public static final int MAX_WINDOW_DATES = 93;

    private static final Comparator<LocalPeriod> BY_START_THEN_END =
            Comparator.comparing(LocalPeriod::start).thenComparing(LocalPeriod::end);

    /** Kind-independent, validated fields of a create or replace request. */
    public record Shape(
            LocalDate firstDate, LocalDate lastDate, boolean allDay, List<LocalPeriod> periods) {
    }

    /** A validated replacement: the expected version and the kind-independent shape. */
    public record ValidatedReplacement(long expectedVersion, Shape shape) {
    }

    /** A validated inclusive list window. */
    public record Window(LocalDate from, LocalDate to) {
    }

    public ScheduleExceptionContent validateCreate(CreateScheduleExceptionCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        ScheduleExceptionKind kind = command.kind();
        if (kind == null) {
            throw new InvalidInput(InputField.KIND);
        }
        if (kind.isStaffScoped() == (command.staffMemberId() == null)) {
            throw new InvalidInput(InputField.STAFF_MEMBER_ID);
        }
        Shape shape = shape(
                command.firstDate(),
                command.lastDate(),
                command.allDay(),
                command.periods());
        return content(kind, command.staffMemberId(), shape);
    }

    public ValidatedReplacement validateReplacement(ReplaceScheduleExceptionCommand command) {
        if (command == null) {
            throw new InvalidInput(InputField.COMMAND);
        }
        long expectedVersion = expectedVersion(command.expectedVersion());
        return new ValidatedReplacement(
                expectedVersion,
                shape(
                        command.firstDate(),
                        command.lastDate(),
                        command.allDay(),
                        command.periods()));
    }

    /**
     * Applies the kind-specific rules and builds the domain content. On replace,
     * the kind and StaffMember come from the stored aggregate.
     */
    public ScheduleExceptionContent content(
            ScheduleExceptionKind kind, UUID staffMemberId, Shape shape) {
        if (!kind.isBlock() && shape.allDay()) {
            throw new InvalidInput(InputField.ALL_DAY);
        }
        if (!shape.allDay()
                && shape.periods().isEmpty()
                && kind != ScheduleExceptionKind.WORKING_DAY_OVERRIDE) {
            throw new InvalidInput(InputField.PERIODS);
        }
        return new ScheduleExceptionContent(
                kind,
                staffMemberId,
                shape.firstDate(),
                shape.lastDate(),
                shape.allDay(),
                shape.periods());
    }

    public long validateExpectedVersion(Long expectedVersion) {
        return expectedVersion(expectedVersion);
    }

    public Window validateWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || !inBounds(from) || !inBounds(to) || to.isBefore(from)) {
            throw new InvalidInput(InputField.WINDOW);
        }
        if (inclusiveDates(from, to) > MAX_WINDOW_DATES) {
            throw new InvalidInput(InputField.WINDOW);
        }
        return new Window(from, to);
    }

    private Shape shape(
            LocalDate firstDate, LocalDate lastDate, Boolean allDay, List<ExceptionPeriod> periods) {
        if (firstDate == null || !inBounds(firstDate)) {
            throw new InvalidInput(InputField.FIRST_DATE);
        }
        if (lastDate == null || !inBounds(lastDate) || lastDate.isBefore(firstDate)) {
            throw new InvalidInput(InputField.LAST_DATE);
        }
        if (inclusiveDates(firstDate, lastDate) > MAX_FULL_DAY_SPAN_DATES) {
            throw new InvalidInput(InputField.LAST_DATE);
        }
        if (allDay == null) {
            throw new InvalidInput(InputField.ALL_DAY);
        }
        if (!allDay && !firstDate.equals(lastDate)) {
            throw new InvalidInput(InputField.LAST_DATE);
        }
        List<LocalPeriod> validated = validatePeriods(periods);
        if (allDay && !validated.isEmpty()) {
            throw new InvalidInput(InputField.PERIODS);
        }
        return new Shape(firstDate, lastDate, allDay, validated);
    }

    private List<LocalPeriod> validatePeriods(List<ExceptionPeriod> periods) {
        if (periods == null || periods.size() > MAX_PERIODS) {
            throw new InvalidInput(InputField.PERIODS);
        }
        List<LocalPeriod> converted = new ArrayList<>(periods.size());
        Set<ExceptionPeriod> unique = new HashSet<>();
        for (ExceptionPeriod period : periods) {
            if (period == null
                    || !isMinutePrecise(period.startTime())
                    || !isMinutePrecise(period.endTime())
                    || !period.startTime().isBefore(period.endTime())
                    || !unique.add(period)) {
                throw new InvalidInput(InputField.PERIODS);
            }
            converted.add(new LocalPeriod(period.startTime(), period.endTime()));
        }
        converted.sort(BY_START_THEN_END);
        for (int index = 1; index < converted.size(); index++) {
            if (converted.get(index).start().isBefore(converted.get(index - 1).end())) {
                throw new InvalidInput(InputField.PERIODS);
            }
        }
        return List.copyOf(converted);
    }

    private long expectedVersion(Long value) {
        if (value == null || value < 0) {
            throw new InvalidInput(InputField.EXPECTED_VERSION);
        }
        return value;
    }

    private boolean inBounds(LocalDate date) {
        return !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE);
    }

    private long inclusiveDates(LocalDate first, LocalDate last) {
        return ChronoUnit.DAYS.between(first, last) + 1;
    }

    private boolean isMinutePrecise(LocalTime value) {
        return value != null && value.getSecond() == 0 && value.getNano() == 0;
    }
}
