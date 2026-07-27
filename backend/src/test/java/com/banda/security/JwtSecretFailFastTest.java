package com.banda.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads the REAL production {@code application.yml} (from the main classpath) and proves
 * the placeholder it declares for {@code app.jwt.secret} has no hardcoded fallback: Spring
 * MUST fail context refresh when {@code JWT_SECRET} is unresolved, and MUST use exactly the
 * env var's value (not some baked-in default) when it is resolved. This is deliberately
 * tied to the actual shipped config, not a copy-pasted literal, so a regression that
 * reintroduces a fallback default (e.g. {@code ${JWT_SECRET:some-default}}) fails this test.
 */
class JwtSecretFailFastTest {

    @Configuration
    static class SecretHolderConfig {

        // Required for @Value("${...}") placeholder resolution (and its fail-fast behavior
        // on unresolvable placeholders) to be active at all in a plain non-Boot context.
        @Bean
        public static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        public String jwtSecretHolder(@Value("${app.jwt.secret}") String secret) {
            return secret;
        }
    }

    private String jwtSecretPlaceholderFromProductionConfig() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml", new ClassPathResource("application.yml"));

        Map<String, Object> flattened = new HashMap<>();
        for (PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    flattened.put(name, enumerable.getProperty(name));
                }
            }
        }

        Object value = flattened.get("app.jwt.secret");
        assertThat(value).as("application.yml must declare app.jwt.secret").isNotNull();
        return value.toString();
    }

    @Test
    void productionConfigFailsFastWhenJwtSecretEnvVarIsUnresolved() throws IOException {
        String configuredPlaceholder = jwtSecretPlaceholderFromProductionConfig();

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("appYaml", Map.of("app.jwt.secret", configuredPlaceholder)));
        // Deliberately do NOT provide JWT_SECRET anywhere in this environment.
        context.register(SecretHolderConfig.class);

        assertThatThrownBy(context::refresh)
                .as("application.yml must NOT provide a hardcoded fallback for JWT_SECRET — "
                        + "startup must fail fast instead of signing with a committed secret")
                .isInstanceOf(Exception.class);
    }

    @Test
    void productionConfigUsesExactlyTheJwtSecretEnvVarValueWhenResolved() throws IOException {
        String configuredPlaceholder = jwtSecretPlaceholderFromProductionConfig();

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "app.jwt.secret", configuredPlaceholder,
                "JWT_SECRET", "resolved-secret-value-from-env")));
        context.register(SecretHolderConfig.class);

        assertThatCode(context::refresh).doesNotThrowAnyException();
        assertThat(context.getBean("jwtSecretHolder", String.class)).isEqualTo("resolved-secret-value-from-env");
        context.close();
    }
}
