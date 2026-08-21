package com.banda.users;

import com.banda.audit.AuditLog;
import com.banda.audit.AuditLogRepository;
import com.banda.common.EmailSender;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    private PasswordTokenRepository passwordTokenRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailSender emailSender;

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
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":"
                                + target.getVersion() + "}"))
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

    // ---- ADMIN role WITHOUT the MANAGE_USERS permission toggle: real end-to-end denial ----
    // (previously only proven for create(); edit/deactivate/get/list only had mock-level proof)

    @Test
    void editByAnAdminLackingManageUsersPermissionIsForbidden() throws Exception {
        persistActiveAdmin("admin-edit-noperm@example.com", "AdminPass1!");
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("edit-target-noperm@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-edit-noperm@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"wont-happen@example.com\",\"role\":\"MUSICIAN\","
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":"
                                + target.getVersion() + "}"))
                .andExpect(status().isForbidden());

        assertThat(userAccountRepository.findById(target.getId()).orElseThrow().getEmail())
                .isEqualTo("edit-target-noperm@example.com");
    }

    @Test
    void deactivateByAnAdminLackingManageUsersPermissionIsForbidden() throws Exception {
        persistActiveAdmin("admin-deactivate-noperm@example.com", "AdminPass1!");
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("deactivate-target-noperm@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-deactivate-noperm@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users/" + target.getId() + "/deactivate")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());

        assertThat(userAccountRepository.findById(target.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void getByAnAdminLackingManageUsersPermissionIsForbidden() throws Exception {
        persistActiveAdmin("admin-get-noperm@example.com", "AdminPass1!");
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("get-target-noperm@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-get-noperm@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listByAnAdminLackingManageUsersPermissionIsForbidden() throws Exception {
        persistActiveAdmin("admin-list-noperm@example.com", "AdminPass1!");

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-list-noperm@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
    }

    // ---- GET /api/users and GET /api/users/{id}: real HTTP + real serialization ----

    @Test
    void listReturnsAllUsersWithRealSerializationForAnAdminHoldingManageUsersPermission() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-list-ok@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        UserAccount other = userAccountRepository.saveAndFlush(
                new UserAccount("listed-musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-list-ok@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + other.getId() + ")].email").value("listed-musician@example.com"))
                .andExpect(jsonPath("$[?(@.id == " + other.getId() + ")].version").exists())
                .andExpect(jsonPath("$[?(@.id == " + admin.getId() + ")].email").value("admin-list-ok@example.com"));
    }

    @Test
    void listOnATableClearedOfAllOtherUsersReturnsOnlyTheCallingAdmin() throws Exception {
        // IntegrationTestBase's shared, never-torn-down Testcontainers Postgres means a truly
        // empty result set is unreachable through this admin-panel surface — the querying
        // actor's own account always exists (it must, to authenticate). This is the closest
        // achievable proxy for the empty-list case: clear every other row first, then prove
        // the endpoint reflects that (size 1, only the caller) rather than leaking anything
        // stale — the same real-HTTP/real-serialization path a truly empty result would use.
        passwordTokenRepository.deleteAll();
        adminPermissionRepository.deleteAll();
        userAccountRepository.deleteAll();
        UserAccount admin = persistActiveAdmin("admin-list-empty@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-list-empty@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("admin-list-empty@example.com"));
    }

    @Test
    void getByIdReturnsTheAccountWithRealSerializationForAnAdminHoldingManageUsersPermission() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-get-ok@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("get-target-ok@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-get-ok@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId()))
                .andExpect(jsonPath("$.email").value("get-target-ok@example.com"))
                .andExpect(jsonPath("$.role").value("MUSICIAN"))
                .andExpect(jsonPath("$.version").value(target.getVersion()));
    }

    @Test
    void getByIdOnANonexistentIdReturnsNotFound() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-get-404@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-get-404@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/users/999999")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
    }

    // ---- Privilege-escalation hardening: MANAGE_ADMIN_ROLES gate + self-target block ----

    @Test
    void createOfAnAdminRoleByAnAdminLackingManageAdminRolesPermissionIsForbidden() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-create-noadminrole@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-create-noadminrole@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"blocked-admin@example.com\",\"role\":\"ADMIN\","
                                + "\"minor\":false,\"consentOnFile\":false}"))
                .andExpect(status().isForbidden());

        assertThat(userAccountRepository.findByEmail("blocked-admin@example.com")).isEmpty();
    }

    @Test
    void editPromotingToAdminByAnAdminLackingManageAdminRolesPermissionIsForbidden() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-edit-noadminrole@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("promote-target@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-edit-noadminrole@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"promote-target@example.com\",\"role\":\"ADMIN\","
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":"
                                + target.getVersion() + "}"))
                .andExpect(status().isForbidden());

        assertThat(userAccountRepository.findById(target.getId()).orElseThrow().getRole()).isEqualTo(UserRole.MUSICIAN);
    }

    @Test
    void editPromotingToAdminByAnAdminHoldingBothPermissionsSucceeds() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-edit-withadminrole@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_ADMIN_ROLES));
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("promote-target-ok@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-edit-withadminrole@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"promote-target-ok@example.com\",\"role\":\"ADMIN\","
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":"
                                + target.getVersion() + "}"))
                .andExpect(status().isOk());

        assertThat(userAccountRepository.findById(target.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void selfEditIsForbiddenEvenForAnAdminHoldingEveryPermission() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-self-edit@example.com", "AdminPass1!");
        for (Permission permission : Permission.values()) {
            adminPermissionRepository.saveAndFlush(new AdminPermission(admin, permission));
        }

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-self-edit@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/users/" + admin.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"self-changed@example.com\",\"role\":\"ADMIN\","
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":"
                                + admin.getVersion() + "}"))
                .andExpect(status().isForbidden());

        assertThat(userAccountRepository.findById(admin.getId()).orElseThrow().getEmail())
                .isEqualTo("admin-self-edit@example.com");
    }

    @Test
    void selfDeactivateIsForbiddenEvenForAnAdminHoldingEveryPermission() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-self-deactivate@example.com", "AdminPass1!");
        for (Permission permission : Permission.values()) {
            adminPermissionRepository.saveAndFlush(new AdminPermission(admin, permission));
        }

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-self-deactivate@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users/" + admin.getId() + "/deactivate")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());

        assertThat(userAccountRepository.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void doubleDeactivateIsIdempotentAndWritesOnlyOneAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-double-deactivate@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("double-deactivate-target@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-double-deactivate@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/users/" + target.getId() + "/deactivate")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());
        long tokenVersionAfterFirstCall = userAccountRepository.findById(target.getId()).orElseThrow().getTokenVersion();

        mockMvc.perform(post("/api/users/" + target.getId() + "/deactivate")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());

        UserAccount reloaded = userAccountRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(reloaded.getTokenVersion()).isEqualTo(tokenVersionAfterFirstCall);

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "UserAccount", target.getId());
        assertThat(history).hasSize(1);
    }

    @Test
    void manageUsersAloneCannotReplaceAnotherAdminsEmailAndChainAPasswordReset() throws Exception {
        UserAccount attacker = persistActiveAdmin("takeover-actor@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(attacker, Permission.MANAGE_USERS));
        UserAccount victim = persistActiveAdmin("takeover-victim@example.com", "VictimPass1!");

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("takeover-actor@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/users/" + victim.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"takeover-mailbox@example.com\",\"role\":\"ADMIN\","
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":"
                                + victim.getVersion() + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"takeover-mailbox@example.com\"}"))
                .andExpect(status().isAccepted());

        assertThat(userAccountRepository.findById(victim.getId()).orElseThrow().getEmail())
                .isEqualTo("takeover-victim@example.com");
        verifyNoInteractions(emailSender);
    }

    @Test
    void sequentialStaleUserEditReturnsConflictAndPreservesTheWinningWrite() throws Exception {
        UserAccount admin = persistActiveAdmin("stale-user-admin@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_USERS));
        UserAccount target = userAccountRepository.saveAndFlush(
                new UserAccount("stale-user-target@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW));
        long staleVersion = target.getVersion();
        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("stale-user-admin@example.com", "AdminPass1!", csrf);

        mockMvc.perform(put("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"winning-user-write@example.com\",\"role\":\"MUSICIAN\","
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":" + staleVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(staleVersion + 1));

        mockMvc.perform(put("/api/users/" + target.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"losing-user-write@example.com\",\"role\":\"MUSICIAN\","
                                + "\"minor\":false,\"consentOnFile\":false,\"version\":" + staleVersion + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        assertThat(userAccountRepository.findById(target.getId()).orElseThrow().getEmail())
                .isEqualTo("winning-user-write@example.com");
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
