package bg.spotyourslot.booking;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * The application's JPA transaction manager with one addition: it records, through
 * {@link TransactionLog}, the transactions that a booking thread begins itself (with their
 * definition), commits, rolls back, and every suspension of an outer transaction. Behavior is
 * otherwise unchanged. A {@code REQUIRES_NEW} would show as a suspension, and a transaction that was
 * merely joined shows as nothing, so the log proves each attempt began exactly one new transaction.
 */
public final class RecordingJpaTransactionManager extends JpaTransactionManager {
    private static final long serialVersionUID = 1L;

    public RecordingJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        TransactionLog.begin(definition);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        TransactionLog.end("commit");
        super.doCommit(status);
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        TransactionLog.end("rollback");
        super.doRollback(status);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        TransactionLog.suspension();
        return super.doSuspend(transaction);
    }
}
