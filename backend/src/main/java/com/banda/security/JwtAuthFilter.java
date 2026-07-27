package com.banda.security;

import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Reads the JWT from the httpOnly access-token cookie, validates it, and — only if the
 * claimed tokenVersion still matches the current UserAccount's tokenVersion and the
 * account is ACTIVE — authenticates the request. This is where the "tokenVersion check"
 * (server-side invalidation on logout/password reset) actually happens: JwtService only
 * verifies cryptographic validity, this filter verifies it's still the *current* session.
 *
 * <p>Never authenticates on failure — it just leaves the request anonymous and lets
 * Spring Security's authorization rules produce the 401/403.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;
    private final UserAccountRepository userAccountRepository;

    public JwtAuthFilter(JwtService jwtService, UserAccountRepository userAccountRepository) {
        this.jwtService = jwtService;
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        extractToken(request)
                .flatMap(jwtService::validateToken)
                .flatMap(this::authenticate)
                .ifPresent(authentication -> SecurityContextHolder.getContext().setAuthentication(authentication));

        filterChain.doFilter(request, response);
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> SecurityConstants.ACCESS_TOKEN_COOKIE.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private Optional<Authentication> authenticate(JwtClaims claims) {
        Optional<UserAccount> maybeUser;
        try {
            maybeUser = userAccountRepository.findById(claims.userId());
        } catch (DataAccessException e) {
            // The DB is unavailable/timing out. This filter runs BEFORE
            // @RestControllerAdvice in the chain, so an uncaught exception here would
            // never reach GlobalExceptionHandler — treat the request as unauthenticated
            // instead of letting it propagate uncaught through the filter chain.
            log.error("Database unavailable while authenticating user {}", claims.userId(), e);
            return Optional.empty();
        }

        if (maybeUser.isEmpty()) {
            return Optional.empty();
        }

        UserAccount user = maybeUser.get();
        if (user.getStatus() != UserStatus.ACTIVE) {
            return Optional.empty();
        }
        if (user.getTokenVersion() != claims.tokenVersion()) {
            return Optional.empty();
        }
        return Optional.of(new UsernamePasswordAuthenticationToken(user, null, authoritiesFor(user)));
    }

    private List<GrantedAuthority> authoritiesFor(UserAccount user) {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }
}
