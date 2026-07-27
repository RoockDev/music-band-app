package com.banda.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and validates the single self-contained access token described by design
 * decision #9: HS256-signed JWT, moderate lifetime, no refresh token. This class only
 * handles cryptography and shape — it does NOT check tokenVersion against the current
 * database state or account status; that business check happens in {@link JwtAuthFilter},
 * which is the only place with access to the current UserAccount.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.access-token-ttl}") Duration accessTokenTtl,
                       Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
        this.clock = clock;
    }

    public String issueToken(Long userId, long tokenVersion, String role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("tokenVersion", tokenVersion)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    public Optional<JwtClaims> validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = Long.parseLong(claims.getSubject());
            long tokenVersion = ((Number) claims.get("tokenVersion")).longValue();
            String role = claims.get("role", String.class);

            return Optional.of(new JwtClaims(userId, tokenVersion, role));
        } catch (JwtException | IllegalArgumentException e) {
            // Signature invalid, malformed, expired, or unparsable subject/claims — never
            // log the raw token here, it's a bearer credential. Only the exception's
            // class/reason is logged (Section 12: no plaintext secret in logs).
            log.warn("JWT validation failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
