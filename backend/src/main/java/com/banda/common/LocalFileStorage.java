package com.banda.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Design decision #3's chosen {@link FileStorage} implementation: plain local disk under
 * {@code app.file-storage.base-dir} (defaults to {@code /data/files} — a persistent-volume
 * mount per decision #4, deliberately outside {@code src/main/resources/static} or any other
 * classpath location Spring Boot auto-serves, and with no {@code WebMvcConfigurer} resource
 * mapping registered anywhere in this codebase pointing at it — the spec's "never
 * static-served" requirement for Section 5 sheet music).
 *
 * <p>Every stored file is named by a fresh random UUID, never the caller-supplied original
 * filename — this is the "opaque generated key" the design doc requires, closing off
 * path-traversal via a crafted filename at the write path entirely. {@link #retrieve} still
 * defends against a crafted {@code storageKey} on the read path too (defense-in-depth): the
 * resolved path is normalized and MUST stay within {@link #baseDir}, even though every real
 * caller only ever passes back a key this class itself generated.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(@Value("${app.file-storage.base-dir:/data/files}") String baseDir) throws IOException {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir);
    }

    @Override
    public String store(InputStream content) throws IOException {
        String storageKey = UUID.randomUUID().toString();
        Path target = baseDir.resolve(storageKey);
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return storageKey;
    }

    @Override
    public byte[] retrieve(String storageKey) throws IOException {
        return Files.readAllBytes(resolveWithinBaseDir(storageKey));
    }

    /** Rejects any resolved path that escapes {@link #baseDir} — see class Javadoc. */
    private Path resolveWithinBaseDir(String storageKey) {
        Path resolved = baseDir.resolve(storageKey).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return resolved;
    }
}
