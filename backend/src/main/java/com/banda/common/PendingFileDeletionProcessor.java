package com.banda.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * Executes durable file-deletion work immediately after commit and retries failures on a
 * schedule. {@link FileStorage#delete(String)} is idempotent, so a database commit failure after
 * physical deletion is safe: the next retry observes an absent file and removes the queue row.
 */
@Service
public class PendingFileDeletionProcessor {

    private static final Logger log = LoggerFactory.getLogger(PendingFileDeletionProcessor.class);

    private final PendingFileDeletionRepository repository;
    private final FileStorage fileStorage;

    public PendingFileDeletionProcessor(PendingFileDeletionRepository repository, FileStorage fileStorage) {
        this.repository = repository;
        this.fileStorage = fileStorage;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long id) {
        repository.findById(id).ifPresent(this::deleteFileAndQueueEntry);
    }

    @Scheduled(fixedDelayString = "${app.file-storage.cleanup-interval-ms:300000}",
            initialDelayString = "${app.file-storage.cleanup-initial-delay-ms:300000}")
    @Transactional
    public void retryPending() {
        repository.findAllByOrderByCreatedAtAscIdAsc().forEach(this::deleteFileAndQueueEntry);
    }

    private void deleteFileAndQueueEntry(PendingFileDeletion pending) {
        try {
            fileStorage.delete(pending.getStorageKey());
            repository.delete(pending);
        } catch (IOException e) {
            log.warn("File deletion remains pending for queue entry {}", pending.getId(), e);
        }
    }
}
