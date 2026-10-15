package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.AppointmentCalendar;
import bg.spotyourslot.booking.AppointmentCalendarException;
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
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarClosure;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarDay;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarStaffMember;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CalendarView;
import bg.spotyourslot.booking.AppointmentCalendarRecords.Cancellation;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CancelledBy;
import bg.spotyourslot.booking.AppointmentCalendarRecords.ClosureKind;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CustomerContactView;
import bg.spotyourslot.booking.AppointmentCalendarRecords.CustomerHistoryPage;
import bg.spotyourslot.booking.AppointmentCalendarRecords.DetailLine;
import bg.spotyourslot.booking.AppointmentCalendarRecords.HistoryItem;
import bg.spotyourslot.booking.AppointmentCalendarRecords.ServiceSummary;
import bg.spotyourslot.booking.AppointmentCalendarRecords.Source;
import bg.spotyourslot.booking.AppointmentCalendarRecords.Status;
import bg.spotyourslot.booking.AppointmentCalendarRecords.TimeInterval;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.DetailRow;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.HistoryRow;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.LineRow;
import bg.spotyourslot.booking.infrastructure.AppointmentReadStore.ListRow;
import bg.spotyourslot.business.BusinessLifecycleAccess.LifecycleStatus;
import bg.spotyourslot.business.BusinessScheduleContextAccess;
import bg.spotyourslot.business.BusinessScheduleContextAccess.BusinessScheduleContext;
import bg.spotyourslot.customer.CustomerDirectory;
import bg.spotyourslot.customer.CustomerDirectory.CustomerContact;
import bg.spotyourslot.customer.CustomerReferenceAccess;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess;
import bg.spotyourslot.identity.SelectedBusinessOwnerAccess.Authorization;
import bg.spotyourslot.identity.SelectedBusinessRequired;
import bg.spotyourslot.scheduling.WorkingTimeOverview;
import bg.spotyourslot.scheduling.WorkingTimeRecords.StaffWorkingTime;
import bg.spotyourslot.scheduling.WorkingTimeRecords.WorkingTime;
import bg.spotyourslot.workforce.StaffCalendarAccess;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link AppointmentCalendar} (ADR-0028 sections 11 and 12). Every operation is one
 * read-only {@code REPEATABLE READ} transaction. Its first statement (the Business context) fixes
 * the snapshot, and every later statement (the owner check, the reference checks, the count, the
 * rows, the Service lines, the Customer names or contact, and the working-time facts) joins it, so a
 * commit by another transaction while the operation runs can never produce a count, rows, or
 * metadata from different moments. The service takes no lock, writes nothing, and never logs.
 *
 * <p><b>Maximum statements per operation</b> (each bounded and independent of the number of
 * Appointments, Services, Customers, and StaffMembers; an empty result skips the dependent
 * reads, so the maximum is reached only when something is found):
 * <ul>
 *   <li>calendar: 7 (context, owner, Appointments, lines, Customer names, StaffMembers, exceptions);
 *       5 when the window is empty; with a StaffMember filter the StaffMember read comes first;
 *   <li>agenda: 6 (context, owner, count, page, lines, names), 7 with a StaffMember filter (one more
 *       existence read); 3 (4 with a filter) when the window is empty or the page is past the end;
 *   <li>detail: 5 (context, owner, row, lines, Customer contact); 3 when missing;
 *   <li>Customer history: 6 (context, owner, Customer reference, count, page, lines); 4 when the
 *       Customer has no Appointments or the page is past the end.
 * </ul>
 *
 * <p>Failures are the typed exceptions of the contract; anything unexpected becomes a fixed
 * {@link Failure} without a cause.
 */
@Service
public class AppointmentCalendarService implements AppointmentCalendar {
    static final int DEFAULT_PAGE_SIZE = 10;
    static final Set<Integer> PAGE_SIZES = Set.of(10, 25, 50);
    static final int MAX_DATES = 31;
    static final LocalDate FIRST_SUPPORTED_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate LAST_SUPPORTED_DATE = LocalDate.of(2100, 12, 31);

    private final BusinessScheduleContextAccess businesses;
    private final SelectedBusinessOwnerAccess owners;
    private final AppointmentReadStore store;
    private final CustomerDirectory customers;
    private final CustomerReferenceAccess customerReferences;
    private final StaffCalendarAccess staff;
    private final WorkingTimeOverview workingTime;

