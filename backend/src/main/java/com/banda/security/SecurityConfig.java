package com.banda.security;

import com.banda.users.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT-in-httpOnly-cookie auth + double-submit CSRF, per design decision #9.
 *
 * <p>CSRF: {@code CookieCsrfTokenRepository.withHttpOnlyFalse()} stores the raw (non-XOR'd)
 * CSRF token in a JS-readable {@code XSRF-TOKEN} cookie. We deliberately pair it with the
 * plain {@link CsrfTokenRequestAttributeHandler} (not the BREACH-protecting Xor variant),
 * because with a cookie-based SPA client the token must be read back verbatim and echoed as
 * the {@code X-XSRF-TOKEN} header — this is the officially documented Spring Security
 * "Angular/SPA" CSRF integration pattern. {@link CsrfCookieFilter} forces the (otherwise
 * lazily-resolved) CSRF token to actually be generated and written to the response cookie
 * on every request, not just ones that already read it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(requestHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/activate", "/api/auth/login",
                                "/api/auth/password-reset/complete").permitAll()
                        // Section 11: audit history is admin-panel-only. A finer-grained
                        // per-action permission gate (Phase 3/RBAC) refines this later; for
                        // now the base ADMIN role is the gate, same as every other endpoint.
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(JwtService jwtService, UserAccountRepository userAccountRepository) {
        return new JwtAuthFilter(jwtService, userAccountRepository);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Forces the deferred CsrfToken to resolve (and therefore be written to the response
     * cookie) on every request, not only on requests that already read it explicitly.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
