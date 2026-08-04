package com.banda.groups;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 4 (Groups) end-to-end: the RBAC gate (MANAGE_GROUPS, enforced independent of the
 * base ADMIN role — Sec.2/Sec.10) and the audit trail (Sec.11) around every mutation, plus
 * the core deliverable of this PR — {@link #deletingAGroupWithMembersReturnsConflictAndRequiresReassignmentFirst}
 * proving the "Delete in-use group" scenario end-to-end through real HTTP.
 */
@AutoConfigureMockMvc
@Import(GroupControllerIntegrationTest.FixedClockConfig.class)
class GroupControllerIntegrationTest extends IntegrationTestBase {

    static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private MusicianGroupRepository musicianGroupRepository;

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

    // ---- create() ----

    @Test
    void createByAnAdminHoldingManageGroupsPermissionSucceedsAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-create@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-create@example.com", "AdminPass1!", csrf);

        MvcResult result = mockMvc.perform(post("/api/groups")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Brass Section\",\"description\":\"Trumpets\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Brass Section");
        Group created = groupRepository.findAll().stream()
                .filter(g -> g.getName().equals("Brass Section")).findFirst().orElseThrow();

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Group", created.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("GROUP_CREATED");
        assertThat(history.get(0).getActorId()).isEqualTo(admin.getId());
    }

    @Test
    void createByAnAdminLackingManageGroupsPermissionIsForbidden() throws Exception {
        persistActiveAdmin("admin-nopermission@example.com", "AdminPass1!");

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-nopermission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/groups")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Blocked Group\"}"))
                .andExpect(status().isForbidden());

        assertThat(groupRepository.findAll().stream().anyMatch(g -> g.getName().equals("Blocked Group"))).isFalse();
    }

    @Test
    void nonAdminRoleIsRejectedAtTheCoarseGateBeforeReachingTheService() throws Exception {
        UserAccount musician = new UserAccount("plain-musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        musician.setPasswordHash(passwordEncoder.encode("MusicianPass1!"));
        userAccountRepository.saveAndFlush(musician);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("plain-musician@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/groups")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
    }

    // ---- edit() ----

    @Test
    void editWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-edit@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));
        Group target = groupRepository.saveAndFlush(new Group("Original Name", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-edit@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/groups/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed Group\",\"description\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Group"));

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Group", target.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("GROUP_UPDATED");
    }

    @Test
    void editOfAnUnknownGroupReturnsNotFound() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-edit-404@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-edit-404@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/groups/999999")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Doesn't matter\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- delete() — the core deliverable of this PR ----

    @Test
    void deletingAnEmptyGroupSucceedsAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-delete@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));
        Group target = groupRepository.saveAndFlush(new Group("Empty Group", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-delete@example.com", "AdminPass1!", csrf);

        mockMvc.perform(delete("/api/groups/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());

        assertThat(groupRepository.findById(target.getId())).isEmpty();
        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Group", target.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("GROUP_DELETED");
    }

    @Test
    void deletingAGroupWithMembersReturnsConflictAndRequiresReassignmentFirst() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-delete-inuse@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));
        Group target = groupRepository.saveAndFlush(new Group("Occupied Group", null, FIXED_NOW));
        UserAccount member = userAccountRepository.saveAndFlush(
                new UserAccount("member-inuse@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(member, target));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-delete-inuse@example.com", "AdminPass1!", csrf);

        // Delete is rejected while the group still has a member — no silent orphaned scope.
        mockMvc.perform(delete("/api/groups/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isConflict());

        assertThat(groupRepository.findById(target.getId())).isPresent();
        assertThat(musicianGroupRepository.existsByMusicianAndGroup(member, target)).isTrue();

        // Explicit reassignment (unassign) first...
        mockMvc.perform(delete("/api/groups/" + target.getId() + "/musicians/" + member.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());

        // ...then the delete succeeds.
        mockMvc.perform(delete("/api/groups/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());

        assertThat(groupRepository.findById(target.getId())).isEmpty();
    }

    @Test
    void deletingAnUnknownGroupReturnsNotFound() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-delete-404@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-delete-404@example.com", "AdminPass1!", csrf);

        mockMvc.perform(delete("/api/groups/999999")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
    }

    // ---- assign/unassign musicians ----

    @Test
    void assigningAMusicianGrantsGroupScopedAccessVisibleThroughTheMembersEndpointAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-assign@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));
        Group group = groupRepository.saveAndFlush(new Group("Choir", null, FIXED_NOW));
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-assign-e2e@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-assign@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/groups/" + group.getId() + "/musicians/" + musician.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/groups/" + group.getId() + "/musicians")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("musician-assign-e2e@example.com"));

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Group", group.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("MUSICIAN_ASSIGNED_TO_GROUP");
    }

    @Test
    void assigningAnUnknownMusicianReturnsNotFound() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-assign-404@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));
        Group group = groupRepository.saveAndFlush(new Group("Choir 2", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-assign-404@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/groups/" + group.getId() + "/musicians/999999")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
    }

    @Test
    void doubleAssignIsIdempotentAndWritesOnlyOneAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-double-assign@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));
        Group group = groupRepository.saveAndFlush(new Group("Choir 3", null, FIXED_NOW));
        UserAccount musician = userAccountRepository.saveAndFlush(
                new UserAccount("musician-double-assign@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-double-assign@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/groups/" + group.getId() + "/musicians/" + musician.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/groups/" + group.getId() + "/musicians/" + musician.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Group", group.getId());
        assertThat(history).hasSize(1);
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
