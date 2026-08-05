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
                        // Section 3: user/musician management is admin-panel-only at this
                        // coarse level; UserService additionally requires the specific
                        // MANAGE_USERS permission toggle (Sec.2/Sec.10) independent of
                        // holding ADMIN, enforced in the service layer per request.
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        // Section 4: groups management is admin-panel-only at this coarse
                        // level; GroupService additionally requires the specific
                        // MANAGE_GROUPS permission toggle (Sec.2/Sec.10) independent of
                        // holding ADMIN, enforced in the service layer per request.
                        .requestMatchers("/api/groups/**").hasRole("ADMIN")
                        // Section 5/6: unlike the admin-only panels above, sheet music is
                        // reachable by both MUSICIAN and ADMIN roles — any authenticated
                        // user may attempt a download, per-piece authorization is enforced
                        // by SheetMusicAccessService#canAccess in the service layer, and
                        // upload additionally requires the MANAGE_SHEET_MUSIC permission
                        // toggle (Sec.2/Sec.10). Explicit here (though functionally already
                        // covered by anyRequest().authenticated() below) for the same
                        // documentation clarity the other feature sections use.
                        .requestMatchers("/api/sheet-music/**").authenticated()
                        // Section 6: collection management (currently create-only, see
                        // CollectionController's own Javadoc) is admin-panel-only at this
                        // coarse level, same as groups/users -- CollectionService
                        // additionally requires the MANAGE_SHEET_MUSIC permission toggle
                        // (Sec.2/Sec.10) independent of holding ADMIN, enforced in the
                        // service layer per request.
                        .requestMatchers("/api/collections/**").hasRole("ADMIN")
                        // Section 7: like sheet music (not like the admin-only groups/users/
                        // collections panels), the internal calendar is reachable by both
                        // MUSICIAN and ADMIN roles -- list()/get() authorize per-event via
                        // EventAccessService#canAccess in the service layer, and
                        // create/edit/cancel additionally require the MANAGE_EVENTS
                        // permission toggle (Sec.2/Sec.10). Explicit here for the same
                        // documentation clarity /api/sheet-music/** uses.
                        .requestMatchers("/api/events/**").authenticated()
                        // Section 8: the FIRST unauthenticated, public-facing surface in this
                        // backend -- every other rule above requires at least a valid JWT
                        // cookie. News/gallery/videos/courses reads AND the public events
                        // listing (deliberately decoupled from /api/events/** -- see
                        // com.banda.publicsite.PublicEventService's own Javadoc) all live
                        // under this single permitAll() prefix so the entire unauthenticated
                        // attack surface is visible at a glance from this one rule.
                        .requestMatchers("/api/public/**").permitAll()
                        // Section 8 admin panel surfaces (create-only, see each *Service's own
                        // Javadoc): admin-only at this coarse level, same shape as
                        // groups/users/collections -- each service additionally requires the
                        // MANAGE_CONTENT permission toggle (Sec.2/Sec.10) independent of
                        // holding ADMIN, enforced in the service layer per request. These are
                        // DIFFERENT URL prefixes from their public read-only counterparts
                        // above, never overlapping paths.
                        .requestMatchers("/api/news/**").hasRole("ADMIN")
                        .requestMatchers("/api/albums/**").hasRole("ADMIN")
                        .requestMatchers("/api/videos/**").hasRole("ADMIN")
                        .requestMatchers("/api/courses/**").hasRole("ADMIN")
                        // Section 9 (Contact Form): the FIRST unauthenticated, public-facing
                        // WRITE in this backend -- every permitAll() rule above (including
                        // /api/auth/activate|login|password-reset/complete) is either
                        // read-only or itself gated by a single-use token. A website visitor
                        // has no JWT cookie at all. CSRF protection is deliberately NOT
                        // exempted here -- same established pattern as the unauthenticated
                        // /api/auth POSTs above: the visitor must first GET /api/auth/csrf
                        // for a CSRF cookie/header pair before this POST is accepted. See
                        // ContactController/ContactService's own Javadoc for the rest of the
                        // unauthenticated-write reasoning (no Permission gate, no actor).
                        .requestMatchers(HttpMethod.POST, "/api/contact").permitAll()
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
