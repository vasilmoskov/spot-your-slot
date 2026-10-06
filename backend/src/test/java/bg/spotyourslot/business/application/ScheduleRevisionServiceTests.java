package bg.spotyourslot.business.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.business.infrastructure.ScheduleRevisionStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The schedule revision service without a database: classification of failures from the SQLState
 * only, sanitized exceptions, the guard's isolation precondition, and delegation. The real
 * transaction, lock, and snapshot behavior is proven against PostgreSQL in
 * {@code ScheduleRevisionIntegrationTests}.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleRevisionServiceTests {
    private static final UUID BUSINESS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    @Mock ScheduleRevisionStore store;

    private ScheduleRevisionService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleRevisionService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearIsolation() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    }

    @Test
    void advanceStampsTheInjectedClockAndReturnsTheNewRevision() {
        when(store.advance(BUSINESS_ID, NOW)).thenReturn(Optional.of(7L));

        assertThat(service.advance(BUSINESS_ID)).isEqualTo(7L);
        verify(store).advance(BUSINESS_ID, NOW);
    }

    @Test
    void aMissingRevisionRowIsAFailureForBothOperationsNeverASilentSuccess() {
        when(store.advance(BUSINESS_ID, NOW)).thenReturn(Optional.empty());
        when(store.lockShared(BUSINESS_ID)).thenReturn(Optional.empty());
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);

        assertThatThrownBy(() -> service.advance(BUSINESS_ID))
                .isExactlyInstanceOf(ScheduleRevisionFailure.class);
        assertThatThrownBy(() -> service.lockShared(BUSINESS_ID))
                .isExactlyInstanceOf(ScheduleRevisionFailure.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001", "40P01"})
    void serializationAndDeadlockVictimsAreTheRetryableConflictForBothOperations(String sqlState) {
        var failure = new DeadlockLoserDataAccessException(
                "SQL [UPDATE business_schedule_revision ...] " + BUSINESS_ID,
                new SQLException("secret " + BUSINESS_ID, sqlState));
        when(store.advance(any(), any())).thenThrow(failure);
        when(store.lockShared(any())).thenThrow(failure);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);

        assertSanitizedConflict(catchFailure(() -> service.advance(BUSINESS_ID)));
        assertSanitizedConflict(catchFailure(() -> service.lockShared(BUSINESS_ID)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23503", "08006", "57014", "55P03", "XX000"})
    void everyOtherSqlStateIsTheSanitizedNonRetryableFailure(String sqlState) {
        var failure = new CannotAcquireLockException(
                "SQL [SELECT revision FROM business_schedule_revision] " + BUSINESS_ID,
                new SQLException("secret " + BUSINESS_ID, sqlState));
        when(store.advance(any(), any())).thenThrow(failure);
        when(store.lockShared(any())).thenThrow(failure);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_SERIALIZABLE);

        assertSanitizedFailure(catchFailure(() -> service.advance(BUSINESS_ID)));
        assertSanitizedFailure(catchFailure(() -> service.lockShared(BUSINESS_ID)));
    }

    @Test
    void aFailureWithoutAnSqlStateIsTheSanitizedFailure() {
        var failure = new DataAccessResourceFailureException("connection " + BUSINESS_ID);
        when(store.advance(any(), any())).thenThrow(failure);

        assertSanitizedFailure(catchFailure(() -> service.advance(BUSINESS_ID)));
    }

    @Test
    void theSqlStateIsFoundAnywhereInTheCauseChain() {
        var nested = new DataAccessResourceFailureException(
                "outer",
                new RuntimeException("middle", new SQLException("deep", "40001")));
        when(store.advance(any(), any())).thenThrow(nested);

        assertSanitizedConflict(catchFailure(() -> service.advance(BUSINESS_ID)));
    }

    @Test
    void theGuardRejectsAWeakOrUnexposedIsolationBeforeAnyStatement() {
        assertThatThrownBy(() -> service.lockShared(BUSINESS_ID))
                .isExactlyInstanceOf(ScheduleRevisionFailure.class);
        for (int weak : new int[] {
            Connection.TRANSACTION_READ_UNCOMMITTED,
            Connection.TRANSACTION_READ_COMMITTED
        }) {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(weak);
            assertThatThrownBy(() -> service.lockShared(BUSINESS_ID))
                    .isExactlyInstanceOf(ScheduleRevisionFailure.class);
        }
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(ints = {Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE})
    void theGuardAcceptsRepeatableReadAndSerializableAndReturnsTheProtectedRevision(int isolation) {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
        when(store.lockShared(BUSINESS_ID)).thenReturn(Optional.of(3L));

        assertThat(service.lockShared(BUSINESS_ID)).isEqualTo(3L);
        verify(store, never()).advance(any(), any());
    }

    @Test
    void theBumpDoesNotRequireASnapshotIsolation() {
        // Mutations run in the default isolation; only the booking-facing guard needs a snapshot.
        when(store.advance(BUSINESS_ID, NOW)).thenReturn(Optional.of(1L));

        assertThat(service.advance(BUSINESS_ID)).isEqualTo(1L);
    }

    @Test
    void aNullBusinessIsRejectedBeforeAnyStatement() {
        assertThatThrownBy(() -> service.advance(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.lockShared(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(store);
    }

    @Test
    void theContractExceptionsAreFixedMessageCauseFreeAndSuppressionFree() {
        for (RuntimeException failure : new RuntimeException[] {
            new ScheduleRevisionConcurrentConflict(), new ScheduleRevisionFailure()
        }) {
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getMessage()).doesNotContain(BUSINESS_ID.toString());
            failure.addSuppressed(new RuntimeException("ignored"));
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getStackTrace()).isNotEmpty();
        }
        assertThat(new ScheduleRevisionConcurrentConflict().getMessage())
                .isEqualTo("Schedule revision operation conflicted with a concurrent change");
        assertThat(new ScheduleRevisionFailure().getMessage())
                .isEqualTo("Schedule revision operation failed");
    }

    private static Throwable catchFailure(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        throw new AssertionError("a failure was expected");
    }

    private static void assertSanitizedConflict(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(ScheduleRevisionConcurrentConflict.class);
        assertSanitized(failure);
    }

    private static void assertSanitizedFailure(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(ScheduleRevisionFailure.class);
        assertSanitized(failure);
    }

    private static void assertSanitized(Throwable failure) {
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage())
                .doesNotContain(BUSINESS_ID.toString())
                .doesNotContain("secret")
                .doesNotContain("SQL")
                .doesNotContain("business_schedule_revision");
    }
}
