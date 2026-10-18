package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentRescheduleOptions;
import bg.spotyourslot.booking.AppointmentRescheduleOptionsException.InvalidInput;
import bg.spotyourslot.calendar.web.BusinessAppointmentRescheduleOptionsHttpRecords.RescheduleAvailabilityResponse;
import bg.spotyourslot.calendar.web.BusinessAppointmentRescheduleOptionsHttpRecords.RescheduleOptionsResponse;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two owner-only reads behind the reschedule and reassignment form (ADR-0028 section 4):
 * {@code GET /api/business/appointments/{appointmentId}/reschedule-options} (the current and the
 * replacement StaffMembers with the stored duration) and
 * {@code GET /api/business/appointments/{appointmentId}/reschedule-options/availability} (the offered
 * starts of one explicit StaffMember on one Business-local date). The Appointment is the only input
 * that names a record; no duration, price, Service, ignored Appointment, or Business identifier is
 * read from the request. The Business comes only from the authenticated context.
 */
@RestController
@RequestMapping("/api/business/appointments/{appointmentId}/reschedule-options")
public class BusinessAppointmentRescheduleOptionsController {
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final AppointmentRescheduleOptions options;

    public BusinessAppointmentRescheduleOptionsController(AppointmentRescheduleOptions options) {
        this.options = options;
    }

    @GetMapping
    public RescheduleOptionsResponse options(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable String appointmentId) {
        return RescheduleOptionsResponse.from(options.options(context, uuid(appointmentId)));
    }

    @GetMapping("/availability")
    public RescheduleAvailabilityResponse availability(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @PathVariable String appointmentId,
            @RequestParam MultiValueMap<String, String> parameters) {
        UUID appointment = uuid(appointmentId);
        UUID staff = uuid(single(parameters, "staffMemberId"));
        LocalDate parsed = BusinessCalendarHttpRecords.parseDate(single(parameters, "date"));
        if (parsed == null) {
            throw new InvalidInput();
        }
        return RescheduleAvailabilityResponse.from(options.availability(context, appointment, staff, parsed));
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null || values.size() != 1) {
            throw new InvalidInput();
        }
        return values.getFirst();
    }

    /** The canonical 8-4-4-4-12 form only, so no lenient short or signed form names a record. */
    private static UUID uuid(String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw new InvalidInput();
        }
        return UUID.fromString(value);
    }
}
