package com.banda.users;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordTokenRepositoryTest extends IntegrationTestBase {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordTokenRepository passwordTokenRepository;

    @Test
    void persistsATokenLinkedToItsUser() {
        Instant now = Instant.now();
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("pending@example.com", UserRole.MUSICIAN, UserStatus.PENDING, now));

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
                new UserAccount("reset@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, now));
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
