package bg.spotyourslot.scheduling.web;

import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InputField;
import bg.spotyourslot.scheduling.ScheduleExceptionApplicationException.InvalidInput;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ExceptionPeriod;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionAdministrationDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionDetails;
import bg.spotyourslot.scheduling.ScheduleExceptionRecords.ScheduleExceptionWindow;
import bg.spotyourslot.scheduling.domain.ScheduleExceptionKind;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class BusinessScheduleExceptionHttpRecords {
    private static final Pattern CANONICAL_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private BusinessScheduleExceptionHttpRecords() {
    }

    public record PeriodRequest(
            @JsonDeserialize(using = StrictCanonicalLocalTimeDeserializer.class) LocalTime startTime,
            @JsonDeserialize(using = StrictCanonicalLocalTimeDeserializer.class) LocalTime endTime) {
        ExceptionPeriod toPeriod() {
            return new ExceptionPeriod(startTime, endTime);
        }
    }

    public record CreateScheduleExceptionRequest(
            ScheduleExceptionKind kind,
            UUID staffMemberId,
            @JsonDeserialize(using = StrictCanonicalLocalDateDeserializer.class) LocalDate firstDate,
            @JsonDeserialize(using = StrictCanonicalLocalDateDeserializer.class) LocalDate lastDate,
            Boolean allDay,
            List<PeriodRequest> periods) {
        public CreateScheduleExceptionRequest {
            if (periods != null) {
                periods = Collections.unmodifiableList(new ArrayList<>(periods));
            }
        }

        List<ExceptionPeriod> toPeriods() {
            return convert(periods);
        }
    }

    public record ReplaceScheduleExceptionRequest(
            Long expectedVersion,
            @JsonDeserialize(using = StrictCanonicalLocalDateDeserializer.class) LocalDate firstDate,
            @JsonDeserialize(using = StrictCanonicalLocalDateDeserializer.class) LocalDate lastDate,
            Boolean allDay,
            List<PeriodRequest> periods) {
        public ReplaceScheduleExceptionRequest {
            if (periods != null) {
                periods = Collections.unmodifiableList(new ArrayList<>(periods));
            }
        }

        List<ExceptionPeriod> toPeriods() {
            return convert(periods);
        }
    }

    public record PeriodResponse(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime startTime,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime endTime) {
        static PeriodResponse from(ExceptionPeriod period) {
            return new PeriodResponse(period.startTime(), period.endTime());
        }
    }

    /** A list element; the Business timezone appears once on the list wrapper. */
    public record ScheduleExceptionItemResponse(
            UUID id,
            ScheduleExceptionKind kind,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            boolean allDay,
            List<PeriodResponse> periods,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public ScheduleExceptionItemResponse {
            periods = List.copyOf(periods);
        }

        static ScheduleExceptionItemResponse from(ScheduleExceptionDetails details) {
            return new ScheduleExceptionItemResponse(
                    details.id(),
                    details.kind(),
                    details.staffMemberId(),
                    details.firstDate(),
                    details.lastDate(),
                    details.allDay(),
                    details.periods().stream().map(PeriodResponse::from).toList(),
                    details.version(),
                    details.createdAt(),
                    details.updatedAt());
        }
    }

    public record ScheduleExceptionResponse(
            UUID id,
            ScheduleExceptionKind kind,
            UUID staffMemberId,
            LocalDate firstDate,
            LocalDate lastDate,
            boolean allDay,
            List<PeriodResponse> periods,
            ZoneId timezone,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public ScheduleExceptionResponse {
            periods = List.copyOf(periods);
        }

        static ScheduleExceptionResponse from(ScheduleExceptionAdministrationDetails result) {
            ScheduleExceptionItemResponse item =
                    ScheduleExceptionItemResponse.from(result.exception());
            return new ScheduleExceptionResponse(
                    item.id(),
                    item.kind(),
                    item.staffMemberId(),
                    item.firstDate(),
                    item.lastDate(),
                    item.allDay(),
                    item.periods(),
                    result.timezone(),
                    item.version(),
                    item.createdAt(),
                    item.updatedAt());
        }
    }

    public record ScheduleExceptionListResponse(
            LocalDate from,
            LocalDate to,
            ZoneId timezone,
            List<ScheduleExceptionItemResponse> exceptions) {
        public ScheduleExceptionListResponse {
            exceptions = List.copyOf(exceptions);
        }

        static ScheduleExceptionListResponse from(ScheduleExceptionWindow window) {
            return new ScheduleExceptionListResponse(
                    window.from(),
                    window.to(),
                    window.timezone(),
                    window.exceptions().stream()
                            .map(ScheduleExceptionItemResponse::from)
                            .toList());
        }
    }

    /** Parses a query date strictly; a malformed value is an ordinary validation failure. */
    static LocalDate parseQueryDate(String value) {
        if (value == null) {
            return null;
        }
        LocalDate parsed = parseCanonicalDate(value);
        if (parsed == null) {
            throw new InvalidInput(InputField.WINDOW);
        }
        return parsed;
    }

    private static LocalDate parseCanonicalDate(String value) {
        if (!CANONICAL_DATE.matcher(value).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static List<ExceptionPeriod> convert(List<PeriodRequest> periods) {
        if (periods == null) {
            return null;
        }
        List<ExceptionPeriod> converted = new ArrayList<>();
        for (PeriodRequest period : periods) {
            converted.add(period == null ? null : period.toPeriod());
        }
        return converted;
    }

    /**
     * Accepts only the exact {@code HH:mm} lexical form (00:00-23:59, zero-padded,
     * no seconds, no whitespace) instead of java.time's lenient HOUR_OF_DAY=24
     * wraparound.
     */
    private static final class StrictCanonicalLocalTimeDeserializer
            extends ValueDeserializer<LocalTime> {
        private static final Pattern CANONICAL_TIME =
                Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

        @Override
        public LocalTime deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            String value = parser.getValueAsString();
            if (value == null) {
                return null;
            }
            if (!CANONICAL_TIME.matcher(value).matches()) {
                return (LocalTime) context.handleWeirdStringValue(
                        LocalTime.class, value, "Not a canonical HH:mm time");
            }
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(3, 5));
            return LocalTime.of(hour, minute);
        }
    }

    /**
     * Accepts only the exact zero-padded {@code yyyy-MM-dd} form of a real
     * calendar date: no extended years, timestamps, arrays, or impossible dates.
     */
    private static final class StrictCanonicalLocalDateDeserializer
            extends ValueDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            String value = parser.getValueAsString();
            if (value == null) {
                return null;
            }
            LocalDate parsed = parseCanonicalDate(value);
            if (parsed == null) {
                return (LocalDate) context.handleWeirdStringValue(
                        LocalDate.class, value, "Not a canonical yyyy-MM-dd date");
            }
            return parsed;
        }
    }
}
