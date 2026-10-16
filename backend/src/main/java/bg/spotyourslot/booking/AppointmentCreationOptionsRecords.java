package bg.spotyourslot.booking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Published, deeply immutable results of {@link AppointmentCreationOptions}. They carry no Customer,
 * contact, note, price, or Appointment datum: only StaffMember identifiers and display names, the
 * summed duration, and instants. Instants are absolute; the caller formats them in the Business
 * {@code timezone}.
 */
public final class AppointmentCreationOptionsRecords {
    private AppointmentCreationOptionsRecords() {
    }

    /** A StaffMember who may perform the whole selection. */
    public record EligibleStaffMember(UUID id, String displayName) {
        public EligibleStaffMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * @param durationMinutes the sum of the current durations of the selected Services
     * @param staffMembers ordered by canonical display name, then identifier; empty when nobody
     *        supports the complete selection
     */
    public record CreationOptions(
            ZoneId timezone, int durationMinutes, List<EligibleStaffMember> staffMembers) {
        public CreationOptions {
            Objects.requireNonNull(timezone, "timezone");
            staffMembers = List.copyOf(staffMembers);
        }
    }

    /** One offered start and the end of the interval it occupies. */
    public record OfferedStart(Instant start, Instant end) {
        public OfferedStart {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    /**
     * @param date the requested Business-local date
     * @param durationMinutes the sum of the current durations of the selected Services
     * @param starts ordered by start instant; only starts whose Business-local date is {@code date}
     */
    public record CreationAvailability(
            ZoneId timezone, LocalDate date, int durationMinutes, List<OfferedStart> starts) {
        public CreationAvailability {
            Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(date, "date");
            starts = List.copyOf(starts);
        }
    }
}
