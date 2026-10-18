package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCreationOptionsRecords.EligibleStaffMember;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.CurrentStaffMember;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleAvailability;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsRecords.RescheduleOptions;
import bg.spotyourslot.calendar.web.BusinessAppointmentOptionsHttpRecords.StartChoice;
import bg.spotyourslot.calendar.web.BusinessAppointmentOptionsHttpRecords.StaffMemberChoice;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the reschedule reads. Every timestamp is an ISO-8601 date-time with the
 * Business-local offset (the {@link StartChoice} of the create reads). The records carry no
 * Customer, contact, note, price, version, or Service datum. The exact key sets are pinned by the
 * API tests.
 */
public final class BusinessAppointmentRescheduleOptionsHttpRecords {
    private BusinessAppointmentRescheduleOptionsHttpRecords() {
    }

    public record CurrentStaffMemberChoice(UUID id, String displayName, boolean available) {
        static CurrentStaffMemberChoice from(CurrentStaffMember member) {
            return new CurrentStaffMemberChoice(member.id(), member.displayName(), member.available());
        }
    }

    public record RescheduleOptionsResponse(
            String timezone,
            int durationMinutes,
            CurrentStaffMemberChoice currentStaffMember,
            List<StaffMemberChoice> staffMembers) {
        static RescheduleOptionsResponse from(RescheduleOptions options) {
            return new RescheduleOptionsResponse(
                    options.timezone().getId(),
                    options.durationMinutes(),
                    CurrentStaffMemberChoice.from(options.currentStaffMember()),
                    options.replacements().stream()
                            .map(BusinessAppointmentRescheduleOptionsHttpRecords::choice)
                            .toList());
        }
    }

    public record RescheduleAvailabilityResponse(
            String timezone, String date, int durationMinutes, List<StartChoice> slots) {
        static RescheduleAvailabilityResponse from(RescheduleAvailability availability) {
            ZoneId zone = availability.timezone();
            return new RescheduleAvailabilityResponse(
                    zone.getId(),
                    availability.date().toString(),
                    availability.durationMinutes(),
                    availability.starts().stream().map(start -> StartChoice.from(start, zone)).toList());
        }
    }

    private static StaffMemberChoice choice(EligibleStaffMember member) {
        return new StaffMemberChoice(member.id(), member.displayName());
    }
}
