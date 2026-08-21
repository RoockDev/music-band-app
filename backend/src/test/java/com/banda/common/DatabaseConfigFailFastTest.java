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

class DatabaseConfigFailFastTest {

    @Configuration
    static class DatabasePasswordHolderConfig {

        @Bean
        public static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        public String databasePasswordHolder(@Value("${spring.datasource.password}") String password) {
            return password;
        }
    }

    @Test
    void productionConfigFailsFastWhenDatabasePasswordIsUnresolved() throws IOException {
        String configuredPlaceholder = databasePasswordPlaceholder();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("appYaml", Map.of("spring.datasource.password", configuredPlaceholder)));
        context.register(DatabasePasswordHolderConfig.class);

        assertThatThrownBy(context::refresh)
                .as("application.yml must not provide a repository-known fallback for DB_PASSWORD")
                .isInstanceOf(Exception.class);
    }

    @Test
    void productionConfigUsesExactlyTheDatabasePasswordEnvVarValue() throws IOException {
        String configuredPlaceholder = databasePasswordPlaceholder();
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.datasource.password", configuredPlaceholder,
                "DB_PASSWORD", "resolved-private-password")));
        context.register(DatabasePasswordHolderConfig.class);

        assertThatCode(context::refresh).doesNotThrowAnyException();
        assertThat(context.getBean("databasePasswordHolder", String.class))
                .isEqualTo("resolved-private-password");
        context.close();
    }

    private String databasePasswordPlaceholder() throws IOException {
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

        Object value = flattened.get("spring.datasource.password");
        assertThat(value).as("application.yml must declare spring.datasource.password").isNotNull();
        return value.toString();
    }
}
