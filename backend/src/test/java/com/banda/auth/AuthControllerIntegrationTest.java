package com.banda.auth;

import com.banda.security.SecurityConstants;
import com.banda.security.TokenHasher;
import com.banda.support.IntegrationTestBase;
import com.banda.users.PasswordToken;
import com.banda.users.PasswordTokenRepository;
import com.banda.users.PasswordTokenType;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers all 5 spec scenarios for Section 1 (Authentication): activation, login, reset,
 * logout, expired token — plus Section 12's "no plaintext secret in logs" requirement.
 */
@AutoConfigureMockMvc
@Import(AuthControllerIntegrationTest.FixedClockConfig.class)
class AuthControllerIntegrationTest extends IntegrationTestBase {

    static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private PasswordTokenRepository passwordTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    @Test
    void activationSetsPasswordAndActivatesAccountThenTokenIsSingleUse() throws Exception {
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("pending@example.com", UserRole.MUSICIAN, UserStatus.PENDING, FIXED_NOW));
        String rawToken = "activation-raw-token";
        passwordTokenRepository.saveAndFlush(new PasswordToken(user, PasswordTokenType.ACTIVATION,
                TokenHasher.sha256Hex(rawToken), FIXED_NOW.plus(Duration.ofDays(1)), FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/activate")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"S3cur3Pass!\"}"))
                .andExpect(status().isOk());

        UserAccount reloaded = userAccountRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(passwordEncoder.matches("S3cur3Pass!", reloaded.getPasswordHash())).isTrue();

        // Reuse must fail — single-use token.
        mockMvc.perform(post("/api/auth/activate")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"AnotherPass!\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginSetsAccessTokenCookieOnSuccess() throws Exception {
        UserAccount user = new UserAccount("login@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        user.setPasswordHash(passwordEncoder.encode("CorrectPass1!"));
        userAccountRepository.saveAndFlush(user);

        Cookie csrf = fetchCsrfCookie();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"login@example.com\",\"password\":\"CorrectPass1!\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessToken = result.getResponse().getCookie(SecurityConstants.ACCESS_TOKEN_COOKIE);
        assertThat(accessToken).isNotNull();
        assertThat(accessToken.isHttpOnly()).isTrue();
        // Proves app.security.cookie-secure is actually wired from config (not just the
        // @Value inline default) — the test profile explicitly sets it to false because
        // MockMvc/TestRestTemplate run over plain HTTP.
        assertThat(accessToken.getSecure()).isFalse();
        assertThat(result.getResponse().getContentAsString()).contains("login@example.com");
    }

    @Test
    void loginWithWrongPasswordIsRejected() throws Exception {
        UserAccount user = new UserAccount("wrongpass@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        user.setPasswordHash(passwordEncoder.encode("CorrectPass1!"));
        userAccountRepository.saveAndFlush(user);

        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"wrongpass@example.com\",\"password\":\"NopeNope1!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithDeactivatedAccountAndCorrectPasswordIsRejectedLikeBadCredentials() throws Exception {
        UserAccount user = new UserAccount("deactivated@example.com", UserRole.MUSICIAN, UserStatus.DEACTIVATED, FIXED_NOW);
        user.setPasswordHash(passwordEncoder.encode("CorrectPass1!"));
        userAccountRepository.saveAndFlush(user);

        Cookie csrf = fetchCsrfCookie();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"deactivated@example.com\",\"password\":\"CorrectPass1!\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("DEACTIVATED");
    }

    @Test
    void loginWithPendingAccountAndCorrectPasswordIsRejectedLikeBadCredentials() throws Exception {
        UserAccount user = new UserAccount("pending-login@example.com", UserRole.MUSICIAN, UserStatus.PENDING, FIXED_NOW);
        // A PENDING account normally has no password hash yet, but even if one were
        // somehow present, status alone must still block login (defense in depth).
        user.setPasswordHash(passwordEncoder.encode("CorrectPass1!"));
        userAccountRepository.saveAndFlush(user);

        Cookie csrf = fetchCsrfCookie();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"pending-login@example.com\",\"password\":\"CorrectPass1!\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("PENDING");
    }

    @Test
    void resetUpdatesPasswordAndInvalidatesOtherOutstandingTokens() throws Exception {
        UserAccount user = new UserAccount("reset@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        user.setPasswordHash(passwordEncoder.encode("OldPass1!"));
        userAccountRepository.saveAndFlush(user);

        String rawResetToken = "reset-raw-token";
        passwordTokenRepository.saveAndFlush(new PasswordToken(user, PasswordTokenType.RESET,
                TokenHasher.sha256Hex(rawResetToken), FIXED_NOW.plus(Duration.ofHours(1)), FIXED_NOW));
        PasswordToken otherOutstanding = passwordTokenRepository.saveAndFlush(new PasswordToken(user, PasswordTokenType.RESET,
                TokenHasher.sha256Hex("other-outstanding-token"), FIXED_NOW.plus(Duration.ofHours(1)), FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/password-reset/complete")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawResetToken + "\",\"newPassword\":\"NewPass1!\"}"))
                .andExpect(status().isOk());

        UserAccount reloaded = userAccountRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("NewPass1!", reloaded.getPasswordHash())).isTrue();

        PasswordToken reloadedOther = passwordTokenRepository.findById(otherOutstanding.getId()).orElseThrow();
        assertThat(reloadedOther.isUsed()).isTrue();
    }

    @Test
    void logoutBumpsTokenVersionInvalidatingOutstandingSessions() throws Exception {
        UserAccount user = new UserAccount("logout@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        user.setPasswordHash(passwordEncoder.encode("LogoutPass1!"));
        userAccountRepository.saveAndFlush(user);
        long versionBeforeLogout = user.getTokenVersion();

        Cookie csrf = fetchCsrfCookie();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"logout@example.com\",\"password\":\"LogoutPass1!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie accessToken = loginResult.getResponse().getCookie(SecurityConstants.ACCESS_TOKEN_COOKIE);

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());

        UserAccount reloaded = userAccountRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isGreaterThan(versionBeforeLogout);
    }

    @Test
    void expiredActivationTokenIsRejectedWithoutStateChange() throws Exception {
        UserAccount user = userAccountRepository.saveAndFlush(
                new UserAccount("expired@example.com", UserRole.MUSICIAN, UserStatus.PENDING, FIXED_NOW));
        String rawToken = "expired-raw-token";
        passwordTokenRepository.saveAndFlush(new PasswordToken(user, PasswordTokenType.ACTIVATION,
                TokenHasher.sha256Hex(rawToken), FIXED_NOW.minus(Duration.ofMinutes(1)), FIXED_NOW.minus(Duration.ofDays(2))));

        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/auth/activate")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"token\":\"" + rawToken + "\",\"newPassword\":\"S3cur3Pass!\"}"))
                .andExpect(status().isBadRequest());

        UserAccount reloaded = userAccountRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(reloaded.getPasswordHash()).isNull();
    }

