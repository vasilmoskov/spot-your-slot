package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationAvailability;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.CreationOptions;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.EligibleStaffMember;
import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.OfferedStart;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the manual-create reads. Every timestamp is an ISO-8601 date-time with the
 * Business-local offset, so the two occurrences of a repeated wall-clock hour stay distinct. The
 * records carry no Customer, contact, note, or price. The exact key sets are pinned by the API tests.
 */
public final class BusinessAppointmentOptionsHttpRecords {
    private BusinessAppointmentOptionsHttpRecords() {
    }

    public record StaffMemberChoice(UUID id, String displayName) {
        static StaffMemberChoice from(EligibleStaffMember member) {
            return new StaffMemberChoice(member.id(), member.displayName());
        }
    }

    public record CreationOptionsResponse(
            String timezone, int durationMinutes, List<StaffMemberChoice> staffMembers) {
        static CreationOptionsResponse from(CreationOptions options) {
            return new CreationOptionsResponse(
                    options.timezone().getId(),
                    options.durationMinutes(),
                    options.staffMembers().stream().map(StaffMemberChoice::from).toList());
        }
    }

    public record StartChoice(String start, String end) {
        static StartChoice from(OfferedStart start, ZoneId zone) {
            return new StartChoice(format(start.start(), zone), format(start.end(), zone));
        }
    }

    public record CreationAvailabilityResponse(
            String timezone, String date, int durationMinutes, List<StartChoice> slots) {
        static CreationAvailabilityResponse from(CreationAvailability availability) {
            ZoneId zone = availability.timezone();
            return new CreationAvailabilityResponse(
                    zone.getId(),
                    availability.date().toString(),
                    availability.durationMinutes(),
                    availability.starts().stream().map(start -> StartChoice.from(start, zone)).toList());
        }
    }

    private static String format(Instant instant, ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone));
    }
}
