package bg.spotyourslot.booking.infrastructure;

import bg.spotyourslot.booking.domain.Appointment;
import bg.spotyourslot.booking.domain.AppointmentSource;
import bg.spotyourslot.booking.domain.AppointmentStatus;
import bg.spotyourslot.booking.domain.BlockingWindow;
import bg.spotyourslot.booking.domain.BookingAttempt;
import bg.spotyourslot.booking.domain.NewAppointment;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.ConcurrentFailure;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.DuplicateAttempt;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.DuplicatePublicReference;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.InvalidData;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.OverlapConflict;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.UnexpectedFailure;
import bg.spotyourslot.booking.infrastructure.AppointmentPersistenceException.UnknownReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Internal Appointment persistence. It owns every Appointment SQL statement and returns domain
 * values, never persistence entities. It is not a published contract.
 *
 * <p>Transaction semantics: the store opens no transaction of its own and joins the caller's, so a
 * rollback of the caller restores the previous state. Every write is a single statement, and no
 * read takes a lock. Every statement is scoped by {@code business_id}, so a lookup in another
 * Business behaves exactly like a missing Appointment. Overlap protection is the database
 * exclusion constraint of {@code V11}, never an application pre-check.
 *
 * <p>Failures are classified only structurally, from the PostgreSQL SQLState and the exact
 * constraint (or table and column) name the driver reports, never by parsing message text, and are
 * rethrown as fixed, cause-free {@link AppointmentPersistenceException}s.
 */
@Repository
public class AppointmentStore {
    static final String OVERLAP_EXCLUSION = "appointment_staff_no_overlap";
    static final String PUBLIC_REFERENCE_UNIQUE = "appointment_business_public_reference_unique";
    static final String ATTEMPT_HASH_UNIQUE = "appointment_business_attempt_hash_unique";
    static final Set<String> FOREIGN_KEYS = Set.of(
            "appointment_business_fk",
            "appointment_customer_fk",
            "appointment_service_fk",
            "appointment_staff_member_fk");
    static final Set<String> CHECK_CONSTRAINTS = Set.of(
            "appointment_source_valid",
            "appointment_status_valid",
            "appointment_instants_finite",
            "appointment_duration_minutes_range",
            "appointment_end_matches_duration",
            "appointment_occupied_until_equals_end",
            "appointment_price_nonnegative",
            "appointment_timezone_not_blank",
            "appointment_service_name_canonical",
            "appointment_staff_display_name_canonical",
            "appointment_customer_note_plain_text",
            "appointment_public_reference_format",
            "appointment_attempt_hash_length",
            "appointment_fingerprint_length",
            "appointment_fingerprint_versions_positive",
            "appointment_idempotency_all_or_none",
            "appointment_online_requires_idempotency",
            "appointment_version_nonnegative",
            "appointment_timestamps_finite_ordered");

