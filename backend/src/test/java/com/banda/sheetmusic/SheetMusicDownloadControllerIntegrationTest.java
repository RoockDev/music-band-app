package com.banda.sheetmusic;

import com.banda.audit.AuditLog;
import com.banda.audit.AuditLogRepository;
import com.banda.common.FileStorage;
import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.groups.MusicianGroup;
import com.banda.groups.MusicianGroupRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 5's {@code GET /api/sheet-music/{id}/file} — task 6.3, the core IDOR-safety
 * deliverable of this PR. Covers the three spec scenarios (Sec.5): authorized download
 * (group/individual/allScope), unauthorized download (the IDOR case: piece exists, actor
 * not scoped to it, request by known id — denied, no bytes returned), and proves the denied
 * response is indistinguishable from a genuinely unknown id.
 */
@AutoConfigureMockMvc
@Import(SheetMusicDownloadControllerIntegrationTest.FixedClockConfig.class)
class SheetMusicDownloadControllerIntegrationTest extends IntegrationTestBase {

    static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private SheetMusicRepository sheetMusicRepository;

    @Autowired
    private SheetGroupAccessRepository sheetGroupAccessRepository;

    @Autowired
    private SheetMusicianAccessRepository sheetMusicianAccessRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private MusicianGroupRepository musicianGroupRepository;

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

    private UserAccount persistActiveMusician(String email, String rawPassword) {
        UserAccount musician = new UserAccount(email, UserRole.MUSICIAN, UserStatus.ACTIVE, FIXED_NOW);
        musician.setPasswordHash(passwordEncoder.encode(rawPassword));
        return userAccountRepository.saveAndFlush(musician);
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

    /** Actually stores real bytes via the real {@link FileStorage} bean (not mocked in this
     * full-context integration test) so a successful download has genuine content to read
     * back — a fabricated {@code storageKey} would make every "authorized" test fail with a
     * storage error instead of proving the download path end-to-end. */
    private SheetMusic persistSheetMusic(String title, boolean allScope) throws java.io.IOException {
        Collection collection = collectionRepository.saveAndFlush(new Collection("Test Collection", null, FIXED_NOW));
        String storageKey = fileStorage.store(new java.io.ByteArrayInputStream(("fake bytes for " + title).getBytes()));
        SheetMusic sheetMusic = new SheetMusic(title, "Composer", collection, storageKey,
                title + ".pdf", "application/pdf", allScope, FIXED_NOW);
        return sheetMusicRepository.saveAndFlush(sheetMusic);
    }

    @Test
    void authorizedDownloadViaGroupScopeStreamsTheFileAndWritesAnAuditRecord() throws Exception {
        UserAccount musician = persistActiveMusician("musician-group-dl@example.com", "MusicianPass1!");
        Group group = groupRepository.saveAndFlush(new Group("Brass Section", null, FIXED_NOW));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician, group));
        SheetMusic piece = persistSheetMusic("Group Scoped Piece", false);
        sheetGroupAccessRepository.saveAndFlush(new SheetGroupAccess(piece, group));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-group-dl@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/sheet-music/" + piece.getId() + "/file")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());

        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "SheetMusic", piece.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("SHEET_MUSIC_DOWNLOADED");
        assertThat(history.get(0).getActorId()).isEqualTo(musician.getId());
    }

    @Test
    void authorizedDownloadViaIndividualScopeStreamsTheFile() throws Exception {
        UserAccount musician = persistActiveMusician("musician-individual-dl@example.com", "MusicianPass1!");
        SheetMusic piece = persistSheetMusic("Individually Scoped Piece", false);
        sheetMusicianAccessRepository.saveAndFlush(new SheetMusicianAccess(piece, musician));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-individual-dl@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/sheet-music/" + piece.getId() + "/file")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void authorizedDownloadViaAllScopeStreamsTheFileForAnyAuthenticatedMusician() throws Exception {
        UserAccount musician = persistActiveMusician("musician-allscope-dl@example.com", "MusicianPass1!");
        SheetMusic piece = persistSheetMusic("Open To Everyone Piece", true);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-allscope-dl@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/sheet-music/" + piece.getId() + "/file")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());
    }

    /** Section 5 IDOR-block scenario: the piece exists, but this musician is scoped to
     * neither individually nor via any of their groups — denied, no bytes, and (per the
     * audit contract) no download audit record either since nothing was actually served. */
    @Test
    void unauthorizedDownloadByAMusicianNotScopedToThePieceReturnsNotFoundWithNoBytesAndNoAuditRecord() throws Exception {
        UserAccount musician = persistActiveMusician("musician-idor@example.com", "MusicianPass1!");
        Group unrelatedGroup = groupRepository.saveAndFlush(new Group("Unrelated Group", null, FIXED_NOW));
        musicianGroupRepository.saveAndFlush(new MusicianGroup(musician, unrelatedGroup));
        SheetMusic piece = persistSheetMusic("Restricted Piece", false);
        Group scopedGroup = groupRepository.saveAndFlush(new Group("Scoped Group", null, FIXED_NOW));
        sheetGroupAccessRepository.saveAndFlush(new SheetGroupAccess(piece, scopedGroup));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-idor@example.com", "MusicianPass1!", csrf);

        MvcResult result = mockMvc.perform(get("/api/sheet-music/" + piece.getId() + "/file")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("fake bytes for Restricted Piece");
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc("SheetMusic", piece.getId()))
                .isEmpty();
    }

    /** Proves the denied response is indistinguishable from a genuinely unknown id — both
     * are the exact same 404 body shape, closing the enumeration side channel. */
    @Test
    void deniedAccessAndAnUnknownIdReturnTheExactSameResponseShape() throws Exception {
        UserAccount musician = persistActiveMusician("musician-enum@example.com", "MusicianPass1!");
        SheetMusic piece = persistSheetMusic("Denied Piece", false);
        Group scopedGroup = groupRepository.saveAndFlush(new Group("Someone Elses Group", null, FIXED_NOW));
        sheetGroupAccessRepository.saveAndFlush(new SheetGroupAccess(piece, scopedGroup));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-enum@example.com", "MusicianPass1!", csrf);

        MvcResult deniedResult = mockMvc.perform(get("/api/sheet-music/" + piece.getId() + "/file")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult unknownResult = mockMvc.perform(get("/api/sheet-music/999999/file")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(deniedResult.getResponse().getContentAsString())
                .isEqualTo(unknownResult.getResponse().getContentAsString().replace("999999", piece.getId().toString()));
    }

    @Test
    void downloadOfAnInactivePieceReturnsNotFoundEvenWhenTheActorWouldOtherwiseBeAuthorized() throws Exception {
        UserAccount musician = persistActiveMusician("musician-inactive-dl@example.com", "MusicianPass1!");
        SheetMusic piece = persistSheetMusic("Deactivated Piece", true);
        org.springframework.test.util.ReflectionTestUtils.setField(piece, "active", false);
        sheetMusicRepository.saveAndFlush(piece);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("musician-inactive-dl@example.com", "MusicianPass1!", csrf);

        mockMvc.perform(get("/api/sheet-music/" + piece.getId() + "/file")
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
