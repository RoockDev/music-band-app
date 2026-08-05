package com.banda.publicsite;

import com.banda.audit.AuditLog;
import com.banda.audit.AuditLogRepository;
import com.banda.security.AdminPermission;
import com.banda.security.AdminPermissionRepository;
import com.banda.security.Permission;
import com.banda.security.SecurityConstants;
import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 8 (Public Site Content) admin panel surface for news: the RBAC gate
 * (MANAGE_CONTENT, enforced independent of the base ADMIN role — Sec.2/Sec.10) and the audit
 * trail (Sec.11), proven through real HTTP over a real Postgres instance — mirroring
 * {@code GroupControllerIntegrationTest}'s equivalent proof.
 */
@AutoConfigureMockMvc
class NewsControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private NewsPostRepository newsPostRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private UserAccount persistActive(String email, String rawPassword, UserRole role) {
        UserAccount account = new UserAccount(email, role, UserStatus.ACTIVE, NOW);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        return userAccountRepository.saveAndFlush(account);
    }

    private Cookie loginAndGetAccessTokenCookie(String email, String rawPassword, Cookie csrf) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + rawPassword + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie(SecurityConstants.ACCESS_TOKEN_COOKIE);
    }

    /** Collision-proof: reads the persisted row's id directly from the create response body
     * instead of locating it by a literal title in the shared Testcontainers Postgres table,
     * which other {@code IntegrationTestBase}-extending test classes can also write rows into
     * for the same entity type (see the 4th-confirmed-instance fixture-name-collision bug this
     * closes). Every create endpoint already returns the persisted {@code id}, so this needs no
     * naming convention to remember. */
    private Long extractId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    @Test
    void createByAnAdminHoldingManageContentPermissionSucceedsAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActive("admin-news-create@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-news-create@example.com", "AdminPass1!", csrf);

        MvcResult result = mockMvc.perform(post("/api/news")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Spring Concert Recap\",\"body\":\"It was great.\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Spring Concert Recap");
        Long createdId = extractId(result);

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "NewsPost", createdId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("NEWS_POST_CREATED");
    }

    @Test
    void createByAnAdminLackingManageContentPermissionIsForbidden() throws Exception {
        persistActive("admin-news-nopermission@example.com", "AdminPass1!", UserRole.ADMIN);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-news-nopermission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/news")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Blocked Post\",\"body\":\"Should not save.\"}"))
                .andExpect(status().isForbidden());

        assertThat(newsPostRepository.findAll().stream().anyMatch(n -> n.getTitle().equals("Blocked Post"))).isFalse();
    }

    @Test
    void createByAMusicianIsForbidden() throws Exception {
        persistActive("musician-news-create@example.com", "MusicianPass1!", UserRole.MUSICIAN);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-news-create@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(post("/api/news")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Musician Attempt\",\"body\":\"Should not save.\"}"))
                .andExpect(status().isForbidden());
    }

    /** No CSRF cookie/header at all -- the CSRF filter itself rejects this before the request
     * ever reaches authentication (403, not 401), matching {@code SecurityConfig}'s filter
     * ordering. See {@link #createByAnUnauthenticatedVisitorWithAValidCsrfTokenIsUnauthorized}
     * for the "authenticated gate specifically" proof. */
    @Test
    void createByAnUnauthenticatedVisitorWithNoCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/news")
                        .contentType("application/json")
                        .content("{\"title\":\"Anon Attempt\",\"body\":\"Should not save.\"}"))
                .andExpect(status().isForbidden());

        assertThat(newsPostRepository.findAll().stream().anyMatch(n -> n.getTitle().equals("Anon Attempt"))).isFalse();
    }

    /** With a valid CSRF token but no JWT cookie, the request passes the CSRF filter and is
     * rejected by the authentication entry point instead -- 401, proving {@code /api/news/**}
     * genuinely requires authentication (not merely a valid CSRF token). */
    @Test
    void createByAnUnauthenticatedVisitorWithAValidCsrfTokenIsUnauthorized() throws Exception {
        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/news")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Anon Attempt\",\"body\":\"Should not save.\"}"))
                .andExpect(status().isUnauthorized());
    }
}