    private static final int MAX_CAUSE_DEPTH = 16;
    private static final String TABLE = "appointment";
    private static final Set<String> COLUMNS = Set.of(
            "id", "business_id", "customer_id", "service_id", "staff_member_id", "source",
            "status", "start_at", "end_at", "occupied_until", "timezone", "duration_minutes",
            "price_eur", "service_name", "staff_display_name", "customer_note",
            "public_reference", "booking_attempt_hash", "request_fingerprint",
            "fingerprint_encoding_version", "fingerprint_key_version", "version", "created_at",
            "updated_at");

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String VALUE_TOO_LONG = "22001";
    private static final String DATETIME_OVERFLOW = "22008";
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private static final String RETURNING_COLUMNS = """
            id, business_id, customer_id, service_id, staff_member_id, source, status,
            start_at, end_at, occupied_until, timezone, duration_minutes, price_eur,
            service_name, staff_display_name, customer_note, public_reference,
            booking_attempt_hash, request_fingerprint, fingerprint_encoding_version,
            fingerprint_key_version, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public AppointmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new {@code CONFIRMED} Appointment at version 0 in one statement. A time that
     * overlaps another {@code CONFIRMED} Appointment of the StaffMember is reported as
     * {@link OverlapConflict}; the caller's transaction is then aborted and must be abandoned.
     */
    public Appointment insert(NewAppointment appointment) {
        BookingAttempt attempt = appointment.attempt();
        return execute(() -> jdbc.sql("""
                        INSERT INTO appointment(
                            id, business_id, customer_id, service_id, staff_member_id,
                            source, status, start_at, end_at, occupied_until, timezone,
                            duration_minutes, price_eur, service_name, staff_display_name,
                            customer_note, public_reference, booking_attempt_hash,
                            request_fingerprint, fingerprint_encoding_version,
                            fingerprint_key_version, version, created_at, updated_at)
                        VALUES (
                            :id, :businessId, :customerId, :serviceId, :staffMemberId,
                            :source, 'CONFIRMED', :startAt, :endAt, :occupiedUntil, :timezone,
                            :durationMinutes, :priceEur, :serviceName, :staffDisplayName,
                            :customerNote, :publicReference, :attemptHash,
                            :requestFingerprint, :encodingVersion,
                            :keyVersion, 0, :createdAt, :createdAt)
                        RETURNING
                        """ + RETURNING_COLUMNS)
                .param("id", appointment.id())
                .param("businessId", appointment.businessId())
                .param("customerId", appointment.customerId())
                .param("serviceId", appointment.serviceId())
                .param("staffMemberId", appointment.staffMemberId())
                .param("source", appointment.source().name())
                .param("startAt", databaseTime(appointment.startAt()))
                .param("endAt", databaseTime(appointment.endAt()))
                .param("occupiedUntil", databaseTime(appointment.occupiedUntil()))
                .param("timezone", appointment.timezone())
                .param("durationMinutes", appointment.durationMinutes())
                .param("priceEur", appointment.priceEur())
                .param("serviceName", appointment.serviceName())
                .param("staffDisplayName", appointment.staffDisplayName())
                .param("customerNote", appointment.customerNote(), Types.VARCHAR)
                .param("publicReference", appointment.publicReference())
                .param("attemptHash", attempt == null ? null : attempt.attemptHash(), Types.BINARY)
                .param(
                        "requestFingerprint",
                        attempt == null ? null : attempt.requestFingerprint(),
                        Types.BINARY)
                .param(
                        "encodingVersion",
                        attempt == null ? null : attempt.encodingVersion(),
                        Types.SMALLINT)
                .param(
                        "keyVersion",
                        attempt == null ? null : attempt.keyVersion(),
                        Types.SMALLINT)
                .param("createdAt", databaseTime(appointment.createdAt()))
                .query(this::appointment)
                .single());
    }

    /** One statement; no lock. A foreign-Business identifier behaves like a missing one. */
    public Optional<Appointment> find(UUID businessId, UUID id) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(id, "id");
        return execute(() -> jdbc.sql("""
                        SELECT
                        """ + RETURNING_COLUMNS + """
                        FROM appointment
                        WHERE business_id = :businessId AND id = :id
                        """)
                .param("businessId", businessId)
                .param("id", id)
                .query(this::appointment)
                .optional());
    }

    /**
     * One statement; no lock. Finds the Appointment this Business created for a booking attempt,
     * for the idempotent replay of ADR-0024. A foreign-Business attempt is not found.
     */
    public Optional<Appointment> findByAttemptHash(UUID businessId, byte[] attemptHash) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(attemptHash, "attemptHash");
        return execute(() -> jdbc.sql("""
                        SELECT
                        """ + RETURNING_COLUMNS + """
                        FROM appointment
                        WHERE business_id = :businessId AND booking_attempt_hash = :attemptHash
                        """)
                .param("businessId", businessId)
                .param("attemptHash", attemptHash.clone(), Types.BINARY)
                .query(this::appointment)
                .optional());
    }

    /**
     * One bulk statement, no lock: the occupied half-open windows of the requested StaffMembers of
     * the Business that overlap {@code [from, to)}, {@code CONFIRMED} rows only, ordered by
     * StaffMember, start, and end. A window that merely touches a boundary is not returned. An
     * empty StaffMember set or an empty query window issues no statement.
     */
    public List<BlockingWindow> findBlockingWindows(
            UUID businessId, Collection<UUID> staffMemberIds, Instant from, Instant to) {
        Objects.requireNonNull(businessId, "businessId");
        Objects.requireNonNull(staffMemberIds, "staffMemberIds");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Set<UUID> requested = new LinkedHashSet<>(staffMemberIds);
        if (requested.isEmpty() || !from.isBefore(to)) {
            // An empty request or an empty window overlaps nothing, so no statement is needed.
            return List.of();
        }
        return execute(() -> jdbc.sql("""
                        SELECT staff_member_id, start_at, occupied_until
                        FROM appointment
                        WHERE business_id = :businessId
                          AND staff_member_id IN (:staffMemberIds)
                          AND status = 'CONFIRMED'
                          AND tstzrange(start_at, occupied_until, '[)')
                              && tstzrange(:from, :to, '[)')
                        ORDER BY staff_member_id, start_at, occupied_until
                        """)
                .param("businessId", businessId)
                .param("staffMemberIds", List.copyOf(requested))
                .param("from", databaseTime(from))
                .param("to", databaseTime(to))
                .query((resultSet, rowNumber) -> new BlockingWindow(
                        resultSet.getObject("staff_member_id", UUID.class),
                        resultSet.getObject("start_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("occupied_until", OffsetDateTime.class).toInstant()))
                .list());
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AppointmentPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    /**
     * Classifies a failure structurally and discards it. The first {@link SQLException} in the
     * cause chain supplies the standard SQLState; for a PostgreSQL {@link PSQLException} the exact
     * constraint, table, and column names come from its {@link ServerErrorMessage}. Message text is
     * never read, missing structured data is treated as unexpected, and the translated exception
     * retains no reference to the original throwable.
     */
    static AppointmentPersistenceException translate(Throwable failure) {
        SQLException sqlException = findSqlException(failure);
        if (sqlException == null) {
            return new UnexpectedFailure(null);
        }
        String sqlState = sqlException.getSQLState();
        String constraint = null;
        String table = null;
        String column = null;
        if (sqlException instanceof PSQLException postgres) {
            ServerErrorMessage details = postgres.getServerErrorMessage();
            if (details != null) {
                constraint = details.getConstraint();
                table = details.getTable();
                column = details.getColumn();
            }
        }

        if (EXCLUSION_VIOLATION.equals(sqlState)) {
            if (OVERLAP_EXCLUSION.equals(constraint)) {
                return new OverlapConflict();
            }
        } else if (UNIQUE_VIOLATION.equals(sqlState)) {
            if (PUBLIC_REFERENCE_UNIQUE.equals(constraint)) {
                return new DuplicatePublicReference();
            }
            if (ATTEMPT_HASH_UNIQUE.equals(constraint)) {
                return new DuplicateAttempt();
            }
        } else if (FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            if (constraint != null && FOREIGN_KEYS.contains(constraint)) {
                return new UnknownReference();
            }
        } else if (CHECK_VIOLATION.equals(sqlState)) {
            if (constraint != null && CHECK_CONSTRAINTS.contains(constraint)) {
                return new InvalidData();
            }
        } else if (NOT_NULL_VIOLATION.equals(sqlState)) {
            if (TABLE.equals(table) && column != null && COLUMNS.contains(column)) {
                return new InvalidData();
            }
        } else if (VALUE_TOO_LONG.equals(sqlState) || DATETIME_OVERFLOW.equals(sqlState)) {
            // PostgreSQL reports no constraint for these states; every Appointment statement
            // writes only the length-limited and date-time Appointment columns.
            return new InvalidData();
        } else if (SERIALIZATION_FAILURE.equals(sqlState) || DEADLOCK_DETECTED.equals(sqlState)) {
            return new ConcurrentFailure();
        }
        return new UnexpectedFailure(sqlState);
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private Appointment appointment(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            byte[] attemptHash = resultSet.getBytes("booking_attempt_hash");
            BookingAttempt attempt = attemptHash == null
                    ? null
                    : new BookingAttempt(
                            attemptHash,
                            resultSet.getBytes("request_fingerprint"),
                            resultSet.getInt("fingerprint_encoding_version"),
                            resultSet.getInt("fingerprint_key_version"));
            return new Appointment(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("business_id", UUID.class),
                    resultSet.getObject("customer_id", UUID.class),
                    resultSet.getObject("service_id", UUID.class),
                    resultSet.getObject("staff_member_id", UUID.class),
                    AppointmentSource.valueOf(resultSet.getString("source")),
                    AppointmentStatus.valueOf(resultSet.getString("status")),
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
                    attempt,
                    resultSet.getLong("version"),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
        } catch (IllegalArgumentException | NullPointerException corrupt) {
            // A stored row that violates a domain invariant is a persistence failure, not input.
            throw new CorruptRow();
        }
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final class CorruptRow extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private CorruptRow() {
            super("Stored Appointment row is invalid", null, false, false);
        }
    }
}
