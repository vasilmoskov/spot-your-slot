package bg.spotyourslot.booking.domain;

import bg.spotyourslot.shared.contact.ContactEmailPolicy;
import bg.spotyourslot.shared.contact.ContactPhoneNumbers;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The complete, validated, normalized guest booking request (ADR-0024): exactly the values the
 * request fingerprint covers, plus the attempt identifier, which is deliberately not fingerprinted.
 * The Business is resolved from the public slug inside the booking transaction and is therefore not
 * part of this record; it joins the fingerprint input when the fingerprint is computed.
 *
 * <p>Normalization decides what "the same request" means, so two requests that differ only in
 * cosmetic ways (surrounding or repeated whitespace, phone formatting, email case, line-break
 * style, edge whitespace of the note) are equal, and any other difference is not:
 *
 * <ul>
 *   <li>the display name, phone, and email follow the shared contact policy that the Customer
 *       module applies (NFKC, whitespace collapsing, compact E.164, lowercase email), so the
 *       fingerprint of a replay never disagrees with what Customer matching would see;
 *   <li>the start must be a whole-second instant, because the fingerprint encodes epoch seconds
 *       and a sub-second difference would otherwise be invisible to it;
 *   <li>the note has {@code CRLF} and {@code CR} replaced by {@code LF}, surrounding Unicode white
 *       space removed, and a blank note is absent; no further normalization changes its text.
 * </ul>
 *
 * <p>The record holds personal data, so {@link #toString()} is redacted.
 */
public final class NormalizedBookingRequest {
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 200;
    public static final int MAX_NOTE_CODE_POINTS = 500;
    public static final int MAX_SERVICES = 5;
    public static final int MAX_VISIT_MINUTES = 480;
    private static final int MIN_SERVICE_MINUTES = 1;
    /** The largest value of {@code numeric(12,2)} in cents. */
    private static final long MAX_SERVICE_PRICE_CENTS = 999_999_999_999L;

    /** NUL and the control characters PostgreSQL or the Appointment note rule rejects; tab and LF stay. */
    private static final Pattern FORBIDDEN_NOTE_CONTROL = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");
    private static final long MIN_EPOCH_SECOND = -62_135_596_800L;
    private static final long MAX_EPOCH_SECOND = 253_402_300_799L;

    private final BookingAttemptId attemptId;
    private final List<RequestedService> services;
    private final UUID requestedStaffMemberId;
    private final Instant start;
    private final String customerName;
    private final String customerPhone;
    private final String customerEmail;
    private final String note;

    private NormalizedBookingRequest(
            BookingAttemptId attemptId,
            List<RequestedService> services,
            UUID requestedStaffMemberId,
            Instant start,
            String customerName,
            String customerPhone,
            String customerEmail,
            String note) {
        this.attemptId = attemptId;
        this.services = services;
        this.requestedStaffMemberId = requestedStaffMemberId;
        this.start = start;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.customerEmail = customerEmail;
        this.note = note;
    }

    /**
     * The legacy single-Service shape: one Service without reviewed terms.
     *
     * @param requestedStaffMemberId the guest's preference; {@code null} is "no preference" and is
     *        never replaced by the StaffMember the server later assigns
     * @throws InvalidBookingRequest naming every invalid field and no value
     */
    public static NormalizedBookingRequest normalize(
            String attemptId,
            UUID serviceId,
            UUID requestedStaffMemberId,
            Instant start,
            String displayName,
            String phone,
            String email,
            String note) {
        Objects.requireNonNull(serviceId, "serviceId");
        return normalize(
                attemptId,
                List.of(new RequestedService(serviceId, null)),
                requestedStaffMemberId,
                start,
                displayName,
                phone,
                email,
                note);
    }

    /**
     * One to five distinct Services (ADR-0027), either all with reviewed terms (the new shape) or
     * all without (the legacy shape). The result holds them in the request identity order: ascending
     * unsigned UUID, whatever order the caller listed them in.
     *
     * @throws InvalidBookingRequest naming every invalid field and no value
     */
    public static NormalizedBookingRequest normalize(
            String attemptId,
            List<RequestedService> requested,
            UUID requestedStaffMemberId,
            Instant start,
            String displayName,
            String phone,
            String email,
            String note) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(start, "start");
        Set<RequestField> invalid = EnumSet.noneOf(RequestField.class);
        List<RequestedService> ordered = orderedServices(requested);
        if (ordered == null) {
            invalid.add(RequestField.SERVICES);
        }

        Optional<BookingAttemptId> parsedAttempt = BookingAttemptId.parse(attemptId);
        if (parsedAttempt.isEmpty()) {
            invalid.add(RequestField.ATTEMPT_ID);
        }
        if (start.getNano() != 0
                || start.getEpochSecond() < MIN_EPOCH_SECOND
                || start.getEpochSecond() > MAX_EPOCH_SECOND) {
            invalid.add(RequestField.START);
        }

        String canonicalName = ContactTextCanonicalizer.canonicalDisplayName(displayName);
        if (canonicalName == null
                || canonicalName.isEmpty()
                || canonicalName.codePointCount(0, canonicalName.length()) > MAX_DISPLAY_NAME_CODE_POINTS) {
            invalid.add(RequestField.DISPLAY_NAME);
        }

        String trimmedPhone = ContactTextCanonicalizer.canonicalTrimmed(phone);
        Optional<String> canonicalPhone = trimmedPhone == null
                ? Optional.empty()
                : ContactPhoneNumbers.canonicalize(trimmedPhone);
        if (trimmedPhone != null && canonicalPhone.isEmpty()) {
            invalid.add(RequestField.PHONE);
        }
        String trimmedEmail = ContactTextCanonicalizer.canonicalTrimmed(email);
        Optional<String> canonicalEmail = trimmedEmail == null
                ? Optional.empty()
                : ContactEmailPolicy.canonicalize(trimmedEmail);
        if (trimmedEmail != null && canonicalEmail.isEmpty()) {
            invalid.add(RequestField.EMAIL);
        }
        if (trimmedPhone == null && trimmedEmail == null) {
            invalid.add(RequestField.CONTACT);
        }

        String normalizedNote = normalizeNote(note);
        if (normalizedNote != null && !isValidNote(normalizedNote)) {
            invalid.add(RequestField.NOTE);
        }

        if (!invalid.isEmpty()) {
            throw new InvalidBookingRequest(invalid);
        }
        return new NormalizedBookingRequest(
                parsedAttempt.orElseThrow(),
                ordered,
                requestedStaffMemberId,
                start,
                canonicalName,
                canonicalPhone.orElse(null),
                canonicalEmail.orElse(null),
                normalizedNote);
    }

    /**
     * The services in ascending unsigned UUID order, or {@code null} when the selection is not
     * valid: not one to five Services, a repeated Service, reviewed terms on only some of them,
     * a reviewed duration outside 1..480, a reviewed price outside 0..999 999 999 999 cents, or
     * reviewed durations that sum to more than the 480-minute visit ceiling (a visit nobody could
     * have reviewed).
     */
    private static List<RequestedService> orderedServices(List<RequestedService> requested) {
        if (requested.isEmpty() || requested.size() > MAX_SERVICES) {
            return null;
        }
        Set<UUID> seen = new HashSet<>();
        int reviewedCount = 0;
        long reviewedMinutes = 0;
        for (RequestedService service : requested) {
            Objects.requireNonNull(service, "service");
            if (!seen.add(service.serviceId())) {
                return null;
            }
            RequestedService.ReviewedTerms terms = service.reviewed();
            if (terms == null) {
                continue;
            }
            reviewedCount++;
            if (terms.durationMinutes() < MIN_SERVICE_MINUTES
                    || terms.durationMinutes() > MAX_VISIT_MINUTES
                    || terms.priceCents() < 0
                    || terms.priceCents() > MAX_SERVICE_PRICE_CENTS) {
                return null;
            }
            reviewedMinutes += terms.durationMinutes();
        }
        if (reviewedCount != 0 && reviewedCount != requested.size()) {
            return null;
        }
        if (reviewedMinutes > MAX_VISIT_MINUTES) {
            return null;
        }
        List<RequestedService> ordered = new ArrayList<>(requested);
        ordered.sort(Comparator.comparing(RequestedService::serviceId, UnsignedUuidOrder.INSTANCE));
        return List.copyOf(ordered);
    }

    /** Line breaks to LF, surrounding Unicode white space removed, blank to absent. */
    static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String unified = note.replace("\r\n", "\n").replace('\r', '\n').strip();
        return unified.isEmpty() ? null : unified;
    }

    private static boolean isValidNote(String note) {
        return note.codePointCount(0, note.length()) <= MAX_NOTE_CODE_POINTS
                && !FORBIDDEN_NOTE_CONTROL.matcher(note).find()
                && !hasUnpairedSurrogate(note);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    public BookingAttemptId attemptId() {
        return attemptId;
    }

    /** The requested Services in ascending unsigned UUID order (the request identity order). */
    public List<RequestedService> services() {
        return services;
    }

    /** Whether the request carries the guest's reviewed terms (the new shape). */
    public boolean reviewed() {
        return services.getFirst().reviewed() != null;
    }

    /**
     * The only Service of a single-Service request.
     *
     * @throws IllegalStateException when the request selects several Services
     */
    public UUID serviceId() {
        if (services.size() != 1) {
            throw new IllegalStateException("The request selects several Services");
        }
        return services.getFirst().serviceId();
    }

    /** The guest's preference, or {@code null} for "no preference". */
    public UUID requestedStaffMemberId() {
        return requestedStaffMemberId;
    }

    public Instant start() {
        return start;
    }

    public String customerName() {
        return customerName;
    }

    /** The canonical compact E.164 phone, or {@code null} when absent. */
    public String customerPhone() {
        return customerPhone;
    }

    /** The canonical lowercase email, or {@code null} when absent. */
    public String customerEmail() {
        return customerEmail;
    }

    /** The normalized note, or {@code null} when absent. */
    public String note() {
        return note;
    }

    @Override
    public String toString() {
        return "NormalizedBookingRequest[redacted]";
    }
}
