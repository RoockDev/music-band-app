package com.banda.security;

import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the JWT-in-httpOnly-cookie authentication and double-submit CSRF flow
 * end to end against the real security filter chain.
 */
@AutoConfigureMockMvc
@Import(SecurityFilterChainIntegrationTest.PingTestConfig.class)
class SecurityFilterChainIntegrationTest extends IntegrationTestBase {

    private static final String JWT_COOKIE = "ACCESS_TOKEN";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    private UserAccount persistActiveUser(String email) {
        UserAccount user = new UserAccount(email, UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now());
        return userAccountRepository.saveAndFlush(user);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validJwtCookieGrantsAccessAndIssuesCsrfCookie() throws Exception {
        UserAccount user = persistActiveUser("cookie-auth@example.com");
        String token = jwtService.issueToken(user.getId(), user.getTokenVersion(), user.getRole().name());

        MvcResult result = mockMvc.perform(get("/api/ping").cookie(new Cookie(JWT_COOKIE, token)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("pong");
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
    }

    @Test
    void mutatingRequestWithoutCsrfHeaderIsRejected() throws Exception {
        UserAccount user = persistActiveUser("no-csrf@example.com");
        String token = jwtService.issueToken(user.getId(), user.getTokenVersion(), user.getRole().name());

        mockMvc.perform(post("/api/ping").cookie(new Cookie(JWT_COOKIE, token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void mutatingRequestWithMatchingCsrfHeaderSucceeds() throws Exception {
        UserAccount user = persistActiveUser("with-csrf@example.com");
        Cookie jwtCookie = new Cookie(JWT_COOKIE, jwtService.issueToken(user.getId(), user.getTokenVersion(), user.getRole().name()));

        MvcResult bootstrap = mockMvc.perform(get("/api/ping").cookie(jwtCookie))
                .andExpect(status().isOk())
                .andReturn();
        Cookie xsrfCookie = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();

        mockMvc.perform(post("/api/ping")
                        .cookie(jwtCookie, xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void staleTokenVersionIsRejectedAfterLogoutBump() throws Exception {
        UserAccount user = persistActiveUser("stale-version@example.com");
        String tokenBeforeLogout = jwtService.issueToken(user.getId(), user.getTokenVersion(), user.getRole().name());

        user.bumpTokenVersion();
        userAccountRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/ping").cookie(new Cookie(JWT_COOKIE, tokenBeforeLogout)))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class PingTestConfig {

        @RestController
        static class PingController {

            @GetMapping("/api/ping")
            public String ping() {
                return "pong";
            }

            @PostMapping("/api/ping")
            public String pingPost() {
                return "pong-post";
            }
        }
    }
}
