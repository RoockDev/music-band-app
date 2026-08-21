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
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServicePasswordResetTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration RESET_TTL = Duration.ofMinutes(45);

    private UserAccountRepository userAccountRepository;
    private PasswordTokenRepository passwordTokenRepository;
    private EmailSender emailSender;
    private PasswordResetLinkFactory linkFactory;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userAccountRepository = mock(UserAccountRepository.class);
        passwordTokenRepository = mock(PasswordTokenRepository.class);
        emailSender = mock(EmailSender.class);
        linkFactory = mock(PasswordResetLinkFactory.class);
        when(linkFactory.create(anyString())).thenAnswer(invocation ->
                "https://band.example/restablecer?token=" + invocation.getArgument(0, String.class));

        authService = new AuthService(userAccountRepository, passwordTokenRepository,
                mock(JwtService.class), mock(PasswordEncoder.class), emailSender, linkFactory,
                Clock.fixed(NOW, ZoneOffset.UTC), RESET_TTL);
    }

    @Test
    void activeAccountReplacesUsableResetTokenPersistsOnlyHashAndEmailsRawToken() {
        UserAccount user = new UserAccount("active@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        PasswordToken previous = new PasswordToken(user, PasswordTokenType.RESET,
                TokenHasher.sha256Hex("previous"), NOW.plusSeconds(60), NOW.minusSeconds(60));
        when(userAccountRepository.findForPasswordResetByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordTokenRepository.findByUserAndTypeAndUsedAtIsNull(user, PasswordTokenType.RESET))
                .thenReturn(List.of(previous));

        authService.requestPasswordReset(user.getEmail());

        ArgumentCaptor<PasswordToken> tokenCaptor = ArgumentCaptor.forClass(PasswordToken.class);
        verify(passwordTokenRepository).saveAndFlush(tokenCaptor.capture());
        PasswordToken persisted = tokenCaptor.getValue();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(org.mockito.ArgumentMatchers.eq(user.getEmail()),
                org.mockito.ArgumentMatchers.eq("Restablece tu contraseña"), bodyCaptor.capture());
        ArgumentCaptor<String> rawTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(linkFactory).create(rawTokenCaptor.capture());
        String rawToken = rawTokenCaptor.getValue();

        assertThat(previous.getUsedAt()).isEqualTo(NOW);
        assertThat(persisted.getType()).isEqualTo(PasswordTokenType.RESET);
        assertThat(persisted.getCreatedAt()).isEqualTo(NOW);
        assertThat(persisted.getExpiresAt()).isEqualTo(NOW.plus(RESET_TTL));
        assertThat(persisted.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(rawToken));
        assertThat(persisted.getTokenHash()).doesNotContain(rawToken);
        assertThat(bodyCaptor.getValue()).contains("https://band.example/restablecer?token=" + rawToken);
    }

    @Test
    void unknownOrIneligibleAccountDoesNotCreateTokenOrSendEmail() {
        UserAccount pending = new UserAccount("pending@example.com", UserRole.MUSICIAN, UserStatus.PENDING, NOW);
        when(userAccountRepository.findForPasswordResetByEmail("unknown@example.com")).thenReturn(Optional.empty());
        when(userAccountRepository.findForPasswordResetByEmail(pending.getEmail())).thenReturn(Optional.of(pending));

        authService.requestPasswordReset("unknown@example.com");
        authService.requestPasswordReset(pending.getEmail());

        verify(passwordTokenRepository, never()).saveAndFlush(any());
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void smtpFailureRaisesRollbackSignalWithoutLeakingTheRawTokenInTheException() {
        UserAccount user = new UserAccount("smtp@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        when(userAccountRepository.findForPasswordResetByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordTokenRepository.findByUserAndTypeAndUsedAtIsNull(user, PasswordTokenType.RESET))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("SMTP unavailable"))
                .when(emailSender).send(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> authService.requestPasswordReset(user.getEmail()))
                .isInstanceOf(PasswordResetDeliveryException.class)
                .hasMessage(null);
    }
}
