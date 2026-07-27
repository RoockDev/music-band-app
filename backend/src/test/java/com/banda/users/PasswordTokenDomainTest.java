package com.banda.users;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for PasswordToken's single-use + TTL domain logic — no Spring context needed.
 */
class PasswordTokenDomainTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private UserAccount aUser() {
        return new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.PENDING, now);
    }

    @Test
    void freshTokenIsValid() {
        PasswordToken token = new PasswordToken(aUser(), PasswordTokenType.ACTIVATION, "hash",
                now.plus(Duration.ofHours(24)), now);

        assertThat(token.isValid(now)).isTrue();
        assertThat(token.isUsed()).isFalse();
        assertThat(token.isExpired(now)).isFalse();
    }

    @Test
    void tokenIsSingleUse() {
        PasswordToken token = new PasswordToken(aUser(), PasswordTokenType.RESET, "hash",
                now.plus(Duration.ofHours(1)), now);

        token.markUsed(now);

        assertThat(token.isUsed()).isTrue();
        assertThat(token.isValid(now)).isFalse();
    }

    @Test
    void tokenExpiresAfterTtl() {
        PasswordToken token = new PasswordToken(aUser(), PasswordTokenType.ACTIVATION, "hash",
                now.plus(Duration.ofHours(24)), now);

        Instant afterTtl = now.plus(Duration.ofHours(24)).plusSeconds(1);

        assertThat(token.isExpired(afterTtl)).isTrue();
        assertThat(token.isValid(afterTtl)).isFalse();
    }

    @Test
    void tokenRightAtExpiryInstantIsNotYetExpired() {
        Instant expiresAt = now.plus(Duration.ofHours(1));
        PasswordToken token = new PasswordToken(aUser(), PasswordTokenType.RESET, "hash", expiresAt, now);

        assertThat(token.isExpired(expiresAt)).isFalse();
        assertThat(token.isValid(expiresAt)).isTrue();
    }
}
