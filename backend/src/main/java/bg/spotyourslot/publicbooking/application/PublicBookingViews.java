package bg.spotyourslot.publicbooking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** The application-level public views: the approved allowlist and nothing else. */
public final class PublicBookingViews {
    private PublicBookingViews() {
    }

    /**
     * @param firstDate the first Business-local bookable date
     * @param lastDate the last Business-local bookable date
     */
    public record BookingOptions(
            ZoneId timezone, LocalDate firstDate, LocalDate lastDate, List<Staff> staff) {
        public BookingOptions {
            staff = List.copyOf(staff);
        }
    }

    public record Staff(UUID id, String displayName) {
    }

    /**
     * @param date the requested Business-local date
     * @param availableDates every Business-local date of the window that offers at least one slot
     * @param slots the offered slots of {@code date}, ordered by start; no StaffMember is attached
     */
    public record Availability(
            LocalDate date, ZoneId timezone, List<LocalDate> availableDates, List<Slot> slots) {
        public Availability {
            availableDates = List.copyOf(availableDates);
            slots = List.copyOf(slots);
        }
    }

    public record Slot(Instant start, Instant end) {
    }
}
