package com.banda.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PublicWriteRateLimitFilterTest {

    private PublicWriteRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        PublicWriteRateLimitProperties properties = new PublicWriteRateLimitProperties();
        properties.setLogin(new PublicWriteRateLimitProperties.Limit(2, Duration.ofMinutes(1)));
        properties.setContact(new PublicWriteRateLimitProperties.Limit(1, Duration.ofMinutes(10)));
        filter = new PublicWriteRateLimitFilter(properties,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsRequestsBeyondThePerClientRouteLimit() throws Exception {
        assertThat(send("POST", "/api/auth/login", "203.0.113.1").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/api/auth/login", "203.0.113.1").getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = send("POST", "/api/auth/login", "203.0.113.1");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentAsString()).contains("Too many requests");
        assertThat(send("POST", "/api/auth/login", "203.0.113.2").getStatus()).isEqualTo(200);
    }

    @Test
    void isolatesEndpointBudgetsAndIgnoresNonProtectedRequests() throws Exception {
        assertThat(send("POST", "/api/contact", "203.0.113.3").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/api/contact", "203.0.113.3").getStatus()).isEqualTo(429);

        assertThat(send("GET", "/api/public/news", "203.0.113.3").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/api/news", "203.0.113.3").getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse send(String method, String path, String remoteAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
