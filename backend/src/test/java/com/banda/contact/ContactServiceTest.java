package com.banda.contact;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.banda.common.EmailSender;
import com.banda.contact.dto.SubmitContactFormRequest;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Section 9 (Contact Form): {@link ContactService#submit} always persists the submission
 * first -- the one guarantee owed to the visitor -- then notifies every ACTIVE admin account
 * via {@link EmailSender}. Applies the SAME failure-isolation reasoning {@code AuditService}
 * established for the audit trail (Sec.11): a broken notification (one admin, or the whole
 * batch) must never fail, or be visible to, the caller, and must never undo the
 * already-persisted submission. See {@link ContactService}'s own Javadoc for why this is a
 * plain try/catch rather than {@code AuditService}'s atomic database transaction --
 * sending an email has no participating database resource.
 */
class ContactServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ContactSubmissionRepository contactSubmissionRepository;
    private UserAccountRepository userAccountRepository;
    private EmailSender emailSender;
    private ContactService contactService;

    @BeforeEach
    void setUp() {
        contactSubmissionRepository = mock(ContactSubmissionRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        emailSender = mock(EmailSender.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        contactService = new ContactService(contactSubmissionRepository, userAccountRepository, emailSender, clock);

        when(contactSubmissionRepository.saveAndFlush(any(ContactSubmission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount admin(long id, String email) {
        UserAccount account = new UserAccount(email, UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        setId(account, id);
        return account;
    }

    /** {@code UserAccount#id} is JPA-generated with no public setter; reflection keeps this
     * unit test independent of adding a test-only constructor to production code. */
    private void setId(UserAccount account, long id) {
        try {
            var field = UserAccount.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(account, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void submitPersistsTheSubmissionWithItsCoreFields() {
        when(userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE)).thenReturn(List.of());

        ContactSubmission saved = contactService.submit(
                new SubmitContactFormRequest("Visitor Name", "visitor@example.com", "Hello there."));

        ArgumentCaptor<ContactSubmission> captor = ArgumentCaptor.forClass(ContactSubmission.class);
        verify(contactSubmissionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Visitor Name");
        assertThat(captor.getValue().getEmail()).isEqualTo("visitor@example.com");
        assertThat(captor.getValue().getMessage()).isEqualTo("Hello there.");
        assertThat(captor.getValue().getSubmittedAt()).isEqualTo(NOW);
        assertThat(saved.getName()).isEqualTo("Visitor Name");
        // Zero admins to notify -- confirms notifyAdmins doesn't attempt any send at all,
        // not just that no failure was observed.
        verifyNoInteractions(emailSender);
    }

    @Test
    void submitLogsAWarningWhenThereAreNoActiveAdminsToNotify() {
        when(userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE)).thenReturn(List.of());

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ContactService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            ContactSubmission saved = contactService.submit(
                    new SubmitContactFormRequest("Visitor Name", "visitor@example.com", "Hello there."));

            boolean warningLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("submissionId=" + saved.getId()));
            assertThat(warningLogged).isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void submitNotifiesEveryActiveAdminAccount() {
        when(userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin(1L, "admin-one@example.com"), admin(2L, "admin-two@example.com")));

        contactService.submit(new SubmitContactFormRequest("Visitor Name", "visitor@example.com", "Hello there."));

        verify(emailSender).send(eq("admin-one@example.com"), anyString(), anyString());
        verify(emailSender).send(eq("admin-two@example.com"), anyString(), anyString());
    }

    @Test
    void submitStillNotifiesTheRemainingAdminsAndSucceedsWhenOneAdminsEmailFails() {
        when(userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin(1L, "broken-admin@example.com"), admin(2L, "healthy-admin@example.com")));
        doThrow(new RuntimeException("smtp unreachable"))
                .when(emailSender).send(eq("broken-admin@example.com"), anyString(), anyString());

        assertThatCode(() -> contactService.submit(
                new SubmitContactFormRequest("Visitor Name", "visitor@example.com", "Hello there.")))
                .doesNotThrowAnyException();

        verify(emailSender).send(eq("healthy-admin@example.com"), anyString(), anyString());
        verify(contactSubmissionRepository, times(1)).saveAndFlush(any(ContactSubmission.class));
    }

    @Test
    void submitStillReturnsTheSubmissionWhenEveryAdminNotificationFails() {
        when(userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin(1L, "broken-admin@example.com")));
        doThrow(new RuntimeException("smtp unreachable")).when(emailSender).send(anyString(), anyString(), anyString());

        ContactSubmission saved = contactService.submit(
                new SubmitContactFormRequest("Visitor Name", "visitor@example.com", "Hello there."));

        assertThat(saved.getName()).isEqualTo("Visitor Name");
        verify(contactSubmissionRepository).saveAndFlush(any(ContactSubmission.class));
    }

    @Test
    void submitStillSucceedsWhenLookingUpAdminsItselfFails() {
        when(userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE))
                .thenThrow(new RuntimeException("db unavailable"));

        ContactSubmission saved = contactService.submit(
                new SubmitContactFormRequest("Visitor Name", "visitor@example.com", "Hello there."));

        assertThat(saved.getName()).isEqualTo("Visitor Name");
        verify(contactSubmissionRepository).saveAndFlush(any(ContactSubmission.class));
    }

    @Test
    void submitLogsAnErrorWithSubmissionAndAdminContextOnNotificationFailureWithoutLeakingTheMessageBody() {
        when(userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin(7L, "broken-admin@example.com")));
        doThrow(new RuntimeException("smtp unreachable")).when(emailSender).send(anyString(), anyString(), anyString());

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ContactService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            contactService.submit(
                    new SubmitContactFormRequest("Visitor Name", "visitor@example.com", "Secret message body."));

            boolean errorLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains("adminId=7")
                            && !event.getFormattedMessage().contains("Secret message body."));
            assertThat(errorLogged).isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
