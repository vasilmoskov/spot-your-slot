package bg.spotyourslot.calendar.web;

import bg.spotyourslot.booking.AppointmentCreationOptions;
import bg.spotyourslot.booking.AppointmentCreationOptionsException.InvalidInput;
import bg.spotyourslot.calendar.web.BusinessAppointmentOptionsHttpRecords.CreationAvailabilityResponse;
import bg.spotyourslot.calendar.web.BusinessAppointmentOptionsHttpRecords.CreationOptionsResponse;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two owner-only reads behind the manual Appointment create form (ADR-0028 section 4):
 * {@code GET /api/business/appointment-options} (the StaffMembers who may perform the selected
 * Services, and their summed duration) and {@code GET /api/business/appointment-options/availability}
 * (the offered starts of one explicit StaffMember on one Business-local date). The selection is the
 * repeated query parameter {@code serviceId}, one canonical UUID per occurrence, at most five, no
 * duplicate, order irrelevant; it never holds a name, a price, or personal data. The Business comes
 * only from the authenticated context.
 */
@RestController
@RequestMapping("/api/business/appointment-options")
public class BusinessAppointmentOptionsController {
    static final String SERVICE_PARAMETER = "serviceId";
    private static final int MAX_SERVICES = 5;
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final AppointmentCreationOptions options;

    public BusinessAppointmentOptionsController(AppointmentCreationOptions options) {
        this.options = options;
    }

    @GetMapping
    public CreationOptionsResponse options(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam MultiValueMap<String, String> parameters) {
        return CreationOptionsResponse.from(
                options.options(context, selection(parameters.get(SERVICE_PARAMETER))));
    }

    @GetMapping("/availability")
    public CreationAvailabilityResponse availability(
            @CurrentSecurityContext(expression = "authentication")
                    AuthenticatedBusinessContext context,
            @RequestParam MultiValueMap<String, String> parameters) {
        List<UUID> selection = selection(parameters.get(SERVICE_PARAMETER));
        UUID staff = uuid(single(parameters, "staffMemberId"));
        LocalDate parsed = BusinessCalendarHttpRecords.parseDate(single(parameters, "date"));
        if (parsed == null) {
            throw new InvalidInput();
        }
        return CreationAvailabilityResponse.from(options.availability(context, selection, staff, parsed));
    }

    /** The raw, unsplit values: a comma list stays one malformed value, never several identifiers. */
    private static List<UUID> selection(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_SERVICES) {
            throw new InvalidInput();
        }
        List<UUID> selection = new ArrayList<>(values.size());
        Set<UUID> seen = new HashSet<>();
        for (String value : values) {
            UUID id = uuid(value);
            if (!seen.add(id)) {
                throw new InvalidInput();
            }
            selection.add(id);
        }
        return List.copyOf(selection);
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
