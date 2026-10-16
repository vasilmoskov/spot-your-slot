package bg.spotyourslot.booking.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

record CommitFault(RuntimeException failure, int completion) {
    static final int NO_REPORT = -99;
}

/** Records the lifecycle, injects failures, and emulates what Spring does around the callbacks. */
final class FakeTransactionManager implements PlatformTransactionManager {
    final List<String> events = new ArrayList<>();
    final List<TransactionDefinition> definitions = new ArrayList<>();
    final Deque<CommitFault> commitFailures = new ArrayDeque<>();
    RuntimeException failRollback;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        events.add("begin");
        definitions.add(definition);
        TransactionSynchronizationManager.initSynchronization();
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
        if (status.isRollbackOnly()) {
            rollback(status);
            return;
        }
        CommitFault fault = commitFailures.pollFirst();
        if (fault == null) {
            complete(TransactionSynchronization.STATUS_COMMITTED);
            events.add("commit");
            return;
        }
        events.add("commit-failed");
        if (fault.completion() != CommitFault.NO_REPORT) {
            complete(fault.completion());
        } else {
            TransactionSynchronizationManager.clear();
        }
        throw fault.failure();
    }

    @Override
    public void rollback(TransactionStatus status) {
        events.add("rollback");
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        if (failRollback != null) {
            throw failRollback;
        }
    }

    private static void complete(int completion) {
        TransactionSynchronizationUtils.triggerAfterCompletion(completion);
        TransactionSynchronizationManager.clear();
    }
}
