package bg.spotyourslot.publicbooking.web;

import bg.spotyourslot.booking.BookedAppointment;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Availability;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.BookingOptions;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Slot;
import bg.spotyourslot.publicbooking.application.PublicBookingViews.Staff;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * The public HTTP contracts of ADR-0026. Every response property is an explicit allowlist entry;
 * there is no Business, Customer, Membership, internal Appointment, or StaffMember-slot
 * identifier, no version, no audit timestamp, and no contact data. The request is read strictly
 * by {@link PublicBookingRequestParser}.
 */
public final class PublicBookingHttpRecords {
    private PublicBookingHttpRecords() {
    }

    // ---- responses ----

    public record BookingOptionsResponse(
            String timezone, String firstDate, String lastDate, List<StaffResponse> staff) {
        static BookingOptionsResponse from(BookingOptions options) {
            return new BookingOptionsResponse(
                    options.timezone().getId(),
                    options.firstDate().toString(),
                    options.lastDate().toString(),
                    options.staff().stream().map(StaffResponse::from).toList());
        }
    }

    public record StaffResponse(UUID id, String displayName) {
        static StaffResponse from(Staff staff) {
            return new StaffResponse(staff.id(), staff.displayName());
        }
    }

    public record AvailabilityResponse(
            String date, String timezone, List<String> availableDates, List<SlotResponse> slots) {
        static AvailabilityResponse from(Availability availability) {
            ZoneId zone = availability.timezone();
            return new AvailabilityResponse(
                    availability.date().toString(),
                    zone.getId(),
                    availability.availableDates().stream().map(Object::toString).toList(),
                    availability.slots().stream().map(slot -> SlotResponse.from(slot, zone)).toList());
        }
    }

    public record SlotResponse(String start, String end) {
        static SlotResponse from(Slot slot, ZoneId zone) {
            return new SlotResponse(format(slot.start(), zone), format(slot.end(), zone));
        }
    }

    public record BookingResponse(
            String reference,
            String status,
            BookedServiceResponse service,
            BookedStaffResponse staff,
            String start,
            String end,
            String timezone) {
        static BookingResponse from(BookedAppointment appointment) {
            ZoneId zone = ZoneId.of(appointment.timezone());
            return new BookingResponse(
                    appointment.reference(),
                    appointment.status().name(),
                    new BookedServiceResponse(
                            appointment.serviceName(),
                            appointment.durationMinutes(),
                            appointment.price()),
                    new BookedStaffResponse(appointment.staffDisplayName()),
                    format(appointment.start(), zone),
                    format(appointment.end(), zone),
                    appointment.timezone());
        }
    }

    public record BookedServiceResponse(String name, int durationMinutes, BigDecimal price) {
    }

    public record BookedStaffResponse(String displayName) {
    }

    /** An ISO-8601 instant with the Business-local offset, so a repeated wall-clock hour stays distinct. */
    private static String format(Instant instant, ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone));
    }
}
