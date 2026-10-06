package bg.spotyourslot.booking.domain;

import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Appointment invariants shared by {@link NewAppointment} and {@link Appointment}. They mirror
 * the {@code V11} constraints so a violation is found before a statement is sent; PostgreSQL stays
 * the authority. Every failure uses a fixed message that contains no submitted value.
 */
final class AppointmentRules {
    static final int MIN_DURATION_MINUTES = 1;
    static final int MAX_DURATION_MINUTES = 480;
    static final int MAX_NAME_CODE_POINTS = 200;
    static final int MAX_NOTE_CODE_POINTS = 500;
    static final int MAX_TIMEZONE_LENGTH = 100;
    static final int PRICE_SCALE = 2;
    static final int MAX_PRICE_INTEGER_DIGITS = 10;
    /** 0001-01-01T00:00:00Z, so every instant and its end convert to a PostgreSQL timestamptz. */
    static final long MIN_EPOCH_SECOND = -62_135_596_800L;
    /** 9999-12-31T23:59:59Z. */
    static final long MAX_EPOCH_SECOND = 253_402_300_799L;
    static final int PUBLIC_REFERENCE_LENGTH = 10;

    private static final Set<String> ZONE_IDS = Set.copyOf(ZoneId.getAvailableZoneIds());
    private static final Pattern PUBLIC_REFERENCE = Pattern.compile("[0-9A-HJKMNP-TV-Z]{10}");
    private static final Pattern NOTE_CONTROL = Pattern.compile("[\\x01-\\x08\\x0B-\\x1F\\x7F]");

    private AppointmentRules() {
    }

    static void requireInstant(Instant value) {
        if (value.getEpochSecond() < MIN_EPOCH_SECOND || value.getEpochSecond() > MAX_EPOCH_SECOND) {
            throw new IllegalArgumentException("Appointment instant is out of range");
        }
    }

    static void requireDuration(int minutes) {
        if (minutes < MIN_DURATION_MINUTES || minutes > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException("Appointment duration is out of range");
        }
    }

    /** Returns the price with the stored scale of two, rejecting rounding and excess digits. */
    static BigDecimal requirePrice(BigDecimal price) {
        if (price.signum() < 0) {
            throw new IllegalArgumentException("Appointment price must not be negative");
        }
        BigDecimal scaled;
        try {
            scaled = price.setScale(PRICE_SCALE);
        } catch (ArithmeticException excessFraction) {
            throw new IllegalArgumentException("Appointment price has too many fraction digits");
        }
        if (scaled.precision() - scaled.scale() > MAX_PRICE_INTEGER_DIGITS) {
            throw new IllegalArgumentException("Appointment price is too large");
        }
        return scaled;
    }

    static void requireTimezone(String timezone) {
        if (timezone.isEmpty()
                || timezone.length() > MAX_TIMEZONE_LENGTH
                || !timezone.equals(timezone.strip())
                || !ZONE_IDS.contains(timezone)) {
            throw new IllegalArgumentException("Appointment timezone is invalid");
        }
    }

    /**
     * A snapshot name is copied from the locked Service or StaffMember row, so it must already be
     * in the canonical form those rows store, which is also the form {@code V11} enforces: Unicode
     * NFKC, approved whitespace only as single internal spaces, and no edge whitespace. The
     * repository's existing canonicalization policy decides, and a name that differs from its own
     * canonical form is rejected, never silently changed, because it would not be the source fact.
     */
    static void requireSnapshotName(String name) {
        if (name.isEmpty()
                || name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS
                || !name.equals(ContactTextCanonicalizer.canonicalDisplayName(name))) {
            throw new IllegalArgumentException("Appointment snapshot name is invalid");
        }
    }

    static void requireNote(String note) {
        if (note == null) {
            return;
        }
        if (note.isEmpty()
                || note.codePointCount(0, note.length()) > MAX_NOTE_CODE_POINTS
                || !note.equals(trimNote(note))
                || NOTE_CONTROL.matcher(note).find()) {
            throw new IllegalArgumentException("Appointment note is invalid");
        }
    }

    static void requirePublicReference(String reference) {
        if (!PUBLIC_REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("Appointment reference is invalid");
        }
    }

    private static String trimNote(String note) {
        int start = 0;
        int end = note.length();
        while (start < end && isNoteWhitespace(note.charAt(start))) {
            start++;
        }
        while (end > start && isNoteWhitespace(note.charAt(end - 1))) {
            end--;
        }
        return note.substring(start, end);
    }

    private static boolean isNoteWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }
}
