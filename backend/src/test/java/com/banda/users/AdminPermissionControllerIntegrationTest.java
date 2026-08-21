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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminPermissionControllerIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "AdminPass1!";

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

    @Test
    void managerCanReadAnAdminPermissionSetButNotAMusicianSet() throws Exception {
        UserAccount actor = admin("permission-reader@example.com");
        grantDirectly(actor, Permission.MANAGE_ADMIN_ROLES);
        UserAccount target = admin("permission-read-target@example.com");
        grantDirectly(target, Permission.MANAGE_USERS);
        grantDirectly(target, Permission.MANAGE_EVENTS);
        UserAccount musician = musician("permission-read-musician@example.com");
        Session session = login(actor);

        mockMvc.perform(get("/api/users/" + target.getId() + "/permissions")
                        .cookie(session.csrf(), session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(target.getId()))
                .andExpect(jsonPath("$.permissions[0]").value("MANAGE_EVENTS"))
                .andExpect(jsonPath("$.permissions[1]").value("MANAGE_USERS"));

        mockMvc.perform(get("/api/users/" + musician.getId() + "/permissions")
                        .cookie(session.csrf(), session.accessToken()))
                .andExpect(status().isConflict());
    }

    @Test
    void adminWithoutManagementPermissionCannotProbeWhetherATargetExists() throws Exception {
        UserAccount actor = admin("permission-no-gate@example.com");
        UserAccount target = admin("permission-hidden-target@example.com");
        Session session = login(actor);

        mockMvc.perform(get("/api/users/" + target.getId() + "/permissions")
                        .cookie(session.csrf(), session.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/999999/permissions")
                        .cookie(session.csrf(), session.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void musicianIsRejectedByTheCoarseRoleGate() throws Exception {
        UserAccount actor = musician("permission-musician-actor@example.com");
        UserAccount target = admin("permission-musician-target@example.com");
        Session session = login(actor);

        mockMvc.perform(get("/api/users/" + target.getId() + "/permissions")
                        .cookie(session.csrf(), session.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void grantAndRevokeAreIdempotentAndAuditOnlyRealChanges() throws Exception {
        UserAccount actor = admin("permission-mutator@example.com");
        grantDirectly(actor, Permission.MANAGE_ADMIN_ROLES);
        UserAccount target = admin("permission-mutation-target@example.com");
        Session session = login(actor);
        String path = "/api/users/" + target.getId() + "/permissions/MANAGE_GROUPS";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put(path)
                            .cookie(session.csrf(), session.accessToken())
                            .header("X-XSRF-TOKEN", session.csrf().getValue()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions[0]").value("MANAGE_GROUPS"));
        }

        assertThat(adminPermissionRepository.findByAdmin(target))
                .extracting(AdminPermission::getPermission)
                .containsExactly(Permission.MANAGE_GROUPS);
        assertAudit(target, "ADMIN_PERMISSION_GRANTED", 1);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete(path)
                            .cookie(session.csrf(), session.accessToken())
                            .header("X-XSRF-TOKEN", session.csrf().getValue()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions.length()").value(0));
        }

        assertThat(adminPermissionRepository.findByAdmin(target)).isEmpty();
        assertAudit(target, "ADMIN_PERMISSION_REVOKED", 1);
    }

    @Test
    void mutationsRequireCsrfAndAValidPermissionEnum() throws Exception {
        UserAccount actor = admin("permission-csrf@example.com");
        grantDirectly(actor, Permission.MANAGE_ADMIN_ROLES);
        UserAccount target = admin("permission-csrf-target@example.com");
        Session session = login(actor);

        mockMvc.perform(put("/api/users/" + target.getId() + "/permissions/MANAGE_EVENTS")
                        .cookie(session.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/users/" + target.getId() + "/permissions/MANAGE_EVENTS")
                        .cookie(session.accessToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/users/" + target.getId() + "/permissions/not-a-permission")
                        .cookie(session.csrf(), session.accessToken())
                        .header("X-XSRF-TOKEN", session.csrf().getValue()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selfTargetAllowsOrdinaryChangesButBlocksOwnManagerPermissionRevocation() throws Exception {
        UserAccount actor = admin("permission-self@example.com");
        grantDirectly(actor, Permission.MANAGE_ADMIN_ROLES);
        Session session = login(actor);

        mockMvc.perform(put("/api/users/" + actor.getId() + "/permissions/MANAGE_CONTENT")
                        .cookie(session.csrf(), session.accessToken())
                        .header("X-XSRF-TOKEN", session.csrf().getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray());

        mockMvc.perform(delete("/api/users/" + actor.getId() + "/permissions/MANAGE_ADMIN_ROLES")
                        .cookie(session.csrf(), session.accessToken())
                        .header("X-XSRF-TOKEN", session.csrf().getValue()))
                .andExpect(status().isConflict());

        assertThat(adminPermissionRepository.existsByAdminAndPermission(
                actor, Permission.MANAGE_ADMIN_ROLES)).isTrue();
    }

    @Test
    void authorizedManagerGetsNotFoundForAnUnknownTarget() throws Exception {
        UserAccount actor = admin("permission-404@example.com");
        grantDirectly(actor, Permission.MANAGE_ADMIN_ROLES);
        Session session = login(actor);

        mockMvc.perform(get("/api/users/999999/permissions")
                        .cookie(session.csrf(), session.accessToken()))
                .andExpect(status().isNotFound());
    }

    private UserAccount admin(String email) {
        return account(email, UserRole.ADMIN);
    }

    private UserAccount musician(String email) {
        return account(email, UserRole.MUSICIAN);
    }

    private UserAccount account(String email, UserRole role) {
        UserAccount account = new UserAccount(email, role, UserStatus.ACTIVE, Instant.now());
        account.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userAccountRepository.saveAndFlush(account);
    }

    private void grantDirectly(UserAccount admin, Permission permission) {
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, permission));
    }

    private Session login(UserAccount account) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/auth/csrf"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"" + account.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(csrf, login.getResponse().getCookie(SecurityConstants.ACCESS_TOKEN_COOKIE));
    }

    private void assertAudit(UserAccount target, String action, int expected) {
        List<AuditLog> entries = auditLogRepository
                .findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc("UserAccount", target.getId())
                .stream()
                .filter(entry -> entry.getAction().equals(action))
                .toList();
        assertThat(entries).hasSize(expected);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.getDetails()).isEqualTo("permission=MANAGE_GROUPS");
            assertThat(entry.getActorId()).isNotNull();
        });
    }

    private record Session(Cookie csrf, Cookie accessToken) {
    }
}
