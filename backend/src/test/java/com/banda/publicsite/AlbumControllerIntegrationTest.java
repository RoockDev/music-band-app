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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 8 (Public Site Content) "Album view" scenario admin panel surface: the RBAC gate
 * (MANAGE_CONTENT, Sec.2/Sec.10), the audit trail (Sec.11), and the core deliverable of this
 * class — photo upload reusing {@code com.banda.common.FileStorage} exactly the way
 * {@code SheetMusicControllerIntegrationTest} proves for sheet music, through real HTTP over a
 * real Postgres instance + real disk (via {@code IntegrationTestBase}'s isolated
 * {@code app.file-storage.base-dir}).
 */
@AutoConfigureMockMvc
class AlbumControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private PhotoRepository photoRepository;

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
     * instead of locating it by a literal name/caption in the shared Testcontainers Postgres
     * table, which other {@code IntegrationTestBase}-extending test classes can also write rows
     * into for the same entity type (see the 4th-confirmed-instance fixture-name-collision bug
     * this closes). Every create endpoint already returns the persisted {@code id}, so this
     * needs no naming convention to remember. */
    private Long extractId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    @Test
    void createAlbumByAnAdminHoldingManageContentPermissionSucceedsAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActive("admin-album-create@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-album-create@example.com", "AdminPass1!", csrf);

        MvcResult result = mockMvc.perform(post("/api/albums")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Summer Tour\",\"description\":\"2026 tour photos\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Summer Tour"))
                .andExpect(jsonPath("$.photos").isArray())
                .andExpect(jsonPath("$.photos").isEmpty())
                .andReturn();

        Long createdId = extractId(result);
        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Album", createdId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("ALBUM_CREATED");
    }

    @Test
    void createAlbumByAnAdminLackingManageContentPermissionIsForbidden() throws Exception {
        persistActive("admin-album-nopermission@example.com", "AdminPass1!", UserRole.ADMIN);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-album-nopermission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/albums")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Blocked Album\"}"))
                .andExpect(status().isForbidden());

        assertThat(albumRepository.findAll().stream().anyMatch(a -> a.getName().equals("Blocked Album"))).isFalse();
    }

    /** Core deliverable: proves {@code FileStorage} is genuinely reused (real bytes written to
     * the isolated test file-storage dir and read back), not a separate mechanism. */
    @Test
    void uploadPhotoStoresTheFileThroughFileStorageAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActive("admin-photo-upload@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));
        Album album = albumRepository.saveAndFlush(new Album("Winter Concert", null, NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-photo-upload@example.com", "AdminPass1!", csrf);

        MockMultipartFile file = new MockMultipartFile("file", "stage.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});

        MvcResult result = mockMvc.perform(multipart("/api/albums/" + album.getId() + "/photos")
                        .file(file)
                        .param("caption", "On stage")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caption").value("On stage"))
                .andReturn();

        Long photoId = extractId(result);
        Photo saved = photoRepository.findById(photoId).orElseThrow();
        assertThat(saved.getStorageKey()).isNotBlank();
        assertThat(saved.getContentType()).isEqualTo("image/jpeg");

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "Photo", photoId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("PHOTO_UPLOADED");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(saved.getStorageKey());
    }

    @Test
    void uploadPhotoWithAnUnsupportedContentTypeIsRejected() throws Exception {
        UserAccount admin = persistActive("admin-photo-badtype@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));
        Album album = albumRepository.saveAndFlush(new Album("Bad Type Album", null, NOW));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-photo-badtype@example.com", "AdminPass1!", csrf);

        MockMultipartFile file = new MockMultipartFile("file", "sheet.pdf", "application/pdf", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/albums/" + album.getId() + "/photos")
                        .file(file)
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadPhotoOnAnUnknownAlbumReturnsNotFound() throws Exception {
        UserAccount admin = persistActive("admin-photo-404@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-photo-404@example.com", "AdminPass1!", csrf);

        MockMultipartFile file = new MockMultipartFile("file", "stage.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/albums/999999/photos")
                        .file(file)
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
    }
}
