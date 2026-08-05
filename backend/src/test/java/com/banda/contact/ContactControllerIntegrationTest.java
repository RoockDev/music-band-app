package com.banda.contact;

import com.banda.common.EmailSender;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Section 9 (Contact Form), proven through real HTTP over a real Postgres instance: the
 * unauthenticated {@code permitAll()} wiring, the CSRF requirement (same established
 * pattern {@code AuthControllerIntegrationTest} proves for {@code /api/auth/login}),
 * persistence, and admin notification -- deactivated admins must NOT be notified.
 * {@link EmailSender} is replaced with a Mockito mock: no real SMTP relay is available in
 * this test environment. See {@code JavaMailSenderEmailSenderTest} for the real
 * implementation's own proof, and {@code ContactServiceTest} for the failure-isolation proof
 * at the unit level.
 */
@AutoConfigureMockMvc
class ContactControllerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactSubmissionRepository contactSubmissionRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @MockitoBean
    private EmailSender emailSender;

    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        return result.getResponse().getCookie("XSRF-TOKEN");
    }

    /** Collision-proof: reads the persisted row's id from the create response body instead
     * of scanning the shared Testcontainers Postgres table by literal name/email -- see
     * {@code NewsControllerIntegrationTest}'s equivalent helper for the established
     * rationale. */
    private Long extractId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    @Test
    void submitByAnUnauthenticatedVisitorWithAValidCsrfTokenSucceedsPersistsAndNotifiesOnlyActiveAdmins() throws Exception {
        userAccountRepository.saveAndFlush(
                new UserAccount("contact-admin-one@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW));
        userAccountRepository.saveAndFlush(
                new UserAccount("contact-admin-two@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW));
        userAccountRepository.saveAndFlush(
                new UserAccount("contact-admin-deactivated@example.com", UserRole.ADMIN, UserStatus.DEACTIVATED, NOW));

        Cookie csrf = fetchCsrfCookie();

        MvcResult result = mockMvc.perform(post("/api/contact")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Jane Visitor\",\"email\":\"jane-visitor@example.com\","
                                + "\"message\":\"Interested in violin lessons.\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Jane Visitor");
        Long createdId = extractId(result);

        ContactSubmission persisted = contactSubmissionRepository.findById(createdId).orElseThrow();
        assertThat(persisted.getEmail()).isEqualTo("jane-visitor@example.com");
        assertThat(persisted.getMessage()).isEqualTo("Interested in violin lessons.");

        verify(emailSender).send(eq("contact-admin-one@example.com"), anyString(), anyString());
        verify(emailSender).send(eq("contact-admin-two@example.com"), anyString(), anyString());
        verify(emailSender, never()).send(eq("contact-admin-deactivated@example.com"), anyString(), anyString());
    }

    /** No CSRF cookie/header at all -- rejected by the CSRF filter itself before reaching the
     * (permitAll) endpoint, exactly the same shape {@code NewsControllerIntegrationTest}
     * proves for {@code /api/news}, and the same established pattern
     * {@code AuthControllerIntegrationTest} implicitly relies on for {@code /api/auth/login}:
     * an unauthenticated POST still requires a valid double-submit CSRF token. No dedicated
     * CSRF exemption is introduced for this endpoint. */
    @Test
    void submitByAnUnauthenticatedVisitorWithNoCsrfTokenIsForbiddenAndNothingIsPersisted() throws Exception {
        mockMvc.perform(post("/api/contact")
                        .contentType("application/json")
                        .content("{\"name\":\"Anon Attempt\",\"email\":\"anon-no-csrf@example.com\","
                                + "\"message\":\"Should not save.\"}"))
                .andExpect(status().isForbidden());

        assertThat(contactSubmissionRepository.findAll().stream()
                .anyMatch(s -> s.getEmail().equals("anon-no-csrf@example.com"))).isFalse();
    }

    @Test
    void submitWithAnInvalidEmailIsRejectedWithBadRequestAndNothingIsPersisted() throws Exception {
        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/contact")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Bad Email Visitor\",\"email\":\"not-an-email\",\"message\":\"Body.\"}"))
                .andExpect(status().isBadRequest());

        assertThat(contactSubmissionRepository.findAll().stream()
                .anyMatch(s -> s.getName().equals("Bad Email Visitor"))).isFalse();
    }

    @Test
    void submitWithABlankNameIsRejectedWithBadRequestAndNothingIsPersisted() throws Exception {
        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/contact")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"\",\"email\":\"blank-name@example.com\",\"message\":\"Body.\"}"))
                .andExpect(status().isBadRequest());

        assertThat(contactSubmissionRepository.findAll().stream()
                .anyMatch(s -> s.getEmail().equals("blank-name@example.com"))).isFalse();
    }

    @Test
    void submitWithABlankMessageIsRejectedWithBadRequestAndNothingIsPersisted() throws Exception {
        Cookie csrf = fetchCsrfCookie();

        mockMvc.perform(post("/api/contact")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Blank Message Visitor\",\"email\":\"blank-message@example.com\","
                                + "\"message\":\"\"}"))
                .andExpect(status().isBadRequest());

        assertThat(contactSubmissionRepository.findAll().stream()
                .anyMatch(s -> s.getEmail().equals("blank-message@example.com"))).isFalse();
    }

    /** {@code SubmitContactFormRequest.name} is capped at 255 chars to match
     * {@code ContactSubmission.name}'s explicit {@code @Column(length = 255)} -- without
     * this, an oversized field passes bean validation and hits a raw DB-layer failure
     * instead of a clean 400 (WARNING finding, Section 9 post-review). */
    @Test
    void submitWithAnOversizedNameIsRejectedWithBadRequestAndNothingIsPersisted() throws Exception {
        Cookie csrf = fetchCsrfCookie();
        String oversizedName = "a".repeat(256);

        mockMvc.perform(post("/api/contact")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"" + oversizedName + "\",\"email\":\"oversized-name@example.com\","
                                + "\"message\":\"Body.\"}"))
                .andExpect(status().isBadRequest());

        assertThat(contactSubmissionRepository.findAll().stream()
                .anyMatch(s -> s.getEmail().equals("oversized-name@example.com"))).isFalse();
    }

    /** Same as above for {@code message}, capped at 5000 chars -- generous for a genuine
     * contact-form message while bounding both DB storage and (since every ACTIVE admin
     * gets a full copy in their notification email) per-request outbound email size on this
     * unauthenticated, unrate-limited endpoint. */
    @Test
    void submitWithAnOversizedMessageIsRejectedWithBadRequestAndNothingIsPersisted() throws Exception {
        Cookie csrf = fetchCsrfCookie();
        String oversizedMessage = "a".repeat(5001);

        mockMvc.perform(post("/api/contact")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue())
                        .contentType("application/json")
                        .content("{\"name\":\"Oversized Message Visitor\",\"email\":\"oversized-message@example.com\","
                                + "\"message\":\"" + oversizedMessage + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(contactSubmissionRepository.findAll().stream()
                .anyMatch(s -> s.getEmail().equals("oversized-message@example.com"))).isFalse();
    }
}
