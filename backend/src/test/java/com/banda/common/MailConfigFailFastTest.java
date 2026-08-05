package com.banda.common;

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
 * Mirrors {@code com.banda.security.JwtSecretFailFastTest}'s reasoning, applied to the mail
 * config identified as a CRITICAL gap in Section 9's post-review pass: {@code spring.mail.host},
 * {@code spring.mail.port}, and {@code app.mail.from} used to have local-dev-friendly defaults
 * in {@code application.yml}, which meant a prod deployment that forgot to set the matching
 * env var would boot fine and silently blackhole every contact-form admin notification against
 * an unreachable host, with zero operator-visible signal. Loads the REAL production
 * {@code application.yml} (from the main classpath) and proves each of those three
 * placeholders now has no hardcoded fallback: Spring MUST fail context refresh when the env
 * var is unresolved, and MUST use exactly the env var's value when it is resolved. Deliberately
 * tied to the actual shipped config, not a copy-pasted literal, so a regression that
 * reintroduces a fallback default fails this test.
 */
class MailConfigFailFastTest {

    @Configuration
    static class MailHostHolderConfig {

        @Bean
        public static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        public String mailHostHolder(@Value("${spring.mail.host}") String host) {
            return host;
        }
    }

    @Configuration
    static class MailPortHolderConfig {

        @Bean
        public static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        public String mailPortHolder(@Value("${spring.mail.port}") String port) {
            return port;
        }
    }

    @Configuration
    static class MailFromHolderConfig {

        @Bean
        public static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        public String mailFromHolder(@Value("${app.mail.from}") String from) {
            return from;
        }
    }

    private Map<String, Object> productionConfigProperties() throws IOException {
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
        return flattened;
    }

    private String placeholderFor(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        assertThat(value).as("application.yml must declare " + key).isNotNull();
        return value.toString();
    }

    @Test
    void productionConfigFailsFastWhenMailHostEnvVarIsUnresolved() throws IOException {
        String configuredPlaceholder = placeholderFor(productionConfigProperties(), "spring.mail.host");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("appYaml", Map.of("spring.mail.host", configuredPlaceholder)));
        // Deliberately do NOT provide MAIL_HOST anywhere in this environment.
        context.register(MailHostHolderConfig.class);

        assertThatThrownBy(context::refresh)
                .as("application.yml must NOT provide a hardcoded fallback for MAIL_HOST — "
                        + "startup must fail fast instead of silently pointing at a local-dev SMTP host")
                .isInstanceOf(Exception.class);
    }

    @Test
    void productionConfigUsesExactlyTheMailHostEnvVarValueWhenResolved() throws IOException {
        String configuredPlaceholder = placeholderFor(productionConfigProperties(), "spring.mail.host");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.mail.host", configuredPlaceholder,
                "MAIL_HOST", "smtp.resolved-from-env.example.com")));
        context.register(MailHostHolderConfig.class);

        assertThatCode(context::refresh).doesNotThrowAnyException();
        assertThat(context.getBean("mailHostHolder", String.class)).isEqualTo("smtp.resolved-from-env.example.com");
        context.close();
    }

    @Test
    void productionConfigFailsFastWhenMailPortEnvVarIsUnresolved() throws IOException {
        String configuredPlaceholder = placeholderFor(productionConfigProperties(), "spring.mail.port");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("appYaml", Map.of("spring.mail.port", configuredPlaceholder)));
        // Deliberately do NOT provide MAIL_PORT anywhere in this environment.
        context.register(MailPortHolderConfig.class);

        assertThatThrownBy(context::refresh)
                .as("application.yml must NOT provide a hardcoded fallback for MAIL_PORT")
                .isInstanceOf(Exception.class);
    }

    @Test
    void productionConfigUsesExactlyTheMailPortEnvVarValueWhenResolved() throws IOException {
        String configuredPlaceholder = placeholderFor(productionConfigProperties(), "spring.mail.port");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.mail.port", configuredPlaceholder,
                "MAIL_PORT", "2525")));
        context.register(MailPortHolderConfig.class);

        assertThatCode(context::refresh).doesNotThrowAnyException();
        assertThat(context.getBean("mailPortHolder", String.class)).isEqualTo("2525");
        context.close();
    }

    @Test
    void productionConfigFailsFastWhenMailFromEnvVarIsUnresolved() throws IOException {
        String configuredPlaceholder = placeholderFor(productionConfigProperties(), "app.mail.from");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("appYaml", Map.of("app.mail.from", configuredPlaceholder)));
        // Deliberately do NOT provide MAIL_FROM anywhere in this environment.
        context.register(MailFromHolderConfig.class);

        assertThatThrownBy(context::refresh)
                .as("application.yml must NOT provide a hardcoded fallback for MAIL_FROM")
                .isInstanceOf(Exception.class);
    }

    @Test
    void productionConfigUsesExactlyTheMailFromEnvVarValueWhenResolved() throws IOException {
        String configuredPlaceholder = placeholderFor(productionConfigProperties(), "app.mail.from");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "app.mail.from", configuredPlaceholder,
                "MAIL_FROM", "resolved-from-env@example.com")));
        context.register(MailFromHolderConfig.class);

        assertThatCode(context::refresh).doesNotThrowAnyException();
        assertThat(context.getBean("mailFromHolder", String.class)).isEqualTo("resolved-from-env@example.com");
        context.close();
    }
}
