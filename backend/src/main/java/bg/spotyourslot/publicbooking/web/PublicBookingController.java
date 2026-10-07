package bg.spotyourslot.publicbooking.web;

import bg.spotyourslot.booking.BookingField;
import bg.spotyourslot.booking.BookingResult;
import bg.spotyourslot.booking.GuestBooking;
import bg.spotyourslot.booking.GuestBookingRequest;
import bg.spotyourslot.booking.PublicBookingRateLimiter;
import bg.spotyourslot.booking.PublicBookingRateLimiter.Admission;
import bg.spotyourslot.publicbooking.application.PublicAvailabilityService;
import bg.spotyourslot.publicbooking.web.PublicBookingHttpRecords.AvailabilityResponse;
import bg.spotyourslot.publicbooking.web.PublicBookingHttpRecords.BookingOptionsResponse;
import bg.spotyourslot.publicbooking.web.PublicBookingHttpRecords.BookingResponse;
import bg.spotyourslot.publicbooking.web.PublicBookingRequestParser.ParsedBooking;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;


/**
 * The unauthenticated guest booking routes (ADR-0026). The controller reads no identity, session,
 * cookie, or header: the Business comes only from the slug, and every value is validated by the
 * orchestration. It contains no booking orchestration, Customer matching, availability
 * calculation, or retry logic. The per-address limits are charged by
 * {@link PublicBookingRateLimitInterceptor} before the body is parsed; the contact limit is
 * charged here as soon as the body is available.
 */
@RestController
@RequestMapping("/api/public/businesses/{slug}")
public class PublicBookingController {
    /** Advisory delay before repeating the same attempt after a 503; not a guarantee. */
    static final long RETRY_AFTER_SECONDS = 2;

    private final PublicAvailabilityService availability;
    private final GuestBooking booking;
    private final PublicBookingRateLimiter limiter;

    public PublicBookingController(
            PublicAvailabilityService availability,
            GuestBooking booking,
            PublicBookingRateLimiter limiter) {
        this.availability = availability;
        this.booking = booking;
        this.limiter = limiter;
    }

    @GetMapping("/services/{serviceId}/booking-options")
    public BookingOptionsResponse bookingOptions(
            @PathVariable String slug, @PathVariable UUID serviceId) {
        return BookingOptionsResponse.from(availability.bookingOptions(slug, serviceId));
    }

    @GetMapping("/services/{serviceId}/availability")
    public AvailabilityResponse availability(
            @PathVariable String slug,
            @PathVariable UUID serviceId,
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "staffMemberId", required = false) UUID staffMemberId) {
        return AvailabilityResponse.from(
                availability.availability(slug, serviceId, parseDate(date), staffMemberId));
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> book(@PathVariable String slug, @RequestBody JsonNode body) {
        ParsedBooking request = PublicBookingRequestParser.parse(body);

        Admission admission = limiter.admitBookingContact(slug, request.phone(), request.email());
        if (!admission.allowed()) {
            throw new PublicRequestRateLimited(admission.retryAfterSeconds());
        }

        BookingResult result = booking.book(new GuestBookingRequest(
                slug,
                request.attemptId(),
                request.serviceId(),
                request.staffMemberId(),
                request.start(),
                request.displayName(),
                request.phone(),
                request.email(),
                request.note()));
        return respond(result);
    }

    private static ResponseEntity<?> respond(BookingResult result) {
        return switch (result) {
            case BookingResult.Created created -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(BookingResponse.from(created.appointment()));
            case BookingResult.Replayed replayed -> ResponseEntity.ok(
                    BookingResponse.from(replayed.appointment()));
            case BookingResult.BusinessUnavailable unavailable ->
                    failure(PublicProblems.businessUnavailable());
            case BookingResult.InvalidRequest invalid ->
                    failure(PublicProblems.validation(fieldErrors(invalid.fields())));
            case BookingResult.ServiceUnavailable unavailable ->
                    failure(PublicProblems.serviceUnavailable());
            case BookingResult.StaffMemberUnavailable unavailable ->
                    failure(PublicProblems.staffUnavailable());
            case BookingResult.SlotUnavailable unavailable ->
                    failure(PublicProblems.slotUnavailable());
            case BookingResult.IdentityConflict conflict ->
                    failure(PublicProblems.notCompletedOnline());
            case BookingResult.AttemptMismatch mismatch ->
                    failure(PublicProblems.attemptMismatch());
            case BookingResult.TemporarilyUnavailable rollback ->
                    retryable(PublicProblems.temporarilyUnavailable());
            case BookingResult.OutcomeUncertain uncertain ->
                    retryable(PublicProblems.outcomeUncertain());
        };
    }

    private static ResponseEntity<ProblemDetail> failure(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ResponseEntity<ProblemDetail> retryable(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(RETRY_AFTER_SECONDS))
                .body(problem);
    }

    /**
     * Only a field the guest can correct is named; the attempt identifier and the start are
     * produced by the application and stay generic.
     */
    private static Map<String, String> fieldErrors(Set<BookingField> fields) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (BookingField field : BookingField.values()) {
            if (!fields.contains(field)) {
                continue;
            }
            switch (field) {
                case DISPLAY_NAME -> errors.put(
                        "displayName", "Въведете име до 200 знака.");
                case PHONE -> errors.put("phone", "Въведеният телефонен номер не е валиден.");
                case EMAIL -> errors.put("email", "Въведеният имейл адрес не е валиден.");
                case CONTACT -> errors.put("contact", "Въведете телефон или имейл.");
                case NOTE -> errors.put(
                        "note", "Бележката може да съдържа най-много 500 знака.");
                case ATTEMPT_ID, START -> {
                }
            }
        }
        return errors;
    }

    private static LocalDate parseDate(String date) {
        if (date == null) {
            throw new InvalidPublicRequest();
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException exception) {
            throw new InvalidPublicRequest();
        }
    }
}
