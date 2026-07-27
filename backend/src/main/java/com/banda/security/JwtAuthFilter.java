package com.banda.security;

import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
        return userAccountRepository.findById(claims.userId())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .filter(user -> user.getTokenVersion() == claims.tokenVersion())
                .map(user -> new UsernamePasswordAuthenticationToken(user, null, authoritiesFor(user)));
    }

    private List<GrantedAuthority> authoritiesFor(UserAccount user) {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }
}
