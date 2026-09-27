package bg.spotyourslot.workforce.web;

import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.StaffWorkingScheduleAdministrationDetails;
import bg.spotyourslot.workforce.StaffWorkingScheduleRecords.WorkingPeriod;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
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

public final class BusinessStaffWorkingScheduleHttpRecords {
    private BusinessStaffWorkingScheduleHttpRecords() {
    }

    public record WorkingPeriodRequest(
            DayOfWeek weekday,
            @JsonDeserialize(using = StrictCanonicalLocalTimeDeserializer.class) LocalTime startTime,
            @JsonDeserialize(using = StrictCanonicalLocalTimeDeserializer.class) LocalTime endTime) {
        WorkingPeriod toWorkingPeriod() {
            return new WorkingPeriod(weekday, startTime, endTime);
        }
    }

    public record ReplaceWorkingScheduleRequest(
            Long expectedVersion,
            List<WorkingPeriodRequest> periods) {
        public ReplaceWorkingScheduleRequest {
            if (periods != null) {
                periods = Collections.unmodifiableList(new ArrayList<>(periods));
            }
        }

        List<WorkingPeriod> toWorkingPeriods() {
            if (periods == null) {
                return null;
            }
            List<WorkingPeriod> converted = new ArrayList<>();
            for (WorkingPeriodRequest period : periods) {
                converted.add(period == null ? null : period.toWorkingPeriod());
            }
            return converted;
        }
    }

    public record WorkingPeriodResponse(
            DayOfWeek weekday,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime startTime,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime endTime) {
        static WorkingPeriodResponse from(WorkingPeriod period) {
            return new WorkingPeriodResponse(
                    period.weekday(), period.startTime(), period.endTime());
        }
    }

    public record WorkingScheduleResponse(
            UUID staffMemberId,
            ZoneId timezone,
            List<WorkingPeriodResponse> periods,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public WorkingScheduleResponse {
            periods = List.copyOf(periods);
        }

        static WorkingScheduleResponse from(StaffWorkingScheduleAdministrationDetails schedule) {
            return new WorkingScheduleResponse(
                    schedule.staffMemberId(),
                    schedule.timezone(),
                    schedule.periods().stream()
                            .map(WorkingPeriodResponse::from)
                            .toList(),
                    schedule.version(),
                    schedule.createdAt(),
                    schedule.updatedAt());
        }
    }

    /**
     * Accepts only the exact {@code HH:mm} lexical form (00:00-23:59, zero-padded,
     * no seconds, no whitespace) instead of java.time's lenient HOUR_OF_DAY=24
     * wraparound.
     */
    static final class StrictCanonicalLocalTimeDeserializer extends ValueDeserializer<LocalTime> {
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
}
