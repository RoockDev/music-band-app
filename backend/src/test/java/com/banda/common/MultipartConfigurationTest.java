package com.banda.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void requestLimitIncludesEnvelopeAboveTheMaximumFilePayload() {
        contextRunner.run(context -> {
            MultipartProperties properties = Binder.get(context.getEnvironment())
                    .bind("spring.servlet.multipart", MultipartProperties.class)
                    .orElseThrow(() -> new AssertionError("Multipart properties were not bound"));

            assertThat(properties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(20));
            assertThat(properties.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(21));
            assertThat(properties.getMaxRequestSize()).isGreaterThan(properties.getMaxFileSize());
        });
    }
}
