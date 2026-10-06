package bg.spotyourslot.booking;

import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.integration.AvailabilityFixtures;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Synthetic, direct-SQL fixtures for PostgreSQL-backed Appointment tests on a disposable
 * database. Every value is fictional; nothing here touches the development database.
 */
public final class AppointmentFixtures {
    public static final Instant CREATED_AT = Instant.parse("2026-10-01T08:00:00Z");
    public static final String TIMEZONE = "Europe/Sofia";
    public static final String SERVICE_NAME = "Подстригване";
    public static final String STAFF_NAME = "Мария Тестова";
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final AtomicLong PHONES = new AtomicLong(895_000_000L);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AvailabilityFixtures availability;

    public AppointmentFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.availability = new AvailabilityFixtures(jdbc);
    }

    /** One Business with an active Service, an assigned active StaffMember, and a Customer. */
    public record Tenant(UUID business, UUID service, UUID staff, UUID customer) {
    }

    public Tenant tenant() {
        UUID business = availability.business("ACTIVE", TIMEZONE);
        UUID service = availability.service(business, 30, true);
        UUID staff = availability.staffMember(business, true);
        availability.assign(business, staff, service);
        return new Tenant(business, service, staff, customer(business));
    }

    public UUID staffMember(UUID business, UUID service) {
        UUID staff = availability.staffMember(business, true);
        availability.assign(business, staff, service);
        return staff;
    }

    public UUID customer(UUID business) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO customer(
                            id, business_id, display_name, phone, email, version,
                            created_at, updated_at)
                        VALUES (:id, :businessId, 'Тестов Клиент', :phone, NULL, 0, :now, :now)
                        """)
                .param("id", id)
                .param("businessId", business)
                .param("phone", "+" + (359_000_000_000L + PHONES.incrementAndGet()))
                .param("now", OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC))
                .update();
        return id;
    }

    public static String reference() {
        StringBuilder reference = new StringBuilder();
        for (int index = 0; index < 10; index++) {
            reference.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return reference.toString();
    }

    public static byte[] digest() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static BookingAttempt attempt() {
        return new BookingAttempt(digest(), digest(), 1, 1);
    }

    public static NewAppointment online(Tenant tenant, Instant start, int minutes) {
        return appointment(tenant, AppointmentSource.ONLINE, start, minutes, attempt());
    }

    public static NewAppointment manual(Tenant tenant, Instant start, int minutes) {
        return appointment(tenant, AppointmentSource.MANUAL, start, minutes, null);
    }

    public static NewAppointment appointment(
            Tenant tenant,
            AppointmentSource source,
            Instant start,
            int minutes,
            BookingAttempt attempt) {
        return new NewAppointment(
                UUID.randomUUID(),
                tenant.business(),
                tenant.customer(),
                tenant.service(),
                tenant.staff(),
                source,
                start,
                minutes,
                new BigDecimal("25.00"),
                TIMEZONE,
                SERVICE_NAME,
                STAFF_NAME,
                null,
                reference(),
                attempt,
                CREATED_AT);
    }

    /** A valid direct-SQL row (a MANUAL, CONFIRMED, zero-buffer Appointment) keyed by column. */
    public static Map<String, Object> row(Tenant tenant, Instant start, int minutes) {
        Instant end = start.plusSeconds(minutes * 60L);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("business_id", tenant.business());
        row.put("customer_id", tenant.customer());
        row.put("service_id", tenant.service());
        row.put("staff_member_id", tenant.staff());
        row.put("source", "MANUAL");
        row.put("status", "CONFIRMED");
        row.put("start_at", utc(start));
        row.put("end_at", utc(end));
        row.put("occupied_until", utc(end));
        row.put("timezone", TIMEZONE);
        row.put("duration_minutes", minutes);
        row.put("price_eur", new BigDecimal("25.00"));
        row.put("service_name", SERVICE_NAME);
        row.put("staff_display_name", STAFF_NAME);
        row.put("customer_note", null);
        row.put("public_reference", reference());
        row.put("booking_attempt_hash", null);
        row.put("request_fingerprint", null);
        row.put("fingerprint_encoding_version", null);
        row.put("fingerprint_key_version", null);
        row.put("version", 0L);
        row.put("created_at", utc(CREATED_AT));
        row.put("updated_at", utc(CREATED_AT));
        return row;
    }

    public static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Inserts the row exactly as given, so a test can break exactly one column. */
    public void insertRow(Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String names = String.join(", ", row.keySet().stream().map(name -> ":" + name).toList());
        JdbcClient.StatementSpec statement = jdbc.sql(
                "INSERT INTO appointment(" + columns + ") VALUES (" + names + ")");
        row.forEach(statement::param);
        statement.update();
    }
}
