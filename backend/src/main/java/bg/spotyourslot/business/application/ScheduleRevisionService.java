package bg.spotyourslot.business.application;

import bg.spotyourslot.business.ScheduleRevisionBump;
import bg.spotyourslot.business.ScheduleRevisionConcurrentConflict;
import bg.spotyourslot.business.ScheduleRevisionFailure;
import bg.spotyourslot.business.ScheduleRevisionGuard;
import bg.spotyourslot.business.infrastructure.ScheduleRevisionStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Implements the schedule revision contracts. Both operations are {@code MANDATORY}: they join the
 * caller's transaction and never open or suspend one. Failures are classified from the PostgreSQL
 * SQLState only and the underlying exception is discarded (it can carry SQL and identifiers).
 */
@Component
public class ScheduleRevisionService implements ScheduleRevisionBump, ScheduleRevisionGuard {
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK_DETECTED = "40P01";

    private final ScheduleRevisionStore store;
    private final Clock clock;

    public ScheduleRevisionService(ScheduleRevisionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long advance(UUID businessId) {
        Objects.requireNonNull(businessId, "businessId");
        return requirePresent(execute(() -> store.advance(businessId, clock.instant())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long lockShared(UUID businessId) {
        Objects.requireNonNull(businessId, "businessId");
        requireSnapshotIsolation();
        return requirePresent(execute(() -> store.lockShared(businessId)));
    }

    /**
     * MANDATORY joins the caller's transaction, so the effective isolation is checked before any
     * statement. Only a repeatable-read or serializable transaction gives the guard its meaning (a
     * bump committed after the snapshot fails the lock); a weaker level, or a level Spring does not
     * expose because the transaction used the driver default, is rejected.
     */
    private static void requireSnapshotIsolation() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        boolean snapshotCoherent = isolation != null
                && (isolation == Connection.TRANSACTION_REPEATABLE_READ
                        || isolation == Connection.TRANSACTION_SERIALIZABLE);
        if (!snapshotCoherent) {
            throw new ScheduleRevisionFailure();
        }
    }

    private static long requirePresent(Optional<Long> revision) {
        return revision.orElseThrow(ScheduleRevisionFailure::new);
    }

    private static <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException exception) {
            throw translate(exception);
        }
    }

    private static RuntimeException translate(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return switch (sqlException.getSQLState()) {
                    case SERIALIZATION_FAILURE, DEADLOCK_DETECTED ->
                            new ScheduleRevisionConcurrentConflict();
                    default -> new ScheduleRevisionFailure();
                };
            }
        }
        return new ScheduleRevisionFailure();
    }
}
