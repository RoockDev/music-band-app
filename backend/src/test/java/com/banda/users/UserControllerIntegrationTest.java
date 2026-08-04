package com.banda.users;

import com.banda.audit.AuditLog;
import com.banda.audit.AuditLogRepository;
import com.banda.security.AdminPermission;
import com.banda.security.AdminPermissionRepository;
import com.banda.security.Permission;
import com.banda.security.SecurityConstants;
import com.banda.support.IntegrationTestBase;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 3 (User/Musician Management) end-to-end: the RBAC gate (MANAGE_USERS, enforced
 * independent of the base ADMIN role — Sec.2/Sec.10) and the audit trail (Sec.11) around
 * every mutation, plus the core "deactivate blocks login" scenario spanning UserService,
 * AuthService and JwtAuthFilter together.
 */
@AutoConfigureMockMvc
@Import(UserControllerIntegrationTest.FixedClockConfig.class)
class UserControllerIntegrationTest extends IntegrationTestBase {

    static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private UserAccount persistActiveAdmin(String email, String rawPassword) {
        UserAccount admin = new UserAccount(email, UserRole.ADMIN, UserStatus.ACTIVE, FIXED_NOW);
        admin.setPasswordHash(passwordEncoder.encode(rawPassword));
        return userAccountRepository.saveAndFlush(admin);
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
    void createByAnAdminHoldingManageUsersPermissionSucceedsAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-create@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-create@example.com", "AdminPass1!", csrf);

        MvcResult result = mockMvc.perform(post("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"newmusician@example.com\",\"role\":\"MUSICIAN\","
                                + "\"minor\":false,\"consentOnFile\":false}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("activationToken");
        UserAccount created = userAccountRepository.findByEmail("newmusician@example.com").orElseThrow();
        assertThat(created.getStatus()).isEqualTo(UserStatus.PENDING);

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "UserAccount", created.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("USER_CREATED");
        assertThat(history.get(0).getActorId()).isEqualTo(admin.getId());
    }

    @Test
    void createByAnAdminLackingManageUsersPermissionIsForbidden() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-nopermission@example.com", "AdminPass1!");

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-nopermission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"blocked@example.com\",\"role\":\"MUSICIAN\","
                                + "\"minor\":false,\"consentOnFile\":false}"))
                .andExpect(status().isForbidden());

        assertThat(userAccountRepository.findByEmail("blocked@example.com")).isEmpty();
    }

    @Test
    void createMinorWithoutGuardianContactOrConsentIsRejected() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-minor@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-minor@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"minor-nogc@example.com\",\"role\":\"MUSICIAN\",\"minor\":true,"
                                + "\"consentOnFile\":false}"))
                .andExpect(status().isBadRequest());

        assertThat(userAccountRepository.findByEmail("minor-nogc@example.com")).isEmpty();
    }

    @Test
    void createMinorWithGuardianContactAndConsentSucceeds() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-minor-ok@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-minor-ok@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"minor-ok@example.com\",\"role\":\"MUSICIAN\",\"minor\":true,"
                                + "\"guardianContact\":\"Parent Name +54911...\",\"consentOnFile\":true}"))
                .andExpect(status().isCreated());

        UserAccount created = userAccountRepository.findByEmail("minor-ok@example.com").orElseThrow();
        assertThat(created.isMinor()).isTrue();
        assertThat(created.getGuardianContact()).isEqualTo("Parent Name +54911...");
        assertThat(created.isConsentOnFile()).isTrue();
    }

    @Test
    void editWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-edit@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("edit-target@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-edit@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"edited@example.com\",\"role\":\"MUSICIAN\","
                                + "\"minor\":false,\"consentOnFile\":false}"))
                .andExpect(status().isOk());

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "UserAccount", target.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("USER_UPDATED");
    }

    @Test
    void deactivateWritesAnAuditRecordAndImmediatelyBlocksLogin() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-deactivate@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        UserAccount target = new UserAccount("to-deactivate@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        target.setPasswordHash(passwordEncoder.encode("TargetPass1!"));
        target = userAccountRepository.saveAndFlush(target);

        Cookie csrf = fetchCsrfCookie();
        Cookie adminAccessToken = loginAndGetAccessTokenCookie("admin-deactivate@example.com", "AdminPass1!", csrf);

        // Prove the target can log in before deactivation.
        loginAndGetAccessTokenCookie("to-deactivate@example.com", "TargetPass1!", csrf);

        mockMvc.perform(post("/api/users/" + target.getId() + "/deactivate")
                        .cookie(csrf, adminAccessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "UserAccount", target.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("USER_DEACTIVATED");

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"to-deactivate@example.com\",\"password\":\"TargetPass1!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivateOfAnUnknownUserReturnsNotFound() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-404@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-404@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users/999999/deactivate")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminRoleIsRejectedAtTheCoarseGateBeforeReachingTheService() throws Exception {
        UserAccount musician = new UserAccount("plain-musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        musician.setPasswordHash(passwordEncoder.encode("MusicianPass1!"));
        userAccountRepository.saveAndFlush(musician);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("plain-musician@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
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
