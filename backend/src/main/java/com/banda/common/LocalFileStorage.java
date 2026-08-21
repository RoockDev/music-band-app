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
 * {@code app.file-storage.base-dir}. The configured directory is deliberately outside
 * {@code src/main/resources/static} or any other classpath location Spring Boot auto-serves,
 * and no {@code WebMvcConfigurer} resource mapping points at it.
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

    public LocalFileStorage(@Value("${app.file-storage.base-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        initializeBaseDirectory();
    }

    private void initializeBaseDirectory() {
        if (Files.exists(baseDir) && !Files.isDirectory(baseDir)) {
            throw initializationFailure("exists but is not a directory", null);
        }

        try {
            Files.createDirectories(baseDir);
        } catch (IOException | SecurityException exception) {
            throw initializationFailure("could not be created", exception);
        }

        if (!Files.isWritable(baseDir)) {
            throw initializationFailure("is not writable", null);
        }
    }

    private IllegalStateException initializationFailure(String reason, Exception cause) {
        String message = "Local file storage directory '" + baseDir + "' " + reason
                + ". Create a writable directory or set APP_FILE_STORAGE_BASE_DIR to one.";
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
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

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolveWithinBaseDir(storageKey));
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
