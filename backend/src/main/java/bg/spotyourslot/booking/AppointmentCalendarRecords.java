package bg.spotyourslot.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Published, deeply immutable result records of {@link AppointmentCalendar}. They are deliberately
 * separate from the persistence rows and the {@code booking.domain} types. Instants are absolute;
 * the caller formats them in the Business {@code timezone}. Every record that carries personal or
 * private data redacts {@link #toString()}.
 *
 * <p>What appears where: the internal note, the guest note, the cancellation attribution, the
 * public reference, the visit price, the version, and the Customer contact appear only in
 * {@link AppointmentDetail}. List rows carry the Customer display name (calendar and agenda only).
 * The Customer history carries neither a Customer name nor any note, reference, or price.
 */
public final class AppointmentCalendarRecords {
    private AppointmentCalendarRecords() {
    }

    public enum Status {
        CONFIRMED,
        CANCELLED
    }

    public enum Source {
        ONLINE,
        MANUAL
    }

    public enum CancelledBy {
        BUSINESS,
        UNKNOWN
    }

    public enum BusinessStatus {
        DRAFT,
        ACTIVE,
        SUSPENDED
    }

    public enum ClosureKind {
        BUSINESS_CLOSURE,
        STAFF_TIME_OFF
    }

    public record ServiceSummary(String name, int durationMinutes) {
        public ServiceSummary {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return "ServiceSummary[redacted]";
        }
    }

    /** A calendar or agenda row. */
    public record CalendarAppointment(
            UUID id,
            Status status,
            Source source,
            Instant start,
            Instant end,
            UUID staffMemberId,
            String staffDisplayName,
            UUID customerId,
            String customerDisplayName,
            int durationMinutes,
            List<ServiceSummary> services) {
        public CalendarAppointment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(staffMemberId, "staffMemberId");
            Objects.requireNonNull(staffDisplayName, "staffDisplayName");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(customerDisplayName, "customerDisplayName");
            services = List.copyOf(services);
        }

        @Override
        public String toString() {
            return "CalendarAppointment[redacted]";
        }
    }

    /** A Business-local date with its zone-rule instant bounds (23, 24, or 25 hours). */
    public record CalendarDay(LocalDate date, Instant start, Instant end) {
    }

    /** A half-open working interval {@code [start, end)}, net of partial exceptions. */
    public record TimeInterval(Instant start, Instant end) {
    }

    /** A date blocked for the whole day by a full-day exception. */
    public record CalendarClosure(LocalDate date, ClosureKind kind) {
    }

    public record CalendarStaffMember(
            UUID id,
            String displayName,
            boolean active,
            List<TimeInterval> workingIntervals,
            List<CalendarClosure> closures) {
        public CalendarStaffMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            workingIntervals = List.copyOf(workingIntervals);
            closures = List.copyOf(closures);
        }

        @Override
        public String toString() {
            return "CalendarStaffMember[redacted]";
        }
    }

    public record CalendarView(
            BusinessStatus businessStatus,
            ZoneId timezone,
            LocalDate from,
            LocalDate to,
            List<CalendarDay> days,
            List<CalendarStaffMember> staffMembers,
            List<CalendarAppointment> appointments) {
        public CalendarView {
            days = List.copyOf(days);
            staffMembers = List.copyOf(staffMembers);
            appointments = List.copyOf(appointments);
        }

        @Override
        public String toString() {
            return "CalendarView[redacted]";
        }
    }

    public record AgendaPage(
            BusinessStatus businessStatus,
            ZoneId timezone,
            LocalDate from,
            LocalDate to,
            List<CalendarAppointment> items,
            int page,
            int size,
            long total) {
        public AgendaPage {
            items = List.copyOf(items);
        }

        @Override
        public String toString() {
            return "AgendaPage[redacted]";
        }
    }

    public record DetailLine(int position, String name, int durationMinutes, BigDecimal price) {
        @Override
        public String toString() {
            return "DetailLine[redacted]";
        }
    }

    public record CustomerContactView(UUID id, String displayName, String phone, String email) {
        @Override
        public String toString() {
            return "CustomerContactView[redacted]";
        }
    }

    /** Present only on a cancelled Appointment; {@code cancelledAt} is null for {@code UNKNOWN}. */
    public record Cancellation(Instant cancelledAt, CancelledBy cancelledBy) {
        @Override
        public String toString() {
            return "Cancellation[redacted]";
        }
    }

    public record AppointmentDetail(
            BusinessStatus businessStatus,
            ZoneId timezone,
            UUID id,
            String reference,
            Status status,
            Source source,
            Instant start,
            Instant end,
            UUID staffMemberId,
            String staffDisplayName,
            CustomerContactView customer,
            List<DetailLine> services,
            int durationMinutes,
            BigDecimal price,
            String customerNote,
            String internalNote,
            Cancellation cancellation,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public AppointmentDetail {
            services = List.copyOf(services);
        }

        @Override
        public String toString() {
            return "AppointmentDetail[redacted]";
        }
    }

    /** A Customer-history row: the minimum facts, never a note, a reference, a price, or contact data. */
    public record HistoryItem(
            UUID id,
            Status status,
            Source source,
            Instant start,
            Instant end,
            String staffDisplayName,
            List<String> serviceNames) {
        public HistoryItem {
            serviceNames = List.copyOf(serviceNames);
        }

        @Override
        public String toString() {
            return "HistoryItem[redacted]";
        }
    }

    public record CustomerHistoryPage(
            ZoneId timezone, List<HistoryItem> items, int page, int size, long total) {
        public CustomerHistoryPage {
            items = List.copyOf(items);
        }

        @Override
        public String toString() {
            return "CustomerHistoryPage[redacted]";
        }
    }
}
