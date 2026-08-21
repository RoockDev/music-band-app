package com.banda.common;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Bridges transactional metadata deletion and non-transactional file storage safely. The
 * pending row is committed in the same database transaction that removes the owning metadata;
 * physical deletion starts only after that commit. A storage failure therefore leaves a
 * durable, retryable record rather than an untracked orphan. This is not filesystem/database
 * atomicity: there can be a bounded interval where deleted content still occupies disk, but it
 * is no longer addressable and remains explicitly tracked until cleanup succeeds.
 */
@Service
public class FileDeletionQueue {

    private final PendingFileDeletionRepository repository;
    private final PendingFileDeletionProcessor processor;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public FileDeletionQueue(PendingFileDeletionRepository repository, PendingFileDeletionProcessor processor,
                              Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void enqueue(String storageKey) {
        PendingFileDeletion pending = repository.saveAndFlush(new PendingFileDeletion(storageKey, clock.instant()));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            processor.process(pending.getId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                processor.process(pending.getId());
            }
        });
    }

    /**
     * Tracks an orphan created before a business transaction could commit. The queue row must
     * survive that transaction's rollback, so it is inserted in an independent transaction.
     */
    public void enqueueIndependent(String storageKey) {
        Long id = requiresNewTransaction.execute(status -> repository.findByStorageKey(storageKey)
                .orElseGet(() -> repository.saveAndFlush(new PendingFileDeletion(storageKey, clock.instant())))
                .getId());
        if (id != null) {
            processor.process(id);
        }
    }
}
