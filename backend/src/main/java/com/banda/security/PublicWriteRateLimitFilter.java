package com.banda.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded in-memory per-client throttling for unauthenticated write endpoints. */
public class PublicWriteRateLimitFilter extends OncePerRequestFilter {

    private static final String OVERFLOW_CLIENT = "__overflow__";

    private final PublicWriteRateLimitProperties properties;
    private final Clock clock;
    private final Map<ClientRoute, WindowCounter> counters = new ConcurrentHashMap<>();

    public PublicWriteRateLimitFilter(PublicWriteRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || ruleFor(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Rule rule = ruleFor(request.getRequestURI());
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Instant now = clock.instant();
        String client = boundedClientKey(request.getRemoteAddr(), now);
        ClientRoute key = new ClientRoute(client, rule.route());
        Decision decision = consume(key, rule.limit(), now);
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Too many requests\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Decision consume(ClientRoute key, PublicWriteRateLimitProperties.Limit limit, Instant now) {
        WindowCounter counter = counters.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.startedAt().plus(limit.window()))) {
                return new WindowCounter(now, 1);
            }
            return new WindowCounter(current.startedAt(), current.count() + 1);
        });
        if (counter.count() <= limit.requests()) {
            return new Decision(true, 0);
        }
        Duration remaining = Duration.between(now, counter.startedAt().plus(limit.window()));
        return new Decision(false, Math.max(1, remaining.toSeconds()));
    }

    private String boundedClientKey(String remoteAddress, Instant now) {
        String client = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        if (counters.size() < properties.getMaxTrackedClients()
                || counters.keySet().stream().anyMatch(key -> key.client().equals(client))) {
            return client;
        }
        counters.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        return counters.size() < properties.getMaxTrackedClients() ? client : OVERFLOW_CLIENT;
    }

    private boolean expired(WindowCounter counter, Instant now) {
        Duration longestWindow = properties.getPasswordReset().window();
        if (properties.getLogin().window().compareTo(longestWindow) > 0) {
            longestWindow = properties.getLogin().window();
        }
        if (properties.getTokenRedemption().window().compareTo(longestWindow) > 0) {
            longestWindow = properties.getTokenRedemption().window();
        }
        if (properties.getContact().window().compareTo(longestWindow) > 0) {
            longestWindow = properties.getContact().window();
        }
        return !now.isBefore(counter.startedAt().plus(longestWindow));
    }

    private Rule ruleFor(String path) {
        return switch (path) {
            case "/api/auth/login" -> new Rule("login", properties.getLogin());
            case "/api/auth/password-reset/request" ->
                    new Rule("password-reset", properties.getPasswordReset());
            case "/api/auth/activate", "/api/auth/password-reset/complete" ->
                    new Rule("token-redemption", properties.getTokenRedemption());
            case "/api/contact" -> new Rule("contact", properties.getContact());
            default -> null;
        };
    }

    private record ClientRoute(String client, String route) {
    }

    private record WindowCounter(Instant startedAt, int count) {
    }

    private record Rule(String route, PublicWriteRateLimitProperties.Limit limit) {
    }

    private record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
