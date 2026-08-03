package com.banda.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for JwtService — no Spring context. Uses fixed clocks so
 * expiry behavior is deterministic instead of relying on Thread.sleep.
 */
class JwtServiceTest {

    private static final String SECRET = "test-only-secret-not-for-production-test-only-secret";

    private JwtService serviceAt(Instant now) {
        return new JwtService(SECRET, Duration.ofMinutes(1), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenRoundTripsClaims() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        JwtService service = serviceAt(now);

        String token = service.issueToken(42L, 3L, "ADMIN");
        Optional<JwtClaims> claims = service.validateToken(token);

        assertThat(claims).isPresent();
        assertThat(claims.get().userId()).isEqualTo(42L);
        assertThat(claims.get().tokenVersion()).isEqualTo(3L);
        assertThat(claims.get().role()).isEqualTo("ADMIN");
    }

    @Test
    void expiredTokenIsRejected() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        JwtService issuer = serviceAt(issuedAt);
        String token = issuer.issueToken(1L, 0L, "MUSICIAN");

        JwtService validatorLater = new JwtService(SECRET, Duration.ofMinutes(1),
                Clock.fixed(issuedAt.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));

        assertThat(validatorLater.validateToken(token)).isEmpty();
    }

    @Test
    void tamperedSignatureIsRejected() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        JwtService service = serviceAt(now);
        String token = service.issueToken(1L, 0L, "MUSICIAN");

        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(service.validateToken(tampered)).isEmpty();
    }

    @Test
    void malformedTokenIsRejected() {
        JwtService service = serviceAt(Instant.now());

        assertThat(service.validateToken("not-a-jwt-at-all")).isEmpty();
    }

    @Test
    void validationFailureIsLoggedWithoutTheRawToken() {
        Logger logger = (Logger) LoggerFactory.getLogger(JwtService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            JwtService service = serviceAt(Instant.now());
            String rawMalformedToken = "not-a-jwt-at-all-and-should-never-appear-in-logs";

            assertThat(service.validateToken(rawMalformedToken)).isEmpty();

            boolean warnLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("JWT validation failed"));
            assertThat(warnLogged).isTrue();

            String allLogs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(allLogs).doesNotContain(rawMalformedToken);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
