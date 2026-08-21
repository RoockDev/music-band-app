package com.banda.publicsite;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PublicContentCrudIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private AdminPermissionRepository adminPermissionRepository;
    @Autowired
    private NewsPostRepository newsPostRepository;
    @Autowired
    private VideoLinkRepository videoLinkRepository;
    @Autowired
    private CourseAnnouncementRepository courseRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie csrf;
    private Cookie accessToken;

    @BeforeEach
    void authenticateContentManager() throws Exception {
        String email = "content-crud-" + System.nanoTime() + "@example.com";
        UserAccount admin = new UserAccount(email, UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        admin.setPasswordHash(passwordEncoder.encode("AdminPass1!"));
        admin = userAccountRepository.saveAndFlush(admin);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));
        csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"AdminPass1!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        accessToken = login.getResponse().getCookie(SecurityConstants.ACCESS_TOKEN_COOKIE);
    }

    @Test
    void newsSupportsAdminListVersionedUpdateDeleteAndStableNotFound() throws Exception {
        NewsPost post = newsPostRepository.saveAndFlush(new NewsPost("Original", "Body", NOW));

        mockMvc.perform(get("/api/news").cookie(accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + post.getId() + ")].version").value(0));

        mockMvc.perform(put("/api/news/" + post.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Updated\",\"body\":\"New body\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put("/api/news/" + post.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Stale\",\"body\":\"Body\",\"version\":0}"))
                .andExpect(status().isConflict());

        deleteVersioned("/api/news/" + post.getId(), 1).andExpect(status().isNoContent());
        deleteVersioned("/api/news/" + post.getId(), 1).andExpect(status().isNotFound());
    }

    @Test
    void videoSupportsVersionedUpdateAndDelete() throws Exception {
        VideoLink video = videoLinkRepository.saveAndFlush(new VideoLink("Original", "https://example.com/a", NOW));

        mockMvc.perform(put("/api/videos/" + video.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Updated\",\"url\":\"https://example.com/b\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        deleteVersioned("/api/videos/" + video.getId(), 1).andExpect(status().isNoContent());
    }

    @Test
    void courseRejectsInvalidDatesAndSupportsVersionedUpdateDelete() throws Exception {
        CourseAnnouncement course = courseRepository.saveAndFlush(new CourseAnnouncement("Original", null,
                LocalDate.of(2026, 9, 1), null, BigDecimal.ZERO, "Voice", 0, NOW));
        String invalid = "{\"title\":\"Updated\",\"startDate\":\"2026-10-01\","
                + "\"endDate\":\"2026-09-01\",\"price\":10,\"instrument\":\"Voice\","
                + "\"minimumAge\":0,\"version\":0}";
        mockMvc.perform(put("/api/courses/" + course.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content(invalid))
                .andExpect(status().isBadRequest());

        String valid = invalid.replace("2026-09-01", "2026-12-01");
        mockMvc.perform(put("/api/courses/" + course.getId())
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content(valid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        deleteVersioned("/api/courses/" + course.getId(), 1).andExpect(status().isNoContent());
    }

    private org.springframework.test.web.servlet.ResultActions deleteVersioned(String path, long version)
            throws Exception {
        return mockMvc.perform(delete(path)
                .queryParam("version", String.valueOf(version))
                .cookie(csrf, accessToken)
                .header("X-XSRF-TOKEN", csrf.getValue()));
    }
}
