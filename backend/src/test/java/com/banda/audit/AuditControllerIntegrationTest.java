package com.banda.audit;

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

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers Section 11's read path: a queryable, chronological, admin-only per-resource
 * history endpoint. Write path (record) is covered by {@link AuditServiceTest}.
 */
@AutoConfigureMockMvc
class AuditControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.now();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    private Cookie loginAndGetAccessTokenCookie(String email, String password) throws Exception {
        Cookie csrf = fetchCsrfCookie();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getCookie("ACCESS_TOKEN");
    }

    @Test
    void adminSeesHistoryOrderedNewestFirstWithFullJsonContract() throws Exception {
        UserAccount admin = new UserAccount("audit-admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        admin.setPasswordHash(passwordEncoder.encode("AdminPass1!"));
        userAccountRepository.saveAndFlush(admin);

        AuditLog created = auditLogRepository.saveAndFlush(
                new AuditLog(admin.getId(), "CREATED", "SheetMusic", 55L, null, NOW));
        AuditLog updated = auditLogRepository.saveAndFlush(
                new AuditLog(admin.getId(), "UPDATED", "SheetMusic", 55L, "changed title",
                        NOW.plus(Duration.ofMinutes(5))));

        Cookie accessToken = loginAndGetAccessTokenCookie("audit-admin@example.com", "AdminPass1!");

        mockMvc.perform(get("/api/audit/SheetMusic/55").cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Newest first: UPDATED (later timestamp) before CREATED.
                .andExpect(jsonPath("$[0].id").value(updated.getId().intValue()))
                .andExpect(jsonPath("$[0].actorId").value(admin.getId().intValue()))
                .andExpect(jsonPath("$[0].action").value("UPDATED"))
                .andExpect(jsonPath("$[0].entityType").value("SheetMusic"))
                .andExpect(jsonPath("$[0].entityId").value(55))
                .andExpect(jsonPath("$[0].details").value("changed title"))
                .andExpect(jsonPath("$[0].timestamp").exists())
                .andExpect(jsonPath("$[1].id").value(created.getId().intValue()))
                .andExpect(jsonPath("$[1].actorId").value(admin.getId().intValue()))
                .andExpect(jsonPath("$[1].action").value("CREATED"))
                .andExpect(jsonPath("$[1].entityType").value("SheetMusic"))
                .andExpect(jsonPath("$[1].entityId").value(55))
                .andExpect(jsonPath("$[1].details").value(nullValue()))
                .andExpect(jsonPath("$[1].timestamp").exists());
    }

    @Test
    void adminSeesEmptyArrayForAnEntityWithNoAuditRecords() throws Exception {
        UserAccount admin = new UserAccount("audit-admin-empty@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        admin.setPasswordHash(passwordEncoder.encode("AdminPass1!"));
        userAccountRepository.saveAndFlush(admin);

        Cookie accessToken = loginAndGetAccessTokenCookie("audit-admin-empty@example.com", "AdminPass1!");

        mockMvc.perform(get("/api/audit/SheetMusic/999999").cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nonAdminIsForbiddenFromHistory() throws Exception {
        UserAccount musician = new UserAccount("audit-musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        musician.setPasswordHash(passwordEncoder.encode("MusicianPass1!"));
        userAccountRepository.saveAndFlush(musician);

        Cookie accessToken = loginAndGetAccessTokenCookie("audit-musician@example.com", "MusicianPass1!");

        mockMvc.perform(get("/api/audit/SheetMusic/55").cookie(accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/audit/SheetMusic/55"))
                .andExpect(status().isUnauthorized());
    }
}
