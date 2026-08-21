package com.banda.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileStorageConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(LocalFileStorage.class);

    @TempDir
    Path tempDir;

    @Test
    void defaultStorageDirectoryIsResolvedUnderUserHome() {
        Path testHome = tempDir.resolve("test-home");
        Path expectedStorageDirectory = testHome.resolve(".music-band-app/files");

        contextRunner
                .withPropertyValues("user.home=" + testHome)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertStoredIn(context.getBean(LocalFileStorage.class), expectedStorageDirectory);
                });
    }

    @Test
    void environmentVariableOverridesTheDefaultStorageDirectory() {
        Path testHome = tempDir.resolve("unused-test-home");
        Path overrideDirectory = tempDir.resolve("storage-override");

        contextRunner
                .withPropertyValues(
                        "user.home=" + testHome,
                        "APP_FILE_STORAGE_BASE_DIR=" + overrideDirectory)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertStoredIn(context.getBean(LocalFileStorage.class), overrideDirectory);
                    assertThat(testHome.resolve(".music-band-app/files")).doesNotExist();
                });
    }

    private static void assertStoredIn(LocalFileStorage storage, Path expectedDirectory) throws IOException {
        byte[] content = "configuration test".getBytes(StandardCharsets.UTF_8);

        String storageKey = storage.store(new ByteArrayInputStream(content));

        assertThat(expectedDirectory.resolve(storageKey)).hasBinaryContent(content);
        assertThat(Files.isDirectory(expectedDirectory)).isTrue();
    }
}
