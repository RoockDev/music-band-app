package com.banda.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Single injectable Clock so time-dependent logic (token expiry, timestamps) is
 * deterministic and testable instead of calling Instant.now()/Clock.systemUTC() directly.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
