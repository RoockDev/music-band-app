package com.banda.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shared base for integration tests that need a real Spring context backed by a
 * real Postgres instance (via Testcontainers). Extracted so every integration
 * test class does not have to duplicate container bootstrap.
 *
 * <p>Uses the Testcontainers "singleton container" pattern deliberately: the
 * container is started once in a static initializer and never stopped explicitly
 * (Ryuk reaps it on JVM exit). We do NOT use {@code @Testcontainers}/{@code @Container}
 * here because that JUnit extension starts/stops the container per test class — with a
 * static field shared across multiple subclasses (multiple test classes), that per-class
 * stop() kills the container out from under a sibling test class still bound to its
 * (now stale) JDBC port, causing "Connection refused" failures.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Overrides {@code application-test.yml}'s {@code app.file-storage.base-dir} for every
     * integration test class (all of them extend this base — see the same "singleton" note
     * above). Fixes a reliability gap: the previous fixed path
     * ({@code ${java.io.tmpdir}/banda-test-files}) was shared across every test run and
     * never cleaned up, unlike {@code LocalFileStorageTest}'s correct use of JUnit
     * {@code @TempDir}. A fresh, uniquely-named directory is created once per JVM test run
     * (same lifetime as the Postgres container above) and recursively deleted via a shutdown
     * hook so repeated local runs don't accumulate orphaned files.
     */
    static final Path fileStorageBaseDir = createFileStorageBaseDir();

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @DynamicPropertySource
    static void fileStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.file-storage.base-dir", fileStorageBaseDir::toString);
    }

    private static Path createFileStorageBaseDir() {
        try {
            Path dir = Files.createTempDirectory("banda-test-files-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(dir)));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup on JVM shutdown; nothing meaningful to do with a
                    // failure here.
                }
            });
        } catch (IOException ignored) {
            // Directory may already be gone (e.g. never had any files written to it) --
            // nothing to clean up.
        }
    }
}