    @Test
    void noPlaintextSecretsEverAppearInLogs() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);

        try {
            String rawPassword = "Sup3rSecretPassphrase!";
            String rawActivationToken = "log-hygiene-activation-token";

            UserAccount user = userAccountRepository.saveAndFlush(
                    new UserAccount("loghygiene@example.com", UserRole.MUSICIAN, UserStatus.PENDING, FIXED_NOW));
            passwordTokenRepository.saveAndFlush(new PasswordToken(user, PasswordTokenType.ACTIVATION,
                    TokenHasher.sha256Hex(rawActivationToken), FIXED_NOW.plus(Duration.ofDays(1)), FIXED_NOW));

            Cookie csrf = fetchCsrfCookie();

            mockMvc.perform(post("/api/auth/activate")
                    .cookie(csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType("application/json")
                    .content("{\"token\":\"" + rawActivationToken + "\",\"newPassword\":\"" + rawPassword + "\"}"));

            MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                            .cookie(csrf)
                            .header("X-XSRF-TOKEN", csrf.getValue())
                            .contentType("application/json")
                            .content("{\"email\":\"loghygiene@example.com\",\"password\":\"" + rawPassword + "\"}"))
                    .andReturn();
            Cookie issuedJwt = loginResult.getResponse().getCookie(SecurityConstants.ACCESS_TOKEN_COOKIE);
            assertThat(issuedJwt).isNotNull();

            mockMvc.perform(post("/api/auth/logout").cookie(csrf, issuedJwt).header("X-XSRF-TOKEN", csrf.getValue()));

            String allLogs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            assertThat(allLogs).doesNotContain(rawPassword);
            assertThat(allLogs).doesNotContain(rawActivationToken);
            assertThat(allLogs).doesNotContain(issuedJwt.getValue());
        } finally {
            rootLogger.detachAppender(appender);
        }
    }

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        public Clock clock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }
}
