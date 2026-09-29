package bg.spotyourslot.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class AvailabilityTestSupport {
    static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    static final UUID STAFF_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID STAFF_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private AvailabilityTestSupport() {
    }

    static LocalPeriod period(String start, String end) {
        return new LocalPeriod(LocalTime.parse(start), LocalTime.parse(end));
    }

    static Instant instant(ZoneId zone, String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(zone).toInstant();
    }

    static Duration minutes(int minutes) {
        return Duration.ofMinutes(minutes);
    }

    static Builder staff(UUID id) {
        return new Builder(id);
    }

    static AvailabilityRequest request(
            ZoneId zone, Duration duration, Instant now, StaffAvailabilityInput... staff) {
        return new AvailabilityRequest(zone, duration, now, List.of(), List.of(staff));
    }

    static AvailabilityRequest request(
            ZoneId zone,
            Duration duration,
            Instant now,
            List<LocalBlock> businessClosures,
            StaffAvailabilityInput... staff) {
        return new AvailabilityRequest(zone, duration, now, businessClosures, List.of(staff));
    }

    static List<LocalTime> localTimesOn(List<AvailableSlot> slots, LocalDate date) {
        List<LocalTime> times = new ArrayList<>();
        for (AvailableSlot slot : slots) {
            if (slot.localStart().toLocalDate().equals(date)) {
                times.add(slot.localStart().toLocalTime());
            }
        }
        return times;
    }

    static List<LocalTime> times(String... values) {
        List<LocalTime> times = new ArrayList<>();
        for (String value : values) {
            times.add(LocalTime.parse(value));
        }
        return times;
    }

    static final class Builder {
        private final UUID id;
        private final Map<DayOfWeek, List<LocalPeriod>> recurring = new EnumMap<>(DayOfWeek.class);
        private final Map<LocalDate, List<LocalPeriod>> overrides = new HashMap<>();
        private final Map<LocalDate, List<LocalPeriod>> additional = new HashMap<>();
        private final List<LocalBlock> timeOff = new ArrayList<>();
        private final List<BusyInterval> busy = new ArrayList<>();

        private Builder(UUID id) {
            this.id = id;
        }

        Builder recurring(DayOfWeek day, LocalPeriod... periods) {
            recurring.put(day, List.of(periods));
            return this;
        }

        Builder everyDay(LocalPeriod... periods) {
            for (DayOfWeek day : DayOfWeek.values()) {
                recurring.put(day, List.of(periods));
            }
            return this;
        }

        Builder override(LocalDate date, LocalPeriod... periods) {
            overrides.put(date, List.of(periods));
            return this;
        }

        Builder additional(LocalDate date, LocalPeriod... periods) {
            additional.put(date, List.of(periods));
            return this;
        }

        Builder timeOff(LocalBlock block) {
            timeOff.add(block);
            return this;
        }

        Builder busy(Instant start, Instant end) {
            busy.add(new BusyInterval(start, end));
            return this;
        }

        StaffAvailabilityInput build() {
            return new StaffAvailabilityInput(id, recurring, overrides, additional, timeOff, busy);
        }
    }
}
