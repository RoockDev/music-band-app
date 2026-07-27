package com.banda.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

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

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
