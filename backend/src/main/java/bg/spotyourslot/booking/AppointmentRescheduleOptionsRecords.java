package bg.spotyourslot.booking;

import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.EligibleStaffMember;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.OfferedStart;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Published, deeply immutable results of {@link AppointmentRescheduleOptions}. They carry no
 * Customer, contact, note, price, version, or Service datum: only StaffMember identifiers and display
 * names, the stored duration, and instants. Instants are absolute; the caller formats them in the
 * Business {@code timezone}.
 */
public final class AppointmentRescheduleOptionsRecords {
    private AppointmentRescheduleOptionsRecords() {
    }

    /**
     * The StaffMember the Appointment is booked with.
     *
     * @param displayName the stored snapshot name, as the Appointment detail shows it
     * @param available whether the StaffMember is still active, which is all a time-only move needs
     *        (a Service assignment removed later does not matter)
     */
    public record CurrentStaffMember(UUID id, String displayName, boolean available) {
        public CurrentStaffMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * @param durationMinutes the stored duration of the Appointment, never the current Services'
     * @param replacements the other active StaffMembers assigned to every stored Service, ordered by
     *        canonical display name, then identifier; the current StaffMember is never listed
     */
    public record RescheduleOptions(
            ZoneId timezone,
            int durationMinutes,
            CurrentStaffMember currentStaffMember,
            List<EligibleStaffMember> replacements) {
        public RescheduleOptions {
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(currentStaffMember, "currentStaffMember");
            replacements = List.copyOf(replacements);
        }
    }

    /**
     * @param date the requested Business-local date
     * @param durationMinutes the stored duration of the Appointment
     * @param starts ordered by start instant; only starts whose Business-local date is {@code date}
     */
    public record RescheduleAvailability(
            ZoneId timezone, LocalDate date, int durationMinutes, List<OfferedStart> starts) {
        public RescheduleAvailability {
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(date, "date");
            starts = List.copyOf(starts);
        }
    }
}
