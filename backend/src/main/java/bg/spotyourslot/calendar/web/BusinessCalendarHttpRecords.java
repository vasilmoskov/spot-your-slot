package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedAppointment;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.CreatedService;
import bg.spotyourslot.booking.AppointmentAdministrationRecords.RescheduledAppointment;
import bg.spotyourslot.booking.AppointmentCalendarRecords.AgendaPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.AppointmentDetail;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarAppointment;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarClosure;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarDay;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarStaffMember;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarView;
import bg.spotyourslot.booking.AppointmentCalendarRecords.Cancellation;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CustomerHistoryPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.HistoryItem;
import bg.spotyourslot.booking.AppointmentCalendarRecords.ServiceSummary;
import bg.spotyourslot.booking.AppointmentCalendarRecords.TimeInterval;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Wire shapes of the private calendar API. Every timestamp is an ISO-8601 date-time with the
 * Business-local offset (so a repeated wall-clock hour stays distinct), every date is a canonical
 * {@code YYYY-MM-DD} Business-local date, and every record that carries personal or private data
 * redacts {@code toString()}. The exact key sets are pinned by the API tests.
 */
public final class BusinessCalendarHttpRecords {
    private static final Pattern CANONICAL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private BusinessCalendarHttpRecords() {
    }

    /** Strict {@code YYYY-MM-DD}; anything else is a generic validation error (null for absent). */
    static LocalDate parseDate(String value) {
        if (value == null || !CANONICAL_DATE.matcher(value).matches()) {
            return null;
        }
        try {
            LocalDate parsed = LocalDate.parse(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    /**
     * The owner-only {@code 201} body of a created Appointment: the stored facts only, no Customer
     * name, phone, email, or Customer note.
     */
    public record CreatedAppointmentResponse(
            UUID id,
            String reference,
            String status,
            String source,
            String start,
            String end,
            String timezone,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            List<CreatedServiceResponse> services,
            int durationMinutes,
            BigDecimal price,
            String internalNote,
            long version,
            String createdAt) {
        public static CreatedAppointmentResponse from(CreatedAppointment created) {
            ZoneId zone = ZoneId.of(created.timezone());
            return new CreatedAppointmentResponse(
                    created.id(),
                    created.reference(),
                    created.status(),
                    created.source(),
                    format(created.start(), zone),
                    format(created.end(), zone),
                    created.timezone(),
                    created.staffMemberId(),
                    created.staffDisplayName(),
                    created.customerId(),
                    created.services().stream().map(CreatedServiceResponse::from).toList(),
                    created.durationMinutes(),
                    created.price(),
                    created.internalNote(),
                    created.version(),
                    format(created.createdAt(), zone));
        }

        @Override
        public String toString() {
            return "CreatedAppointmentResponse[redacted]";
        }
    }

    /**
     * The owner-only {@code 200} body of a rescheduled or reassigned Appointment: the stored facts
     * only, no Customer name, phone, email, or Customer note. Customer identity, Services, duration,
     * price, source, and reference are exactly as before; the version and update time advanced.
     */
    public record RescheduledAppointmentResponse(
            UUID id,
            String reference,
            String status,
            String source,
            String start,
            String end,
            String timezone,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            List<CreatedServiceResponse> services,
            int durationMinutes,
            BigDecimal price,
            String internalNote,
            long version,
            String createdAt,
            String updatedAt) {
        public static RescheduledAppointmentResponse from(RescheduledAppointment updated) {
            ZoneId zone = ZoneId.of(updated.timezone());
            return new RescheduledAppointmentResponse(
                    updated.id(),
                    updated.reference(),
                    updated.status(),
                    updated.source(),
                    format(updated.start(), zone),
                    format(updated.end(), zone),
                    updated.timezone(),
                    updated.staffMemberId(),
                    updated.staffDisplayName(),
                    updated.customerId(),
                    updated.services().stream().map(CreatedServiceResponse::from).toList(),
                    updated.durationMinutes(),
                    updated.price(),
                    updated.internalNote(),
                    updated.version(),
                    format(updated.createdAt(), zone),
                    format(updated.updatedAt(), zone));
        }

        @Override
        public String toString() {
            return "RescheduledAppointmentResponse[redacted]";
        }
    }

    public record CreatedServiceResponse(int position, String name, int durationMinutes, BigDecimal price) {
        static CreatedServiceResponse from(CreatedService service) {
            return new CreatedServiceResponse(
                    service.position(), service.name(), service.durationMinutes(), service.price());
        }

        @Override
        public String toString() {
            return "CreatedServiceResponse[redacted]";
        }
    }

    private static String format(Instant instant, ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(zone));
    }

    public record ServiceResponse(String name, int durationMinutes) {
        static ServiceResponse from(ServiceSummary service) {
            return new ServiceResponse(service.name(), service.durationMinutes());
        }

        @Override
        public String toString() {
            return "ServiceResponse[redacted]";
        }
    }

    public record AppointmentRowResponse(
            UUID id,
            String status,
            String source,
            String start,
            String end,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            String customerDisplayName,
            int durationMinutes,
            List<ServiceResponse> services) {
        static AppointmentRowResponse from(CalendarAppointment row, ZoneId zone) {
            return new AppointmentRowResponse(
                    row.id(),
                    row.status().name(),
                    row.source().name(),
                    format(row.start(), zone),
                    format(row.end(), zone),
                    row.staffMemberId(),
                    row.staffDisplayName(),
                    row.customerId(),
                    row.customerDisplayName(),
                    row.durationMinutes(),
                    row.services().stream().map(ServiceResponse::from).toList());
        }

        @Override
        public String toString() {
            return "AppointmentRowResponse[redacted]";
        }
    }

    public record DayResponse(String date, String start, String end) {
        static DayResponse from(CalendarDay day, ZoneId zone) {
            return new DayResponse(
                    day.date().toString(), format(day.start(), zone), format(day.end(), zone));
        }
    }

    public record IntervalResponse(String start, String end) {
        static IntervalResponse from(TimeInterval interval, ZoneId zone) {
            return new IntervalResponse(format(interval.start(), zone), format(interval.end(), zone));
        }
    }

    public record ClosureResponse(String date, String kind) {
        static ClosureResponse from(CalendarClosure closure) {
            return new ClosureResponse(closure.date().toString(), closure.kind().name());
        }
    }

    public record StaffMemberResponse(
            UUID id,
            String displayName,
            boolean active,
            List<IntervalResponse> workingIntervals,
            List<ClosureResponse> closures) {
        static StaffMemberResponse from(CalendarStaffMember member, ZoneId zone) {
            return new StaffMemberResponse(
                    member.id(),
                    member.displayName(),
                    member.active(),
                    member.workingIntervals().stream().map(i -> IntervalResponse.from(i, zone)).toList(),
                    member.closures().stream().map(ClosureResponse::from).toList());
        }

        @Override
        public String toString() {
            return "StaffMemberResponse[redacted]";
        }
    }

    public record CalendarResponse(
            String businessStatus,
            String timezone,
            String from,
            String to,
            List<DayResponse> days,
            List<StaffMemberResponse> staffMembers,
            List<AppointmentRowResponse> appointments) {
        public static CalendarResponse from(CalendarView view) {
            ZoneId zone = view.timezone();
            return new CalendarResponse(
                    view.businessStatus().name(),
                    zone.getId(),
                    view.from().toString(),
                    view.to().toString(),
                    view.days().stream().map(day -> DayResponse.from(day, zone)).toList(),
                    view.staffMembers().stream().map(member -> StaffMemberResponse.from(member, zone)).toList(),
                    view.appointments().stream().map(row -> AppointmentRowResponse.from(row, zone)).toList());
        }

        @Override
        public String toString() {
            return "CalendarResponse[redacted]";
        }
    }

    public record AgendaResponse(
            String businessStatus,
            String timezone,
            String from,
            String to,
            List<AppointmentRowResponse> items,
            int page,
            int size,
            long total) {
        public static AgendaResponse from(AgendaPage agenda) {
            ZoneId zone = agenda.timezone();
            return new AgendaResponse(
                    agenda.businessStatus().name(),
                    zone.getId(),
                    agenda.from().toString(),
                    agenda.to().toString(),
                    agenda.items().stream().map(row -> AppointmentRowResponse.from(row, zone)).toList(),
                    agenda.page(),
                    agenda.size(),
                    agenda.total());
        }

        @Override
        public String toString() {
            return "AgendaResponse[redacted]";
        }
    }

    public record CustomerResponse(UUID id, String displayName, String phone, String email) {
        @Override
        public String toString() {
            return "CustomerResponse[redacted]";
        }
    }

    public record DetailLineResponse(int position, String name, int durationMinutes, BigDecimal price) {
        @Override
        public String toString() {
            return "DetailLineResponse[redacted]";
        }
    }

    public record CancellationResponse(String cancelledAt, String cancelledBy) {
        static CancellationResponse from(Cancellation cancellation, ZoneId zone) {
            return new CancellationResponse(
                    cancellation.cancelledAt() == null ? null : format(cancellation.cancelledAt(), zone),
                    cancellation.cancelledBy().name());
        }

        @Override
        public String toString() {
            return "CancellationResponse[redacted]";
        }
    }

    public record DetailResponse(
            String businessStatus,
            String timezone,
            UUID id,
            String reference,
            String status,
            String source,
            String start,
            String end,
            UUID staffMemberId,
            String staffDisplayName,
            CustomerResponse customer,
            List<DetailLineResponse> services,
            int durationMinutes,
            BigDecimal price,
            String customerNote,
            String internalNote,
            CancellationResponse cancellation,
            long version,
            String createdAt,
            String updatedAt) {
        public static DetailResponse from(AppointmentDetail detail) {
            ZoneId zone = detail.timezone();
            return new DetailResponse(
                    detail.businessStatus().name(),
                    zone.getId(),
                    detail.id(),
                    detail.reference(),
                    detail.status().name(),
                    detail.source().name(),
                    format(detail.start(), zone),
                    format(detail.end(), zone),
                    detail.staffMemberId(),
                    detail.staffDisplayName(),
                    new CustomerResponse(
                            detail.customer().id(),
                            detail.customer().displayName(),
                            detail.customer().phone(),
                            detail.customer().email()),
                    detail.services().stream()
                            .map(line -> new DetailLineResponse(
                                    line.position(), line.name(), line.durationMinutes(), line.price()))
                            .toList(),
                    detail.durationMinutes(),
                    detail.price(),
                    detail.customerNote(),
                    detail.internalNote(),
                    detail.cancellation() == null
                            ? null
                            : CancellationResponse.from(detail.cancellation(), zone),
                    detail.version(),
                    format(detail.createdAt(), zone),
                    format(detail.updatedAt(), zone));
        }

        @Override
        public String toString() {
            return "DetailResponse[redacted]";
        }
    }

    public record HistoryItemResponse(
            UUID id,
            String status,
            String source,
            String start,
            String end,
            String staffDisplayName,
            List<String> serviceNames) {
        static HistoryItemResponse from(HistoryItem item, ZoneId zone) {
            return new HistoryItemResponse(
                    item.id(),
                    item.status().name(),
                    item.source().name(),
                    format(item.start(), zone),
                    format(item.end(), zone),
                    item.staffDisplayName(),
                    item.serviceNames());
        }

        @Override
        public String toString() {
            return "HistoryItemResponse[redacted]";
        }
    }

    public record HistoryResponse(
            String timezone, List<HistoryItemResponse> items, int page, int size, long total) {
        public static HistoryResponse from(CustomerHistoryPage history) {
            ZoneId zone = history.timezone();
            return new HistoryResponse(
                    zone.getId(),
                    history.items().stream().map(item -> HistoryItemResponse.from(item, zone)).toList(),
                    history.page(),
                    history.size(),
                    history.total());
        }

        @Override
        public String toString() {
            return "HistoryResponse[redacted]";
        }
    }
}
