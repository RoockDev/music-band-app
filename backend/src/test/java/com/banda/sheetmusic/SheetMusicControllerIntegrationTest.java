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
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 5 (Sheet Music) upload endpoint end-to-end: the RBAC gate (MANAGE_SHEET_MUSIC,
 * Sec.2/Sec.10), the audit trail (Sec.11), {@code storageKey} persistence (task 6.2's own
 * test focus), and — per the same task's explicit requirement — proof that no static
 * resource mapping exposes an uploaded file directly, only the gated download endpoint
 * (task 6.3, covered by {@link SheetMusicDownloadControllerIntegrationTest}) can ever serve
 * its bytes.
 */
@AutoConfigureMockMvc
@Import(SheetMusicControllerIntegrationTest.FixedClockConfig.class)
class SheetMusicControllerIntegrationTest extends IntegrationTestBase {

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
    private SheetMusicRepository sheetMusicRepository;

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
    void uploadByAnAdminHoldingManageSheetMusicPermissionSucceedsPersistsStorageKeyAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-upload@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        Collection collection = collectionRepository.saveAndFlush(new Collection("Marches", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-upload@example.com", "AdminPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "radetzky.pdf", "application/pdf", "fake pdf bytes".getBytes());

        MvcResult result = mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Radetzky March")
                        .param("composer", "Johann Strauss I")
                        .param("collectionId", collection.getId().toString())
                        .param("allScope", "true")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Radetzky March");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("storageKey");

        SheetMusic saved = sheetMusicRepository.findAll().stream()
                .filter(sm -> sm.getTitle().equals("Radetzky March")).findFirst().orElseThrow();
        assertThat(saved.getStorageKey()).isNotBlank();
        assertThat(saved.getStorageKey()).doesNotContain("radetzky.pdf");

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "SheetMusic", saved.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("SHEET_MUSIC_UPLOADED");
        assertThat(history.get(0).getActorId()).isEqualTo(admin.getId());
    }

    @Test
    void uploadByAnAdminLackingManageSheetMusicPermissionIsForbidden() throws Exception {
        persistActiveAdmin("admin-upload-nopermission@example.com", "AdminPass1!");
        Collection collection = collectionRepository.saveAndFlush(new Collection("Anthems", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-upload-nopermission@example.com", "AdminPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "anthem.pdf", "application/pdf", "bytes".getBytes());

        mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Blocked Upload")
                        .param("collectionId", collection.getId().toString())
                        .param("allScope", "false")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());

        assertThat(sheetMusicRepository.findAll().stream().anyMatch(sm -> sm.getTitle().equals("Blocked Upload"))).isFalse();
    }

    @Test
    void uploadWithAnUnknownCollectionIdReturnsNotFound() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-upload-404@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-upload-404@example.com", "AdminPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "f.pdf", "application/pdf", "bytes".getBytes());

        mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Orphan Upload")
                        .param("collectionId", "999999")
                        .param("allScope", "false")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMusicianCannotUploadEvenWithoutTryingSincePermissionIsAdminOnly() throws Exception {
        UserAccount musician = new UserAccount("plain-musician-upload@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        musician.setPasswordHash(passwordEncoder.encode("MusicianPass1!"));
        userAccountRepository.saveAndFlush(musician);
        Collection collection = collectionRepository.saveAndFlush(new Collection("Solos", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("plain-musician-upload@example.com", "MusicianPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "f.pdf", "application/pdf", "bytes".getBytes());

        mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Musician Attempt")
                        .param("collectionId", collection.getId().toString())
                        .param("allScope", "false")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden());
    }

    /** Task 6.2's explicit "no static resource mapping exists" requirement: a raw
     * {@code storageKey} is never itself a resolvable URL path — only
     * {@code GET /api/sheet-music/{id}/file} (task 6.3) can ever serve the bytes. */
    @Test
    void theRawStorageKeyIsNeverResolvableAsAStaticUrlPath() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-static-bypass@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        Collection collection = collectionRepository.saveAndFlush(new Collection("Bypass Test", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-static-bypass@example.com", "AdminPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "secret.pdf", "application/pdf", "secret bytes".getBytes());

        mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Bypass Attempt")
                        .param("collectionId", collection.getId().toString())
                        .param("allScope", "false")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isCreated());

        SheetMusic saved = sheetMusicRepository.findAll().stream()
                .filter(sm -> sm.getTitle().equals("Bypass Attempt")).findFirst().orElseThrow();

        mockMvc.perform(get("/" + saved.getStorageKey())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/files/" + saved.getStorageKey())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/static/" + saved.getStorageKey())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
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
