package com.banda.auth;

import com.banda.common.EmailSender;
import com.banda.security.JwtService;
import com.banda.security.TokenHasher;
import com.banda.users.PasswordToken;
import com.banda.users.PasswordTokenRepository;
import com.banda.users.PasswordTokenType;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito-based unit tests proving how {@link AuthService} REACTS to an optimistic
 * lock conflict (the actual proof that {@code @Version} triggers such a conflict lives in
 * {@code OptimisticLockingTest}, a real JPA-level test). A losing concurrent token
 * redemption must look identical to "token already used/invalid" (never a raw 500); a
 * losing concurrent tokenVersion bump gets exactly one retry before failing loudly.
 */
class AuthServiceOptimisticLockingTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private UserAccountRepository userAccountRepository;
    private PasswordTokenRepository passwordTokenRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        passwordTokenRepository = mock(PasswordTokenRepository.class);
        JwtService jwtService = mock(JwtService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        authService = new AuthService(userAccountRepository, passwordTokenRepository, jwtService, passwordEncoder,
                mock(EmailSender.class), mock(PasswordResetLinkFactory.class), clock, Duration.ofHours(1));
    }

    @Test
    void activateTranslatesLostRedemptionRaceToInvalidTokenException() {
        UserAccount user = new UserAccount("race-activate@example.com", UserRole.MUSICIAN, UserStatus.PENDING, NOW);
        String rawToken = "activation-race-token";
        PasswordToken token = new PasswordToken(user, PasswordTokenType.ACTIVATION,
                TokenHasher.sha256Hex(rawToken), NOW.plus(Duration.ofDays(1)), NOW);

        when(passwordTokenRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken)))
                .thenReturn(Optional.of(token));
        when(userAccountRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserAccount.class, 1L));

        assertThatThrownBy(() -> authService.activate(rawToken, "NewPass1!"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void resetPasswordTranslatesLostRedemptionRaceToInvalidTokenException() {
        UserAccount user = new UserAccount("race-reset@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        String rawToken = "reset-race-token";
        PasswordToken token = new PasswordToken(user, PasswordTokenType.RESET,
                TokenHasher.sha256Hex(rawToken), NOW.plus(Duration.ofHours(1)), NOW);

        when(passwordTokenRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken)))
                .thenReturn(Optional.of(token));
        when(userAccountRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserAccount.class, 1L));

        assertThatThrownBy(() -> authService.resetPassword(rawToken, "NewPass1!"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void logoutRetriesOnceAfterOptimisticLockFailureThenSucceeds() {
        UserAccount staleUser = new UserAccount("race-logout@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(staleUser, "id", 42L);
        long versionBeforeLogout = staleUser.getTokenVersion();

        UserAccount freshUser = new UserAccount("race-logout@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(freshUser, "id", 42L);
        // Simulates a concurrent winner (e.g. a simultaneous password reset) having
        // already bumped tokenVersion once before this retry reloads the row.
        freshUser.bumpTokenVersion();

        when(userAccountRepository.saveAndFlush(staleUser))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserAccount.class, 42L));
        when(userAccountRepository.findById(42L)).thenReturn(Optional.of(freshUser));
        when(userAccountRepository.saveAndFlush(freshUser)).thenReturn(freshUser);

        authService.logout(staleUser);

        assertThat(freshUser.getTokenVersion()).isGreaterThan(versionBeforeLogout + 1);
        verify(userAccountRepository).saveAndFlush(freshUser);
    }

    @Test
    void logoutPropagatesFailureIfRetryAlsoLosesTheRace() {
        UserAccount staleUser = new UserAccount("race-logout-fail@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(staleUser, "id", 43L);

        UserAccount freshUser = new UserAccount("race-logout-fail@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(freshUser, "id", 43L);

        when(userAccountRepository.saveAndFlush(staleUser))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserAccount.class, 43L));
        when(userAccountRepository.findById(43L)).thenReturn(Optional.of(freshUser));
        when(userAccountRepository.saveAndFlush(freshUser))
                .thenThrow(new ObjectOptimisticLockingFailureException(UserAccount.class, 43L));

        assertThatThrownBy(() -> authService.logout(staleUser))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
