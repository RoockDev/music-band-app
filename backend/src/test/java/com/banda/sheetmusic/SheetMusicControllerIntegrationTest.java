package com.banda.sheetmusic;

import com.banda.audit.AuditLog;
import com.banda.audit.AuditLogRepository;
import com.banda.common.FileStorage;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    @Autowired
    private FileStorage fileStorage;

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
     * instead of locating it by a literal title in the shared Testcontainers Postgres table,
     * which other {@code IntegrationTestBase}-extending test classes can also write rows into
     * for the same entity type. Every create endpoint already returns the persisted {@code id},
     * so this needs no naming convention to remember. */
    private Long extractId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
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

        Long createdId = extractId(result);
        SheetMusic saved = sheetMusicRepository.findById(createdId).orElseThrow();
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

    /** Section 5 upload guard: a disallowed content-type is rejected with 400 before it ever
     * reaches disk — the fix for the CRITICAL finding that an unvalidated content-type could
     * be stored verbatim and later crash every download of that file. */
    @Test
    void uploadWithADisallowedContentTypeIsRejectedWithBadRequest() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-upload-badtype@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        Collection collection = collectionRepository.saveAndFlush(new Collection("Bad Types", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-upload-badtype@example.com", "AdminPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "malware.exe", "application/x-msdownload", "bytes".getBytes());

        mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Rejected Upload")
                        .param("collectionId", collection.getId().toString())
                        .param("allScope", "false")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isBadRequest());

        assertThat(sheetMusicRepository.findAll().stream().anyMatch(sm -> sm.getTitle().equals("Rejected Upload"))).isFalse();
    }

    /** Resilience fix: {@code fileStorage.store} runs before the group/musician access-scope
     * is applied, outside the DB transaction's control -- a failed access-scope application
     * (bad group id here) must not leave the already-written file orphaned on disk. */
    @Test
    void aFailedGroupAccessScopeApplicationDoesNotLeaveAnOrphanedFileOnDisk() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-upload-orphan@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        Collection collection = collectionRepository.saveAndFlush(new Collection("Orphan Guard", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-upload-orphan@example.com", "AdminPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "f.pdf", "application/pdf", "bytes".getBytes());

        Path baseDir = (Path) ReflectionTestUtils.getField(fileStorage, "baseDir");
        long filesBefore = countFilesInBaseDir(baseDir);

        mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Orphan Guard Attempt")
                        .param("collectionId", collection.getId().toString())
                        .param("allScope", "false")
                        .param("groupIds", "999999")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());

        assertThat(sheetMusicRepository.findAll().stream().anyMatch(sm -> sm.getTitle().equals("Orphan Guard Attempt"))).isFalse();
        assertThat(countFilesInBaseDir(baseDir)).isEqualTo(filesBefore);
    }

    private static long countFilesInBaseDir(Path baseDir) throws Exception {
        try (var paths = Files.list(baseDir)) {
            return paths.count();
        }
    }

    /** Reliability/resilience test-gap fix: every other multipart test sends {@code allScope}
     * explicitly ({@code "true"}/{@code "false"}); the real bug this PR fixed (a primitive
     * {@code boolean} rejecting a real HTML checkbox's "field entirely absent" submission with
     * a 400) is only otherwise proven via {@link SheetMusicServiceTest}'s hand-constructed
     * {@code UploadSheetMusicRequest}, which bypasses Spring's data binder entirely. This test
     * goes through the real binder by never calling {@code .param("allScope", ...)} at all --
     * the actual scenario an unchecked HTML checkbox produces. */
    @Test
    void uploadWithTheAllScopeParameterOmittedEntirelySucceedsAndTreatsItAsFalse() throws Exception {
        UserAccount admin = persistActiveAdmin("admin-upload-noallscope@example.com", "AdminPass1!");
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        Collection collection = collectionRepository.saveAndFlush(new Collection("Checkbox Omitted", null, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-upload-noallscope@example.com", "AdminPass1!", csrf);
        MockMultipartFile file = new MockMultipartFile("file", "unchecked.pdf", "application/pdf", "bytes".getBytes());

        MvcResult result = mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Unchecked Checkbox Upload")
                        .param("collectionId", collection.getId().toString())
                        // Deliberately no .param("allScope", ...) call at all -- an unchecked
                        // HTML checkbox submits no field, never a literal "false".
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isCreated())
                .andReturn();

        Long createdId = extractId(result);
        SheetMusic saved = sheetMusicRepository.findById(createdId).orElseThrow();
        assertThat(saved.isAllScope()).isFalse();
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

    @Test
    void listReturnsMetadataOnlyForSheetMusicAccessibleToTheAuthenticatedActor() throws Exception {
        UserAccount musician = new UserAccount("musician-list@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        musician.setPasswordHash(passwordEncoder.encode("MusicianPass1!"));
        userAccountRepository.saveAndFlush(musician);
        Collection collection = collectionRepository.saveAndFlush(new Collection("List Contract", null, FIXED_NOW));
        sheetMusicRepository.saveAndFlush(new SheetMusic("Visible Piece", "Visible Composer", collection,
                "visible-storage-key", "visible.pdf", "application/pdf", true, FIXED_NOW));
        sheetMusicRepository.saveAndFlush(new SheetMusic("Hidden Piece", "Hidden Composer", collection,
                "hidden-storage-key", "hidden.pdf", "application/pdf", false, FIXED_NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-list@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/sheet-music").cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.title == 'Visible Piece')].composer").value("Visible Composer"))
                .andExpect(jsonPath("$[?(@.title == 'Visible Piece')].collectionId").value(collection.getId().intValue()))
                .andExpect(jsonPath("$[?(@.title == 'Visible Piece')].storageKey").doesNotExist())
                .andExpect(jsonPath("$[?(@.title == 'Hidden Piece')]").doesNotExist());
    }

    @Test
    void listWithoutAuthenticationIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/sheet-music"))
                .andExpect(status().isUnauthorized());
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

        MvcResult result = mockMvc.perform(multipart("/api/sheet-music")
                        .file(file)
                        .param("title", "Bypass Attempt")
                        .param("collectionId", collection.getId().toString())
                        .param("allScope", "false")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isCreated())
                .andReturn();

        Long createdId = extractId(result);
        SheetMusic saved = sheetMusicRepository.findById(createdId).orElseThrow();

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
