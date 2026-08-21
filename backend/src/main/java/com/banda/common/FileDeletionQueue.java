package com.banda.common;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

@Service
public class FileDeletionQueue {

    private final PendingFileDeletionRepository repository;
    private final PendingFileDeletionProcessor processor;
    private final Clock clock;

    public FileDeletionQueue(PendingFileDeletionRepository repository, PendingFileDeletionProcessor processor,
                             Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.clock = clock;
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
}
