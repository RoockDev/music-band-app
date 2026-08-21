package com.banda.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingFileDeletionProcessorTest {

    @Test
    void successfulStorageDeletionRemovesTheTrackedQueueEntry() throws IOException {
        PendingFileDeletionRepository repository = mock(PendingFileDeletionRepository.class);
        FileStorage storage = mock(FileStorage.class);
        PendingFileDeletion pending = new PendingFileDeletion("key", Instant.EPOCH);
        when(repository.findById(1L)).thenReturn(Optional.of(pending));
        PendingFileDeletionProcessor processor = new PendingFileDeletionProcessor(repository, storage);

        processor.process(1L);

        verify(storage).delete("key");
        verify(repository).delete(pending);
    }

    @Test
    void failedStorageDeletionKeepsTheQueueEntryForRetry() throws IOException {
        PendingFileDeletionRepository repository = mock(PendingFileDeletionRepository.class);
        FileStorage storage = mock(FileStorage.class);
        PendingFileDeletion pending = new PendingFileDeletion("key", Instant.EPOCH);
        when(repository.findById(1L)).thenReturn(Optional.of(pending));
        doThrow(new IOException("disk unavailable")).when(storage).delete("key");
        PendingFileDeletionProcessor processor = new PendingFileDeletionProcessor(repository, storage);

        processor.process(1L);

        verify(repository, never()).delete(pending);
    }
}
