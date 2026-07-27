package com.banda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration excluded: we authenticate via JWT cookie (JwtAuthFilter),
// not form/basic login, so Spring Boot's default in-memory user is unused. Excluding it also
// avoids the "Using generated security password" log line — one less secret-looking string in logs.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BandaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BandaApplication.class, args);
    }

}
