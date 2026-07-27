package com.banda.auth;

import com.banda.security.JwtService;
import com.banda.security.TokenHasher;
import com.banda.users.PasswordToken;
import com.banda.users.PasswordTokenRepository;
import com.banda.users.PasswordTokenType;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Section 1 (Authentication) use cases. Deliberately does NOT expose a way to *create*
 * activation/reset tokens in this PR — issuing the initial activation token is the
 * admin-account-creation flow (Phase 4, a later PR); a self-service "forgot password"
 * request endpoint would additionally require wiring an EmailSender, which is likewise
 * out of scope for this PR's assigned tasks (activation/login/reset/logout completion
 * only). Both are natural, small follow-ups once their respective phases land.
 *
 * <p>Logging is intentionally minimal and never interpolates a raw password, raw token,
 * or JWT value (Section 12).
 */
@Service
@Transactional
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordTokenRepository passwordTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AuthService(UserAccountRepository userAccountRepository,
                        PasswordTokenRepository passwordTokenRepository,
                        JwtService jwtService,
                        PasswordEncoder passwordEncoder,
                        Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.passwordTokenRepository = passwordTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public void activate(String rawToken, String newPassword) {
        PasswordToken token = requireValidToken(rawToken, PasswordTokenType.ACTIVATION);
        UserAccount user = token.getUser();
        if (user.getStatus() != UserStatus.PENDING) {
            throw new InvalidTokenException();
        }

        Instant now = clock.instant();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setStatus(UserStatus.ACTIVE);
        user.touch(now);
        token.markUsed(now);

        userAccountRepository.save(user);
        passwordTokenRepository.save(token);
        log.info("Account activated for user {}", user.getEmail());
    }

    public LoginResult login(String email, String rawPassword) {
        UserAccount user = userAccountRepository.findByEmail(email)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> {
                    log.warn("Login failed for email {}", email);
                    return new InvalidCredentialsException();
                });

        if (user.getPasswordHash() == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.warn("Login failed for email {}", email);
            throw new InvalidCredentialsException();
        }

        String jwt = jwtService.issueToken(user.getId(), user.getTokenVersion(), user.getRole().name());
        log.info("Login succeeded for user {}", user.getEmail());
        return new LoginResult(jwt, user.getEmail(), user.getRole().name());
    }

    public void resetPassword(String rawToken, String newPassword) {
        PasswordToken token = requireValidToken(rawToken, PasswordTokenType.RESET);
        UserAccount user = token.getUser();

        Instant now = clock.instant();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Defensive, beyond the literal spec scenario: a password reset also bumps
        // tokenVersion so any JWT issued before the reset stops working immediately.
        user.bumpTokenVersion();
        user.touch(now);
        token.markUsed(now);
        userAccountRepository.save(user);
        passwordTokenRepository.save(token);

        List<PasswordToken> outstanding = passwordTokenRepository.findByUserAndUsedAtIsNull(user);
        outstanding.forEach(t -> t.markUsed(now));
        passwordTokenRepository.saveAll(outstanding);

        log.info("Password reset completed for user {}", user.getEmail());
    }

    public void logout(UserAccount user) {
        user.bumpTokenVersion();
        userAccountRepository.save(user);
        log.info("Logout for user {}", user.getEmail());
    }

    private PasswordToken requireValidToken(String rawToken, PasswordTokenType expectedType) {
        String hash = TokenHasher.sha256Hex(rawToken);
        PasswordToken token = passwordTokenRepository.findByTokenHash(hash)
                .filter(t -> t.getType() == expectedType)
                .orElseThrow(InvalidTokenException::new);

        if (!token.isValid(clock.instant())) {
            throw new InvalidTokenException();
        }
        return token;
    }

    public record LoginResult(String jwt, String email, String role) {
    }
}
