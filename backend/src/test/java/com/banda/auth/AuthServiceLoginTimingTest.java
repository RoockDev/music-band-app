package com.banda.auth;

import com.banda.common.EmailSender;
import com.banda.security.JwtService;
import com.banda.users.PasswordTokenRepository;
import com.banda.users.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceLoginTimingTest {

    @Test
    void unknownAccountStillPerformsAPasswordHashComparison() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(users.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
        AuthService service = new AuthService(
                users,
                mock(PasswordTokenRepository.class),
                mock(JwtService.class),
                passwordEncoder,
                mock(EmailSender.class),
                mock(PasswordResetLinkFactory.class),
                Clock.systemUTC(),
                Duration.ofHours(1));

        assertThatThrownBy(() -> service.login("unknown@example.com", "candidate-password"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(passwordEncoder).matches(org.mockito.ArgumentMatchers.eq("candidate-password"), anyString());
    }
}
