package com.banda.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design decision #3: local disk behind a swappable {@link FileStorage} interface. Proves
 * the round-trip (store returns an opaque key, retrieve resolves the exact same bytes back)
 * and the path-traversal defense a maliciously-crafted storage key would otherwise allow —
 * even though every real caller only ever passes back a server-generated UUID, this is
 * defense-in-depth per the "store files by an opaque generated key ... to avoid
 * path-traversal risk" requirement.
 */
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void storeReturnsAnOpaqueKeyDifferentFromAnyOriginalFilenameAndRetrieveReturnsTheSameBytes() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        byte[] content = "%PDF-1.4 fake sheet music bytes".getBytes(StandardCharsets.UTF_8);

        String storageKey = storage.store(new ByteArrayInputStream(content));

        assertThat(storageKey).isNotBlank();
        assertThat(storageKey).doesNotContain("original.pdf");
        byte[] retrieved = storage.retrieve(storageKey);
        assertThat(retrieved).isEqualTo(content);
    }

    @Test
    void twoStoredFilesGetDifferentOpaqueKeysEvenWithIdenticalContent() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);

        String firstKey = storage.store(new ByteArrayInputStream(content));
        String secondKey = storage.store(new ByteArrayInputStream(content));

        assertThat(firstKey).isNotEqualTo(secondKey);
    }

    @Test
    void retrievingAnUnknownStorageKeyThrows() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

        assertThatThrownBy(() -> storage.retrieve("does-not-exist"))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void retrievingAPathTraversalStorageKeyIsRejectedRatherThanEscapingTheBaseDirectory() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        Path secretOutsideBaseDir = Files.writeString(tempDir.getParent().resolve("secret.txt"), "top secret");

        assertThatThrownBy(() -> storage.retrieve("../" + secretOutsideBaseDir.getFileName()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorCreatesTheBaseDirectoryIfItDoesNotExistYet() throws IOException {
        Path nestedDir = tempDir.resolve("nested/does/not/exist/yet");

        new LocalFileStorage(nestedDir.toString());

        assertThat(Files.isDirectory(nestedDir)).isTrue();
    }

    /** Resilience fix: {@code delete} lets a caller clean up a file it just wrote if a later
     * step in the same use case fails (e.g. {@code SheetMusicService#upload}'s access-scope
     * application), so a rolled-back DB transaction never leaves an orphaned file behind. */
    @Test
    void deleteRemovesAPreviouslyStoredFileSoItCanNoLongerBeRetrieved() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        String storageKey = storage.store(new ByteArrayInputStream("bytes to delete".getBytes(StandardCharsets.UTF_8)));

        storage.delete(storageKey);

        assertThatThrownBy(() -> storage.retrieve(storageKey)).isInstanceOf(NoSuchFileException.class);
    }

    /** A no-op, not an error, per the interface's own contract -- deleting an already-gone
     * (or never-existed) key must not throw. */
    @Test
    void deletingAnUnknownStorageKeyIsANoOp() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

        storage.delete("does-not-exist");
    }
}
