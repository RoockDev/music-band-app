package com.banda.contact;

import com.banda.common.EmailSender;
import com.banda.contact.dto.SubmitContactFormRequest;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Section 9 (Contact Form) use case: {@link #submit} always persists the visitor's
 * submission first -- that is the one guarantee owed to the visitor -- and only afterward
 * attempts to notify every currently ACTIVE admin account via {@link EmailSender}. Unlike
 * every other mutating service in this codebase, there is no permission gate here: a contact
 * form submission has no authenticated actor to check a
 * {@link com.banda.security.Permission} against -- this is the backend's first public,
 * unauthenticated WRITE (see {@code ContactController} and {@code SecurityConfig}'s own
 * Javadoc for the matching {@code permitAll()} wiring).
 *
 * <p><b>Notification failure isolation</b> applies the SAME reasoning {@code AuditService}
 * established for the audit trail (Sec.11): a broken notification (one admin's mailbox
 * rejecting the message, or the whole SMTP relay being down) must never fail, or be visible
 * to, the caller, and must never undo the already-persisted submission. Unlike
 * {@code AuditService#record}, notifying an admin has no participating database resource --
 * it is a plain outbound network call through {@link EmailSender} -- so there is no
 * {@code REQUIRES_NEW} database sub-transaction to open here: wrapping a slow network call in
 * one would only hold a JDBC connection open for its duration (or, worse, two connections at
 * once if this method ever ran inside an ambient transaction that had to be suspended), the
 * exact class of connection-pool pressure this codebase has already had to guard against
 * (Phase 6/PR7's HikariCP pool-exhaustion hardening). The isolation this method needs
 * instead: notification is attempted strictly AFTER the submission's own short,
 * already-committed {@code saveAndFlush} call; the admin lookup and every individual send are
 * each caught separately so one broken mailbox (or even a failed lookup) never stops the
 * rest or reaches the caller; and every failure is logged at ERROR with enough context
 * (submission id, admin id) to investigate -- never the visitor's message body or any
 * credential, and never silently swallowed with zero visibility.
 */
@Service
public class ContactService {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);
    private static final String NOTIFICATION_SUBJECT = "New contact form submission";

    private final ContactSubmissionRepository contactSubmissionRepository;
    private final UserAccountRepository userAccountRepository;
    private final EmailSender emailSender;
    private final Clock clock;

    public ContactService(ContactSubmissionRepository contactSubmissionRepository,
                           UserAccountRepository userAccountRepository,
                           EmailSender emailSender,
                           Clock clock) {
        this.contactSubmissionRepository = contactSubmissionRepository;
        this.userAccountRepository = userAccountRepository;
        this.emailSender = emailSender;
        this.clock = clock;
    }

    public ContactSubmission submit(SubmitContactFormRequest request) {
        Instant now = clock.instant();
        ContactSubmission submission = new ContactSubmission(request.name(), request.email(), request.message(), now);
        ContactSubmission saved = contactSubmissionRepository.saveAndFlush(submission);

        notifyAdmins(saved);

        return saved;
    }

    private void notifyAdmins(ContactSubmission submission) {
        try {
            List<UserAccount> admins = userAccountRepository.findByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE);
            for (UserAccount admin : admins) {
                try {
                    emailSender.send(admin.getEmail(), NOTIFICATION_SUBJECT, notificationBody(submission));
                } catch (RuntimeException e) {
                    log.error("Contact form admin notification failed: submissionId={}, adminId={}",
                            submission.getId(), admin.getId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.error("Contact form admin notification batch failed: submissionId={}", submission.getId(), e);
        }
    }

    private String notificationBody(ContactSubmission submission) {
        return "New contact form submission from " + submission.getName() + " <" + submission.getEmail() + ">:\n\n"
                + submission.getMessage();
    }
}
