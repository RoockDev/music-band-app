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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Section 8 "Structured course" scenario admin panel surface: the RBAC gate
 * (MANAGE_CONTENT, Sec.2/Sec.10), the audit trail (Sec.11), and proof that every structured
 * field (dates/price/instrument/minimum age) round-trips through the real HTTP endpoint as its
 * own distinct field, not folded into free text. */
@AutoConfigureMockMvc
class CourseAnnouncementControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private CourseAnnouncementRepository courseAnnouncementRepository;

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
     * instead of locating it by a literal title in the shared Testcontainers Postgres table,
     * which other {@code IntegrationTestBase}-extending test classes can also write rows into
     * for the same entity type (see the 4th-confirmed-instance fixture-name-collision bug this
     * closes). Every create endpoint already returns the persisted {@code id}, so this needs no
     * naming convention to remember. */
    private Long extractId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    @Test
    void createPersistsEveryStructuredFieldAndWritesAnAuditRecord() throws Exception {
        UserAccount admin = persistActive("admin-course-create@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-course-create@example.com", "AdminPass1!", csrf);

        MvcResult result = mockMvc.perform(post("/api/courses")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Beginner Violin\",\"description\":\"A gentle introduction\","
                                + "\"startDate\":\"2026-09-01\",\"endDate\":\"2026-12-15\",\"price\":120.00,"
                                + "\"instrument\":\"Violin\",\"minimumAge\":8}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Beginner Violin"))
                .andExpect(jsonPath("$.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.endDate").value("2026-12-15"))
                .andExpect(jsonPath("$.price").value(120.00))
                .andExpect(jsonPath("$.instrument").value("Violin"))
                .andExpect(jsonPath("$.minimumAge").value(8))
                .andReturn();

        Long createdId = extractId(result);
        List<AuditLog> history = auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "CourseAnnouncement", createdId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAction()).isEqualTo("COURSE_ANNOUNCEMENT_CREATED");
    }

    @Test
    void createByAnAdminLackingManageContentPermissionIsForbidden() throws Exception {
        persistActive("admin-course-nopermission@example.com", "AdminPass1!", UserRole.ADMIN);

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-course-nopermission@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/courses")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Blocked Course\",\"startDate\":\"2026-09-01\",\"price\":50.00,"
                                + "\"instrument\":\"Piano\",\"minimumAge\":6}"))
                .andExpect(status().isForbidden());

        assertThat(courseAnnouncementRepository.findAll().stream()
                .anyMatch(c -> c.getTitle().equals("Blocked Course"))).isFalse();
    }

    @Test
    void createWithAMissingRequiredFieldIsRejectedWithBadRequest() throws Exception {
        UserAccount admin = persistActive("admin-course-invalid@example.com", "AdminPass1!", UserRole.ADMIN);
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_CONTENT));

        Cookie csrf = fetchCsrfCookie();
        Cookie accessToken = loginAndGetAccessTokenCookie("admin-course-invalid@example.com", "AdminPass1!", csrf);

        mockMvc.perform(post("/api/courses")
                        .cookie(csrf, accessToken)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"title\":\"Missing Price\",\"startDate\":\"2026-09-01\","
                                + "\"instrument\":\"Piano\",\"minimumAge\":6}"))
                .andExpect(status().isBadRequest());
    }
}
