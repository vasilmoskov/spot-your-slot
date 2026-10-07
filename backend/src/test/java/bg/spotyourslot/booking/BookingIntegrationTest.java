package bg.spotyourslot.booking;

import static bg.spotyourslot.integration.ConcurrencyTestSupport.completed;

import bg.spotyourslot.booking.AppointmentFixtures.Tenant;
import bg.spotyourslot.booking.BookingTestHooks.Point;
import bg.spotyourslot.identity.AuthenticatedBusinessContext;
import bg.spotyourslot.integration.AvailabilityFixtures;
import bg.spotyourslot.integration.MutableTestClock;
import bg.spotyourslot.integration.PostgresIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared base of the guest booking integration tests: real PostgreSQL, the real orchestration, a
 * controllable clock, deterministic hooks, and helpers for named booking threads. Every fixture is
 * synthetic and written to the disposable test database only.
 *
 * <p>The clock is Tuesday 2026-09-29 11:00 in Sofia. The default slot is Thursday 2026-10-01 10:00
 * Sofia time (07:00 UTC) with the StaffMember working 09:00 to 12:00 every day.
 */
@Import(BookingHookConfiguration.class)
@Sql(
        statements = "TRUNCATE business CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class BookingIntegrationTest extends PostgresIntegrationTest {
    protected static final ZoneId SOFIA = ZoneId.of("Europe/Sofia");
    protected static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    private static final AtomicInteger PHONES = new AtomicInteger(100_000);

    @Autowired protected GuestBooking booking;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MutableTestClock clock;
    @Autowired protected BookingTestHooks hooks;
    @Autowired protected PlatformTransactionManager transactionManager;

    protected AppointmentFixtures fixtures;
    protected AvailabilityFixtures availabilityFixtures;
    protected Tenant tenant;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });

    @BeforeEach
    void setUpBookingFixtures() {
        clock.set(BookingHookConfiguration.NOW);
        clock.resetReads();
        hooks.reset();
        TransactionLog.clear();
        fixtures = new AppointmentFixtures(jdbc);
        availabilityFixtures = new AvailabilityFixtures(jdbc);
        tenant = openTenant();
    }

    @AfterEach
    void tearDownBookingFixtures() {
        hooks.reset();
        executor.shutdownNow();
    }

    /** An ACTIVE Business with one active Service, one assigned StaffMember working 09:00-12:00. */
    protected Tenant openTenant() {
        Tenant created = fixtures.tenant();
        // The Phase 2 fixture pre-creates a Customer; booking tests start without any.
        jdbc.sql("DELETE FROM customer WHERE business_id = :id").param("id", created.business()).update();
        availabilityFixtures.everyDay(created.business(), created.staff(), "09:00", "12:00");
        return created;
    }

    // ---- requests ---------------------------------------------------------------

    protected static Instant at(String time) {
        return THURSDAY.atTime(LocalTime.parse(time)).atZone(SOFIA).toInstant();
    }

    protected static String freshPhone() {
        return "+359888" + PHONES.incrementAndGet();
    }

    protected String slugOf(UUID business) {
        return jdbc.sql("SELECT slug FROM business WHERE id = :id").param("id", business)
                .query(String.class).single();
    }

    /** A mutable request builder with valid defaults for {@link #tenant}. */
    protected final class Req {
        String slug = slugOf(tenant.business());
        String attemptId = UUID.randomUUID().toString();
        UUID service = tenant.service();
        UUID staff;
        Instant start = at("10:00");
        String name = "Иван Петров";
        String phone = freshPhone();
        String email;
        String note;

        public Req slug(String value) {
            slug = value;
            return this;
        }

        public Req attempt(String value) {
            attemptId = value;
            return this;
        }

        public Req service(UUID value) {
            service = value;
            return this;
        }

        public Req staff(UUID value) {
            staff = value;
            return this;
        }

        public Req start(String time) {
            start = at(time);
            return this;
        }

        public Req start(Instant value) {
            start = value;
            return this;
        }

        public Req name(String value) {
            name = value;
            return this;
        }

        public Req phone(String value) {
            phone = value;
            return this;
        }

        public Req email(String value) {
            email = value;
            return this;
        }

        public Req note(String value) {
            note = value;
            return this;
        }

        public Req forTenant(Tenant other) {
            slug = slugOf(other.business());
            service = other.service();
            staff = null;
            return this;
        }

        public GuestBookingRequest build() {
            return new GuestBookingRequest(slug, attemptId, service, staff, start, name, phone, email, note);
        }

        /** A copy that shares the attempt identifier and every value, for replays. */
        public Req copy() {
            Req copy = new Req();
            copy.slug = slug;
            copy.attemptId = attemptId;
            copy.service = service;
            copy.staff = staff;
            copy.start = start;
            copy.name = name;
            copy.phone = phone;
            copy.email = email;
            copy.note = note;
            return copy;
        }
    }

    protected Req req() {
        return new Req();
    }

    // ---- threads ----------------------------------------------------------------

    /** Runs a booking on a thread named {@code booking-<label>} so hooks and the log see it. */
    protected CompletableFuture<BookingResult> submit(String label, GuestBookingRequest request) {
        return CompletableFuture.supplyAsync(() -> booking.book(request), runnable -> executor.execute(() -> {
            Thread thread = Thread.currentThread();
            String original = thread.getName();
            thread.setName(threadOf(label));
            try {
                runnable.run();
            } finally {
                thread.setName(original);
            }
        }));
    }

    /** The exact name of the thread that {@link #submit} runs a booking with this label on. */
    protected static String threadOf(String label) {
        return BookingTestHooks.BOOKING_THREAD_PREFIX + label;
    }

    /** Runs a task on a plain (non-booking) thread, so it uses its own connection and autocommits. */
    protected <T> CompletableFuture<T> async(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    protected static <T> T result(CompletableFuture<T> future) {
        return completed(future);
    }

    protected BookingResult book(GuestBookingRequest request) {
        return completed(submit("main", request));
    }

    /**
     * Installs, for the next attempt that reaches the point, a synchronization that blocks just
     * before COMMIT until released, so a test can hold an Appointment insert uncommitted.
     */
    protected static void holdBeforeCommit(java.util.concurrent.CountDownLatch reached,
            java.util.concurrent.CountDownLatch release) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                reached.countDown();
                bg.spotyourslot.integration.ConcurrencyTestSupport.await(release, "commit was never released");
            }
        });
    }

    // ---- observation ------------------------------------------------------------

    protected long appointmentCount(UUID business) {
        return jdbc.sql("SELECT count(*) FROM appointment WHERE business_id = :id")
                .param("id", business).query(Long.class).single();
    }

    protected long customerCount(UUID business) {
        return jdbc.sql("SELECT count(*) FROM customer WHERE business_id = :id")
                .param("id", business).query(Long.class).single();
    }

    protected long totalAppointments() {
        return jdbc.sql("SELECT count(*) FROM appointment").query(Long.class).single();
    }

    protected long totalCustomers() {
        return jdbc.sql("SELECT count(*) FROM customer").query(Long.class).single();
    }

    protected Optional<UUID> appointmentIdByReference(String reference) {
        return jdbc.sql("SELECT id FROM appointment WHERE public_reference = :reference")
                .param("reference", reference).query(UUID.class).optional();
    }

    protected AppointmentRow appointmentRow(String reference) {
        return jdbc.sql("""
                        SELECT business_id, customer_id, service_id, staff_member_id, source, status,
                               start_at, end_at, occupied_until, timezone, duration_minutes, price_eur,
                               service_name, staff_display_name, customer_note, public_reference,
                               booking_attempt_hash, request_fingerprint, fingerprint_encoding_version,
                               fingerprint_key_version, version, created_at, updated_at, xmin::text AS xmin
                        FROM appointment WHERE public_reference = :reference
                        """)
                .param("reference", reference)
                .query((resultSet, rowNumber) -> new AppointmentRow(
                        resultSet.getObject("business_id", UUID.class),
                        resultSet.getObject("customer_id", UUID.class),
                        resultSet.getObject("service_id", UUID.class),
                        resultSet.getObject("staff_member_id", UUID.class),
                        resultSet.getString("source"),
                        resultSet.getString("status"),
                        resultSet.getObject("start_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("end_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("occupied_until", OffsetDateTime.class).toInstant(),
                        resultSet.getString("timezone"),
                        resultSet.getInt("duration_minutes"),
                        resultSet.getBigDecimal("price_eur"),
                        resultSet.getString("service_name"),
                        resultSet.getString("staff_display_name"),
                        resultSet.getString("customer_note"),
                        resultSet.getString("public_reference"),
                        resultSet.getBytes("booking_attempt_hash"),
                        resultSet.getBytes("request_fingerprint"),
                        resultSet.getInt("fingerprint_encoding_version"),
                        resultSet.getInt("fingerprint_key_version"),
                        resultSet.getLong("version"),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("xmin")))
                .single();
    }

    protected record AppointmentRow(
            UUID businessId,
            UUID customerId,
            UUID serviceId,
            UUID staffMemberId,
            String source,
            String status,
            Instant startAt,
            Instant endAt,
            Instant occupiedUntil,
            String timezone,
            int durationMinutes,
            java.math.BigDecimal price,
            String serviceName,
            String staffDisplayName,
            String customerNote,
            String reference,
            byte[] attemptHash,
            byte[] fingerprint,
            int encodingVersion,
            int keyVersion,
            long version,
            Instant createdAt,
            Instant updatedAt,
            String xmin) {
    }

    protected TransactionTemplate snapshotTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        return template;
    }

    protected static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A Business owner of the Tenant's Business, for the real administration and schedule mutations. */
    protected AuthenticatedBusinessContext owner(UUID business) {
        UUID user = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO app_user(
                            id,normalized_email,display_name,password_hash,
                            password_changed_at,created_at,updated_at)
                        VALUES (:id,:email,'Owner','hash',:now,:now,:now)
                        """)
                .param("id", user)
                .param("email", user + "@example.invalid")
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        jdbc.sql("""
                        INSERT INTO membership(
                            id,business_id,user_id,role,active,created_at,updated_at)
                        VALUES (:id,:businessId,:userId,'BUSINESS_OWNER',true,:now,:now)
                        """)
                .param("id", UUID.randomUUID())
                .param("businessId", business)
                .param("userId", user)
                .param("now", utc(BookingHookConfiguration.NOW))
                .update();
        return new OwnerContext(user, business);
    }

    protected record OwnerContext(UUID userId, UUID businessId) implements AuthenticatedBusinessContext {
        @Override
        public Optional<UUID> selectedBusinessId() {
            return Optional.of(businessId);
        }
    }

    /** Fires {@code action} once, at the first arrival of the point on a booking thread. */
    protected void onFirst(Point point, ThrowingConsumer action) {
        AtomicInteger arrivals = new AtomicInteger();
        hooks.on(point, invocation -> {
            if (arrivals.incrementAndGet() == 1) {
                action.accept(invocation);
            }
        });
    }

    @FunctionalInterface
    protected interface ThrowingConsumer {
        void accept(BookingTestHooks.Invocation invocation) throws Exception;
    }
}
