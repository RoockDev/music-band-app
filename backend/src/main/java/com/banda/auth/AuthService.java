package com.banda.auth;

import com.banda.common.EmailSender;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Section 1 (Authentication) use cases, including self-service password-reset issuance.
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
    private final EmailSender emailSender;
    private final PasswordResetLinkFactory passwordResetLinkFactory;
    private final Clock clock;
    private final Duration resetTokenTtl;

    public AuthService(UserAccountRepository userAccountRepository,
                        PasswordTokenRepository passwordTokenRepository,
                        JwtService jwtService,
                        PasswordEncoder passwordEncoder,
                        EmailSender emailSender,
                        PasswordResetLinkFactory passwordResetLinkFactory,
                        Clock clock,
                        @Value("${app.auth.reset-token-ttl}") Duration resetTokenTtl) {
        this.userAccountRepository = userAccountRepository;
        this.passwordTokenRepository = passwordTokenRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.passwordResetLinkFactory = passwordResetLinkFactory;
        this.clock = clock;
        this.resetTokenTtl = resetTokenTtl;
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

        try {
            // saveAndFlush (not save): forces the @Version check to happen NOW, inside
            // this method, so a losing concurrent redemption is caught here rather than
            // surfacing as an opaque 500 at transaction-commit time.
            userAccountRepository.saveAndFlush(user);
            passwordTokenRepository.saveAndFlush(token);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Lost a concurrent redemption race — to the loser, this must look identical
            // to "token already used/invalid", never a 500.
            throw new InvalidTokenException();
        }
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

    /**
     * Issues and synchronously delivers a reset token only for ACTIVE accounts. The known
     * account row is pessimistically locked so concurrent requests cannot leave two usable
     * RESET tokens. SMTP remains inside this transaction deliberately: delivery failure
     * throws and rolls back both the new token and prior-token invalidation. The controller
     * then returns the same neutral response, without exposing account or SMTP state.
     */
    public void requestPasswordReset(String email) {
        UserAccount user = userAccountRepository.findForPasswordResetByEmail(email)
                .filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
                .orElse(null);
        if (user == null) {
            return;
        }

        Instant now = clock.instant();
        passwordTokenRepository.findByUserAndTypeAndUsedAtIsNull(user, PasswordTokenType.RESET).stream()
                .filter(token -> token.isValid(now))
                .forEach(token -> token.markUsed(now));

        String rawToken = TokenHasher.generateRawToken();
        PasswordToken resetToken = new PasswordToken(user, PasswordTokenType.RESET,
                TokenHasher.sha256Hex(rawToken), now.plus(resetTokenTtl), now);
        passwordTokenRepository.saveAndFlush(resetToken);

        try {
            emailSender.send(user.getEmail(), "Restablece tu contraseña", resetEmailBody(rawToken));
        } catch (RuntimeException e) {
            log.error("Password reset email delivery failed: userId={}, errorType={}",
                    user.getId(), e.getClass().getSimpleName());
            throw new PasswordResetDeliveryException();
        }

        log.info("Password reset requested for userId={}", user.getId());
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

        try {
            // saveAndFlush (not save): forces the @Version check to happen NOW, inside
            // this method, so a losing concurrent redemption is caught here rather than
            // surfacing as an opaque 500 at transaction-commit time.
            userAccountRepository.saveAndFlush(user);
            passwordTokenRepository.saveAndFlush(token);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Lost a concurrent redemption race — to the loser, this must look identical
            // to "token already used/invalid", never a 500.
            throw new InvalidTokenException();
        }

        List<PasswordToken> outstanding = passwordTokenRepository.findByUserAndUsedAtIsNull(user);
        outstanding.forEach(t -> t.markUsed(now));
        passwordTokenRepository.saveAll(outstanding);

        log.info("Password reset completed for user {}", user.getEmail());
    }

    public void logout(UserAccount user) {
        bumpTokenVersionWithRetry(user);
        log.info("Logout for user {}", user.getEmail());
    }

    /**
     * Bumping tokenVersion is commutative — either bump invalidates prior JWTs — so on a
     * lost race (e.g. simultaneous logout + password reset) a single reload-and-retry is
     * correct, not just best-effort. If the retry also loses the race, fail loudly rather
     * than silently dropping the invalidation.
     */
    private void bumpTokenVersionWithRetry(UserAccount user) {
        try {
            user.bumpTokenVersion();
            userAccountRepository.saveAndFlush(user);
        } catch (ObjectOptimisticLockingFailureException e) {
            UserAccount fresh = userAccountRepository.findById(user.getId()).orElseThrow(() -> e);
            fresh.bumpTokenVersion();
            userAccountRepository.saveAndFlush(fresh);
        }
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

    private String resetEmailBody(String rawToken) {
        return "Hemos recibido una solicitud para restablecer tu contraseña.\n\n"
                + "Utiliza este enlace para elegir una nueva contraseña:\n"
                + passwordResetLinkFactory.create(rawToken)
                + "\n\nSi no has solicitado este cambio, puedes ignorar este mensaje.";
    }

    public record LoginResult(String jwt, String email, String role) {
    }
}
