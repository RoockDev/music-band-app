package com.banda.users;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses {@code token-pending@example.com}/{@code token-reset@example.com} (not the shorter
 * {@code pending@example.com}/{@code reset@example.com}) specifically to avoid colliding with
 * {@code AuthControllerIntegrationTest}'s identical literal emails: every integration test
 * class shares one real, never-truncated Postgres instance for the whole JVM run (see
 * {@link IntegrationTestBase}'s own "singleton container" note), so two different test
 * classes hardcoding the same email is a real {@code UNIQUE(email)} collision risk whenever
 * Surefire's test-class execution order happens to interleave them — surfaced here only after
 * adding the new {@code com.banda.events.*} integration test classes shifted that order.
 */
class PasswordTokenRepositoryTest extends IntegrationTestBase {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordTokenRepository passwordTokenRepository;

    @Test
    void persistsATokenLinkedToItsUser() {
        Instant now = Instant.now();
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("token-pending@example.com", UserRole.MUSICIAN, UserStatus.PENDING, now));

        PasswordToken token = new PasswordToken(user, PasswordTokenType.ACTIVATION, "hashed-value",
                now.plus(Duration.ofHours(24)), now);
        passwordTokenRepository.saveAndFlush(token);

        Optional<PasswordToken> found = passwordTokenRepository.findByTokenHash("hashed-value");
        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
        assertThat(found.get().getType()).isEqualTo(PasswordTokenType.ACTIVATION);
        assertThat(found.get().isUsed()).isFalse();
    }

    @Test
    void markingATokenUsedPersistsAcrossReload() {
        Instant now = Instant.now();
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("token-reset@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, now));
        PasswordToken token = passwordTokenRepository.saveAndFlush(
                new PasswordToken(user, PasswordTokenType.RESET, "reset-hash", now.plus(Duration.ofHours(1)), now));

        token.markUsed(now);
        passwordTokenRepository.saveAndFlush(token);

        PasswordToken reloaded = passwordTokenRepository.findByTokenHash("reset-hash").orElseThrow();
        assertThat(reloaded.isUsed()).isTrue();
        assertThat(reloaded.isValid(now)).isFalse();
    }

    @Test
    void findsOnlyOutstandingTokensForAUser() {
        Instant now = Instant.now();
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("multi@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, now));

        PasswordToken used = passwordTokenRepository.saveAndFlush(
                new PasswordToken(user, PasswordTokenType.RESET, "used-hash", now.plus(Duration.ofHours(1)), now));
        used.markUsed(now);
        passwordTokenRepository.saveAndFlush(used);

        passwordTokenRepository.saveAndFlush(
                new PasswordToken(user, PasswordTokenType.RESET, "outstanding-hash", now.plus(Duration.ofHours(1)), now));

        List<PasswordToken> outstanding = passwordTokenRepository.findByUserAndUsedAtIsNull(user);

        assertThat(outstanding).hasSize(1);
        assertThat(outstanding.get(0).getTokenHash()).isEqualTo("outstanding-hash");
    }
}
