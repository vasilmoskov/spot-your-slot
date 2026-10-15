package bg.spotyourslot.booking;

import static org.assertj.core.api.Assertions.assertThat;

import bg.spotyourslot.booking.AppointmentCalendarException.AccessDenied;
import bg.spotyourslot.booking.AppointmentCalendarException.AppointmentNotFound;
import bg.spotyourslot.booking.AppointmentCalendarException.CustomerNotFound;
import bg.spotyourslot.booking.AppointmentCalendarException.Failure;
import bg.spotyourslot.booking.AppointmentCalendarException.InvalidInput;
import bg.spotyourslot.booking.AppointmentCalendarException.StaffMemberNotFound;
import bg.spotyourslot.booking.AppointmentCalendarRecords.AgendaPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.AppointmentDetail;
import bg.spotyourslot.booking.AppointmentCalendarRecords.BusinessStatus;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarAppointment;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarStaffMember;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarView;
import bg.spotyourslot.booking.AppointmentCalendarRecords.Cancellation;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CancelledBy;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CustomerContactView;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CustomerHistoryPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.DetailLine;
import bg.spotyourslot.booking.AppointmentCalendarRecords.HistoryItem;
import bg.spotyourslot.booking.AppointmentCalendarRecords.ServiceSummary;
import bg.spotyourslot.booking.AppointmentCalendarRecords.Source;
import bg.spotyourslot.booking.AppointmentCalendarRecords.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every published calendar record that can carry a name, a note, contact data, or an attribution
 * redacts {@code toString()}, and every exception has a fixed, value-free, cause-free message.
 */
class AppointmentCalendarRecordsTests {
    private static final String SECRET = "СЕНТИНЕЛ-ТАЙНА-3141";
    private static final Instant T = Instant.parse("2026-11-10T09:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Europe/Sofia");

    @Test
    void everyRecordThatCarriesPrivateDataRedactsItsToString() {
        UUID id = UUID.randomUUID();
        CalendarAppointment row = new CalendarAppointment(
                id, Status.CANCELLED, Source.MANUAL, T, T.plusSeconds(60), id, SECRET, id, SECRET, 30,
                List.of(new ServiceSummary(SECRET, 30)));
        List<Object> values = List.of(
                row,
                new ServiceSummary(SECRET, 30),
                new CalendarStaffMember(id, SECRET, true, List.of(), List.of()),
                new CalendarView(BusinessStatus.ACTIVE, ZONE, LocalDate.EPOCH, LocalDate.EPOCH, List.of(), List.of(), List.of(row)),
                new AgendaPage(BusinessStatus.ACTIVE, ZONE, LocalDate.EPOCH, LocalDate.EPOCH, List.of(row), 0, 10, 1),
                new DetailLine(1, SECRET, 30, BigDecimal.TEN),
                new CustomerContactView(id, SECRET, SECRET, SECRET),
                new Cancellation(T, CancelledBy.BUSINESS),
                new AppointmentDetail(BusinessStatus.ACTIVE, ZONE, id, SECRET, Status.CANCELLED, Source.ONLINE, T, T,
                        id, SECRET, new CustomerContactView(id, SECRET, SECRET, SECRET), List.of(), 30, BigDecimal.TEN,
                        SECRET, SECRET, new Cancellation(T, CancelledBy.BUSINESS), 1, T, T),
                new HistoryItem(id, Status.CONFIRMED, Source.ONLINE, T, T, SECRET, List.of(SECRET)),
                new CustomerHistoryPage(ZONE, List.of(new HistoryItem(id, Status.CONFIRMED, Source.ONLINE, T, T, SECRET, List.of(SECRET))), 0, 10, 1));
        for (Object value : values) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).contains("redacted")
                    .doesNotContain(SECRET, "BUSINESS", "CANCELLED", id.toString());
        }
    }

    @Test
    void everyExceptionHasAFixedMessageAndNoCauseOrSuppressedException() {
        for (AppointmentCalendarException exception : List.<AppointmentCalendarException>of(
                new InvalidInput(), new AccessDenied(), new AppointmentNotFound(), new CustomerNotFound(),
                new StaffMemberNotFound(), new Failure())) {
            assertThat(exception.getMessage()).isNotBlank();
            assertThat(exception.getCause()).isNull();
            assertThat(exception.getSuppressed()).isEmpty();
        }
        assertThat(new AppointmentNotFound().getMessage()).isEqualTo(new AppointmentNotFound().getMessage());
    }
}
