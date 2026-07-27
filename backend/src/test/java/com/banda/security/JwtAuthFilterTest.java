package com.banda.security;

import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link JwtAuthFilter} — no Spring context. A JWT-valid-but-DB-down
 * request must never propagate the DataAccessException uncaught through the filter chain
 * (filters run before {@code @RestControllerAdvice} can catch anything) — it must be
 * treated as unauthenticated instead.
 */
class JwtAuthFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtService, userAccountRepository);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UserAccount activeUser(Long id) {
        UserAccount user = new UserAccount("filter-test@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void databaseFailureDuringAuthenticationLeavesRequestUnauthenticatedAndDoesNotPropagate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SecurityConstants.ACCESS_TOKEN_COOKIE, "some-valid-looking-jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        when(jwtService.validateToken("some-valid-looking-jwt"))
                .thenReturn(Optional.of(new JwtClaims(7L, 0L, "MUSICIAN")));
        when(userAccountRepository.findById(anyLong())).thenThrow(new QueryTimeoutException("db down"));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void validTokenForActiveUserWithMatchingVersionAuthenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SecurityConstants.ACCESS_TOKEN_COOKIE, "valid-jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        UserAccount user = activeUser(9L);
        when(jwtService.validateToken("valid-jwt")).thenReturn(Optional.of(new JwtClaims(9L, 0L, "MUSICIAN")));
        when(userAccountRepository.findById(9L)).thenReturn(Optional.of(user));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(user);
    }

    @Test
    void tokenVersionMismatchIsRejectedAndLoggedWithUserIdNotToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String rawToken = "stale-version-jwt-should-never-appear-in-logs";
        request.setCookies(new Cookie(SecurityConstants.ACCESS_TOKEN_COOKIE, rawToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        UserAccount user = activeUser(11L);
        when(jwtService.validateToken(rawToken)).thenReturn(Optional.of(new JwtClaims(11L, 5L, "MUSICIAN")));
        when(userAccountRepository.findById(11L)).thenReturn(Optional.of(user));

        Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

            boolean warnLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("11"));
            assertThat(warnLogged).isTrue();

            String allLogs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
            assertThat(allLogs).doesNotContain(rawToken);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void inactiveAccountIsRejectedAndLoggedWithUserIdNotToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String rawToken = "deactivated-account-jwt-should-never-appear-in-logs";
        request.setCookies(new Cookie(SecurityConstants.ACCESS_TOKEN_COOKIE, rawToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        UserAccount user = new UserAccount("deactivated@example.com", UserRole.MUSICIAN, UserStatus.DEACTIVATED, Instant.now());
        ReflectionTestUtils.setField(user, "id", 13L);
        when(jwtService.validateToken(rawToken)).thenReturn(Optional.of(new JwtClaims(13L, 0L, "MUSICIAN")));
        when(userAccountRepository.findById(13L)).thenReturn(Optional.of(user));

        Logger logger = (Logger) LoggerFactory.getLogger(JwtAuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            filter.doFilter(request, response, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

            boolean warnLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("13"));
            assertThat(warnLogged).isTrue();

            String allLogs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
            assertThat(allLogs).doesNotContain(rawToken);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
