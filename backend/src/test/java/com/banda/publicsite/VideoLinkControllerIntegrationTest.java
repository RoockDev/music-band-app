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

/** Section 8 (Public Site Content) admin panel surface for video links: the RBAC gate
 * (MANAGE_CONTENT, Sec.2/Sec.10) and the audit trail (Sec.11), mirroring
 * {@code NewsControllerIntegrationTest}'s equivalent proof. */
@AutoConfigureMockMvc
class VideoLinkControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private VideoLinkRepository videoLinkRepository;

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

    @Test
    void createByAnAdminHoldingManageContentPermissionSucceedsAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActive("admin-video-create@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-video-create@example.com", "AdminPass1!", csrf);

        MvcResult result = mockMvc.perform(post("/api/videos")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Rehearsal Clip\",\"url\":\"https://youtube.com/watch?v=abc\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Rehearsal Clip");
        VideoLink created = videoLinkRepository.findAll().stream()
                .filter(v -> v.getTitle().equals("Rehearsal Clip")).findFirst().orElseThrow();

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "VideoLink", created.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("VIDEO_LINK_CREATED");
    }

    @Test
    void createByAnAdminLackingManageContentPermissionIsForbidden() throws Exception {
        persistActive("admin-video-nopermission@example.com", "AdminPass1!", UserRole.ADMIN);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-video-nopermission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/videos")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Blocked\",\"url\":\"https://youtube.com/watch?v=blocked\"}"))
                .andExpect(status().isForbidden());

        assertThat(videoLinkRepository.findAll().stream().anyMatch(v -> v.getTitle().equals("Blocked"))).isFalse();
    }
}