    public AppointmentCalendarService(
            BusinessScheduleContextAccess businesses,
            SelectedBusinessOwnerAccess owners,
            AppointmentReadStore store,
            CustomerDirectory customers,
            CustomerReferenceAccess customerReferences,
            StaffCalendarAccess staff,
            WorkingTimeOverview workingTime) {
        this.businesses = businesses;
        this.owners = owners;
        this.store = store;
        this.customers = customers;
        this.customerReferences = customerReferences;
        this.staff = staff;
        this.workingTime = workingTime;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public CalendarView calendar(
            AuthenticatedBusinessContext context, LocalDate from, LocalDate to, UUID staffMemberIdOrNull) {
        return guarded(() -> {
            Access access = authorize(context);
            requireWindow(from, to);
            ZoneId zone = access.zone();
            Instant start = from.atStartOfDay(zone).toInstant();
            Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
            UUID businessId = access.businessId();

            WorkingTime working;
            List<ListRow> rows;
            if (staffMemberIdOrNull != null) {
                // An explicit filter may select an inactive StaffMember; missing and foreign are one 404.
                working = workingTime.overview(businessId, zone, from, to, staffMemberIdOrNull, Set.of());
                if (working.staffMembers().isEmpty()) {
                    throw new StaffMemberNotFound();
                }
                rows = store.findWindow(businessId, start, end, staffMemberIdOrNull);
            } else {
                rows = store.findWindow(businessId, start, end, null);
                Set<UUID> withAppointments = new HashSet<>();
                rows.forEach(row -> withAppointments.add(row.staffMemberId()));
                working = workingTime.overview(businessId, zone, from, to, null, withAppointments);
            }
            List<CalendarAppointment> appointments = appointments(businessId, rows);
            return new CalendarView(
                    access.status(),
                    zone,
                    from,
                    to,
                    working.days().stream()
                            .map(day -> new CalendarDay(day.date(), day.start(), day.end()))
                            .toList(),
                    working.staffMembers().stream().map(AppointmentCalendarService::staffMember).toList(),
                    appointments);
        });
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public AgendaPage agenda(
            AuthenticatedBusinessContext context,
            LocalDate from,
            LocalDate to,
            UUID staffMemberIdOrNull,
            Integer page,
            Integer size) {
        return guarded(() -> {
            Access access = authorize(context);
            requireWindow(from, to);
            int validatedPage = page == null ? 0 : page;
            int validatedSize = size == null ? DEFAULT_PAGE_SIZE : size;
            requirePaging(validatedPage, validatedSize);
            ZoneId zone = access.zone();
            Instant start = from.atStartOfDay(zone).toInstant();
            Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
            UUID businessId = access.businessId();

            if (staffMemberIdOrNull != null
                    && staff.find(businessId, staffMemberIdOrNull, false).isEmpty()) {
                throw new StaffMemberNotFound();
            }
            long total = store.countWindow(businessId, start, end, staffMemberIdOrNull);
            long offset = (long) validatedPage * validatedSize;
            List<CalendarAppointment> items = List.of();
            if (total > offset) {
                items = appointments(businessId, store.findWindowPage(
                        businessId, start, end, staffMemberIdOrNull, offset, validatedSize));
            }
            return new AgendaPage(
                    access.status(), zone, from, to, items, validatedPage, validatedSize, total);
        });
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public AppointmentDetail detail(AuthenticatedBusinessContext context, UUID appointmentId) {
        return guarded(() -> {
            Access access = authorize(context);
            if (appointmentId == null) {
                throw new InvalidInput();
            }
            UUID businessId = access.businessId();
            DetailRow row = store.findDetail(businessId, appointmentId).orElseThrow(AppointmentNotFound::new);
            List<LineRow> lines = store.findLines(businessId, List.of(row.id()));
            CustomerContact contact = customers.findContact(businessId, row.customerId())
                    .orElseThrow(Failure::new);
            Status status = Status.valueOf(row.status());
            Cancellation cancellation = status == Status.CANCELLED
                    ? new Cancellation(row.cancelledAt(), CancelledBy.valueOf(row.cancelledBy()))
                    : null;
            return new AppointmentDetail(
                    access.status(),
                    access.zone(),
                    row.id(),
                    row.publicReference(),
                    status,
                    Source.valueOf(row.source()),
                    row.startAt(),
                    row.endAt(),
                    row.staffMemberId(),
                    row.staffDisplayName(),
                    new CustomerContactView(
                            contact.id(), contact.displayName(), contact.phone(), contact.email()),
                    lines.stream()
                            .map(line -> new DetailLine(
                                    line.position(), line.serviceName(), line.durationMinutes(), line.priceEur()))
                            .toList(),
                    row.durationMinutes(),
                    row.priceEur(),
                    row.customerNote(),
                    row.internalNote(),
                    cancellation,
                    row.version(),
                    row.createdAt(),
                    row.updatedAt());
        });
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public CustomerHistoryPage customerHistory(
            AuthenticatedBusinessContext context, UUID customerId, Integer page, Integer size) {
        return guarded(() -> {
            Access access = authorize(context);
            if (customerId == null) {
                throw new InvalidInput();
            }
            int validatedPage = page == null ? 0 : page;
            int validatedSize = size == null ? DEFAULT_PAGE_SIZE : size;
            requirePaging(validatedPage, validatedSize);
            UUID businessId = access.businessId();
            customerReferences.find(businessId, customerId).orElseThrow(CustomerNotFound::new);

            long total = store.countHistory(businessId, customerId);
            long offset = (long) validatedPage * validatedSize;
            List<HistoryItem> items = List.of();
            if (total > offset) {
                List<HistoryRow> rows = store.findHistoryPage(businessId, customerId, offset, validatedSize);
                Map<UUID, List<String>> names = new LinkedHashMap<>();
                for (LineRow line : store.findLines(
                        businessId, rows.stream().map(HistoryRow::id).toList())) {
                    names.computeIfAbsent(line.appointmentId(), id -> new ArrayList<>())
                            .add(line.serviceName());
                }
                items = rows.stream()
                        .map(row -> new HistoryItem(
                                row.id(),
                                Status.valueOf(row.status()),
                                Source.valueOf(row.source()),
                                row.startAt(),
                                row.endAt(),
                                row.staffDisplayName(),
                                names.getOrDefault(row.id(), List.of())))
                        .toList();
            }
            return new CustomerHistoryPage(access.zone(), items, validatedPage, validatedSize, total);
        });
    }

    // ---- shared steps ----------------------------------------------------------------------------

    private record Access(UUID businessId, BusinessStatus status, ZoneId zone) {
    }

    /**
     * The Business and the user come only from the authenticated context. The first statement is
     * the Business context read (it fixes the snapshot and supplies the lifecycle and timezone);
     * the owner check follows. DRAFT, ACTIVE, and SUSPENDED all pass.
     */
    private Access authorize(AuthenticatedBusinessContext context) {
        if (context == null || context.userId() == null) {
            throw new AccessDenied();
        }
        UUID businessId = context.selectedBusinessId().orElseThrow(SelectedBusinessRequired::new);
        BusinessScheduleContext business = businesses.findScheduleContext(businessId)
                .orElseThrow(AccessDenied::new);
        if (owners.authorize(context.userId(), businessId) != Authorization.GRANTED) {
            throw new AccessDenied();
        }
        return new Access(businessId, status(business.status()), business.timezone());
    }

    private static BusinessStatus status(LifecycleStatus status) {
        return BusinessStatus.valueOf(status.name());
    }

    private static void requireWindow(LocalDate from, LocalDate to) {
        if (from == null
                || to == null
                || from.isBefore(FIRST_SUPPORTED_DATE)
                || to.isAfter(LAST_SUPPORTED_DATE)
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DATES) {
            throw new InvalidInput();
        }
    }

    private static void requirePaging(int page, int size) {
        if (page < 0 || !PAGE_SIZES.contains(size)) {
            throw new InvalidInput();
        }
    }

    /**
     * Turns rows into calendar rows with two further bulk reads (the lines and the Customer names)
     * whatever the number of rows; nothing is read for an empty list.
     */
    private List<CalendarAppointment> appointments(UUID businessId, List<ListRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ServiceSummary>> services = new LinkedHashMap<>();
        for (LineRow line : store.findLines(businessId, rows.stream().map(ListRow::id).toList())) {
            services.computeIfAbsent(line.appointmentId(), id -> new ArrayList<>())
                    .add(new ServiceSummary(line.serviceName(), line.durationMinutes()));
        }
        Set<UUID> customerIds = new LinkedHashSet<>();
        rows.forEach(row -> customerIds.add(row.customerId()));
        Map<UUID, String> names = customers.displayNames(businessId, customerIds);
        List<CalendarAppointment> result = new ArrayList<>(rows.size());
        for (ListRow row : rows) {
            String customerName = names.get(row.customerId());
            List<ServiceSummary> lines = services.get(row.id());
            if (customerName == null || lines == null) {
                // A stored Appointment always has its Customer and its lines (foreign keys, V13 trigger).
                throw new Failure();
            }
            result.add(new CalendarAppointment(
                    row.id(),
                    Status.valueOf(row.status()),
                    Source.valueOf(row.source()),
                    row.startAt(),
                    row.endAt(),
                    row.staffMemberId(),
                    row.staffDisplayName(),
                    row.customerId(),
                    customerName,
                    row.durationMinutes(),
                    lines));
        }
        return List.copyOf(result);
    }

    private static CalendarStaffMember staffMember(StaffWorkingTime member) {
        return new CalendarStaffMember(
                member.id(),
                member.displayName(),
                member.active(),
                member.workingIntervals().stream()
                        .map(interval -> new TimeInterval(interval.start(), interval.end()))
                        .toList(),
                member.closures().stream()
                        .map(closure -> new CalendarClosure(
                                closure.date(), ClosureKind.valueOf(closure.kind().name())))
                        .toList());
    }

    /** Typed contract failures and the identity selection failure pass; anything else is sanitized. */
    private static <T> T guarded(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AppointmentCalendarException | SelectedBusinessRequired known) {
            throw known;
        } catch (RuntimeException unexpected) {
            throw new Failure();
        }
    }
}
