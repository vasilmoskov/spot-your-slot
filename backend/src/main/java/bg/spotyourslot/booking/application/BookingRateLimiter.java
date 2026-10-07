package bg.spotyourslot.booking.application;

import bg.spotyourslot.booking.PublicBookingRateLimiter;
import bg.spotyourslot.booking.application.FixedWindowCounters.Charge;
import bg.spotyourslot.booking.application.FixedWindowCounters.Decision;
import bg.spotyourslot.shared.contact.ContactEmailPolicy;
import bg.spotyourslot.shared.contact.ContactPhoneNumbers;
import bg.spotyourslot.shared.contact.ContactTextCanonicalizer;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The initial Booking-owned limiter of ADR-0026, finalized in Phase 5. Process-local fixed windows
 * over {@link FixedWindowCounters}.
 *
 * <table>
 *   <caption>Budgets per window (default 15 minutes)</caption>
 *   <tr><th>Budget</th><th>Key</th><th>Default</th></tr>
 *   <tr><td>booking, address and Business</td><td>address, slug</td><td>10</td></tr>
 *   <tr><td>booking, address aggregate</td><td>address</td><td>30</td></tr>
 *   <tr><td>booking, contact</td><td>slug, canonical identifier</td><td>5</td></tr>
 *   <tr><td>reads, address and Business</td><td>address, slug</td><td>300</td></tr>
 *   <tr><td>reads, address aggregate</td><td>address</td><td>600</td></tr>
 * </table>
 *
 * <p><b>Keys.</b> Every key is an HMAC-SHA-256 of its parts under a random per-process secret,
 * truncated to 128 bits. The raw address, slug, phone, and email are never stored, so the retained
 * state cannot be read back, and a low-entropy contact value cannot be recovered by a dictionary
 * because the secret never leaves the process. The secret is regenerated at every start (the state is
 * lost then anyway).
 *
 * <p><b>Address.</b> The servlet remote address is used. An IPv6 address is reduced to its /64
 * prefix, so one subscriber cannot multiply its budgets by rotating the interface identifier; an
 * unparsable value shares one bucket. <b>Slug.</b> The slug is stripped and lower-cased; a value that
 * is not a plausible slug (the repository grammar, at most 100 characters) shares one bucket, so
 * malformed requests create no per-value key. A well-formed unknown slug does create a key, but the
 * per-address aggregate budget bounds how many a single address can create per window.
 *
 * <p><b>Contacts.</b> Phone and email are canonicalized with the shared contact policies (the same
 * ones the booking fingerprint and Customer matching use) and each supplied identifier is charged
 * one unit independently and atomically, so alternating the other identifier cannot evade the budget.
 */
public final class BookingRateLimiter implements PublicBookingRateLimiter {
    private static final Pattern PLAUSIBLE_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final int MAX_SLUG_LENGTH = 100;
    private static final String HMAC = "HmacSHA256";
    private static final String MALFORMED_SLUG = "\u0000malformed";
    private static final String UNKNOWN_ADDRESS = "\u0000unknown";

    private final RateLimitSettings settings;
    private final FixedWindowCounters counters;
    private final byte[] secret;

    public BookingRateLimiter(Clock clock, RateLimitSettings settings, byte[] secret) {
        this.settings = Objects.requireNonNull(settings, "settings");
        if (Objects.requireNonNull(secret, "secret").length < 16) {
            throw new IllegalArgumentException("The limiter secret is too short");
        }
        this.secret = secret.clone();
        this.counters = new FixedWindowCounters(clock, settings.window(), settings.maxEntries());
    }

    @Override
    public Admission admitBookingRequest(String remoteAddress, String businessSlug) {
        String address = addressBucket(remoteAddress);
        String slug = slugBucket(businessSlug);
        return decide(List.of(
                new Charge(digest("booking-aggregate", address), settings.bookingPerAddress()),
                new Charge(
                        digest("booking-address-business", address, slug),
                        settings.bookingPerAddressAndBusiness())));
    }

    @Override
    public Admission admitAvailabilityRequest(String remoteAddress, String businessSlug) {
        String address = addressBucket(remoteAddress);
        String slug = slugBucket(businessSlug);
        return decide(List.of(
                new Charge(digest("read-aggregate", address), settings.availabilityPerAddress()),
                new Charge(
                        digest("read-address-business", address, slug),
                        settings.availabilityPerAddressAndBusiness())));
    }

    @Override
    public Admission admitBookingContact(String businessSlug, String phone, String email) {
        String slug = slugBucket(businessSlug);
        List<Charge> charges = new ArrayList<>(2);
        canonicalPhone(phone).ifPresent(value -> charges.add(
                new Charge(digest("booking-contact-phone", slug, value), settings.bookingPerContact())));
        canonicalEmail(email).ifPresent(value -> charges.add(
                new Charge(digest("booking-contact-email", slug, value), settings.bookingPerContact())));
        if (charges.isEmpty()) {
            return Admission.permitted();
        }
        return decide(charges);
    }

    /** The number of counters currently retained, for capacity tests and diagnostics. */
    public int retainedEntries() {
        return counters.retainedEntries();
    }

    private Admission decide(List<Charge> charges) {
        Decision decision = counters.tryCharge(charges);
        return decision.allowed()
                ? Admission.permitted()
                : Admission.rejected(decision.retryAfterSeconds());
    }

    private static Optional<String> canonicalPhone(String phone) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(phone);
        return trimmed == null ? Optional.empty() : ContactPhoneNumbers.canonicalize(trimmed);
    }

    private static Optional<String> canonicalEmail(String email) {
        String trimmed = ContactTextCanonicalizer.canonicalTrimmed(email);
        return trimmed == null ? Optional.empty() : ContactEmailPolicy.canonicalize(trimmed);
    }

    private static String slugBucket(String slug) {
        if (slug == null) {
            return MALFORMED_SLUG;
        }
        String canonical = slug.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() > MAX_SLUG_LENGTH || !PLAUSIBLE_SLUG.matcher(canonical).matches()) {
            return MALFORMED_SLUG;
        }
        return canonical;
    }

    /** The address as a limiter bucket: an IPv4 address as is, an IPv6 address as its /64 prefix. */
    static String addressBucket(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.length() > 64) {
            return UNKNOWN_ADDRESS;
        }
        try {
            InetAddress address = InetAddress.ofLiteral(remoteAddress.strip());
            byte[] bytes = address.getAddress();
            if (bytes.length == 16) {
                return "v6/" + HexFormat.of().formatHex(bytes, 0, 8);
            }
            return "v4/" + HexFormat.of().formatHex(bytes);
        } catch (IllegalArgumentException exception) {
            return UNKNOWN_ADDRESS;
        }
    }

    private Digest digest(String scope, String... parts) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            update(mac, scope);
            for (String part : parts) {
                update(mac, part);
            }
            ByteBuffer result = ByteBuffer.wrap(mac.doFinal());
            return new Digest(result.getLong(), result.getLong());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    /** Length-prefixed, so no two different part lists produce the same input. */
    private static void update(Mac mac, String part) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }
}
