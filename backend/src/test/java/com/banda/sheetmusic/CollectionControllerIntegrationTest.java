package com.banda.sheetmusic;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 6 minimal list/create surface (reliability fix: before this PR, no
 * {@code CollectionController} existed anywhere, so the upload flow's hard-required
 * {@code collectionId} had no real end-to-end path to obtain one). Covers the RBAC gate
 * (MANAGE_SHEET_MUSIC, Sec.2/Sec.10) and the audit trail (Sec.11), mirroring
 * {@code GroupControllerIntegrationTest}'s own {@code create()} coverage shape.
 */
@AutoConfigureMockMvc
@Import(CollectionControllerIntegrationTest.FixedClockConfig.class)
class CollectionControllerIntegrationTest extends IntegrationTestBase {

    static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SheetMusicRepository sheetMusicRepository;

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

    /** Collision-proof: reads the persisted row's id directly from the create response body
     * instead of locating it by a literal name in the shared Testcontainers Postgres table,
     * which other {@code IntegrationTestBase}-extending test classes can also write rows into
     * for the same entity type. Every create endpoint already returns the persisted {@code id},
     * so this needs no naming convention to remember. */
    private Long extractId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    @Test
    void createByAnAdminHoldingManageSheetMusicPermissionSucceedsAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-collection-create@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-collection-create@example.com", "AdminPass1!", csrf);

        MvcResult result = mockMvc.perform(post("/api/collections")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Marches\",\"description\":\"Brass band marches\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Marches");
        Long createdId = extractId(result);

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Collection", createdId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("COLLECTION_CREATED");
        assertThat(history.get(0).getActorId()).isEqualTo(admin.getId());
    }

    @Test
    void createByAnAdminLackingManageSheetMusicPermissionIsForbidden() throws Exception {
        persistActiveAdmin("admin-collection-nopermission@example.com", "AdminPass1!");

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-collection-nopermission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/collections")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Blocked Collection\"}"))
                .andExpect(status().isForbidden());

        assertThat(collectionRepository.findAll().stream().anyMatch(c -> c.getName().equals("Blocked Collection"))).isFalse();
    }

    @Test
    void aMusicianCannotCreateACollectionSinceThePathIsAdminOnly() throws Exception {
        UserAccount musician = new UserAccount("plain-musician-collection@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        musician.setPasswordHash(passwordEncoder.encode("MusicianPass1!"));
        userAccountRepository.saveAndFlush(musician);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("plain-musician-collection@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(post("/api/collections")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Musician Attempt\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsCollectionMetadataToAnAdminHoldingManageSheetMusicPermission() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-collection-list@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        Collection collection = collectionRepository.saveAndFlush(
                new Collection("Catalog Collection", "Catalog description", FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-collection-list@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/collections").cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + collection.getId() + ")].name").value("Catalog Collection"))
                .andExpect(jsonPath("$[?(@.id == " + collection.getId() + ")].description")
                        .value("Catalog description"));
    }

    @Test
    void listIsForbiddenToAnAdminLackingManageSheetMusicPermission() throws Exception {
        persistActiveAdmin("admin-collection-list-no-permission@example.com", "AdminPass1!");
        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie(
                "admin-collection-list-no-permission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(get("/api/collections").cookie(accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void listIsForbiddenToAMusicianByTheAdminRoleGate() throws Exception {
        UserAccount musician = new UserAccount(
                "musician-collection-list@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        musician.setPasswordHash(passwordEncoder.encode("MusicianPass1!"));
        userAccountRepository.saveAndFlush(musician);
        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie(
                "musician-collection-list@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/collections").cookie(accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateUsesVersionAndDeleteIsBlockedUntilTheCollectionIsEmpty() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-collection-lifecycle@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        Collection collection = collectionRepository.saveAndFlush(new Collection("Original", null, FIXED_NOW));
        Long originalVersion = collection.getVersion();
        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie(
                "admin-collection-lifecycle@example.com", "AdminPass1!", csrf);

        MvcResult update = mockMvc.perform(put("/api/collections/" + collection.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed\",\"description\":\"Updated\",\"version\":"
                                + originalVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andReturn();
        Number updatedVersion = JsonPath.read(update.getResponse().getContentAsString(), "$.version");

        mockMvc.perform(put("/api/collections/" + collection.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Stale\",\"version\":" + originalVersion + "}"))
                .andExpect(status().isConflict());

        Collection managed = collectionRepository.findById(collection.getId()).orElseThrow();
        SheetMusic score = sheetMusicRepository.saveAndFlush(new SheetMusic("Score", null, managed, "key-"
                + collection.getId(), "score.pdf", "application/pdf", true, FIXED_NOW));

        mockMvc.perform(delete("/api/collections/" + collection.getId())
                        .queryParam("version", updatedVersion.toString())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isConflict());

        sheetMusicRepository.delete(score);
        sheetMusicRepository.flush();
        mockMvc.perform(delete("/api/collections/" + collection.getId())
                        .queryParam("version", updatedVersion.toString())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
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
