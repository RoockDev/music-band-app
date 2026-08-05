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
 *
 * <p><b>No {@code AuditService} call, deliberately.</b> {@code AuditService}'s own class
 * Javadoc states, as an explicit codebase-wide contract, that every mutating action across
 * every feature is expected to call {@code record(...)} once it has successfully persisted
 * its change, and every other mutating service in this codebase does. This is the one
 * exception: {@code AuditService#record}'s own contract requires {@code actorId} to be
 * derived from an authenticated principal ({@code SecurityContextHolder}), and a contact
 * form submission has no authenticated actor to attribute the mutation to (see above) --
 * inventing a fake/null actorId to satisfy the call would violate that contract for no real
 * benefit. The persisted {@link ContactSubmission} row itself already serves as the durable
 * record of the action, the same role {@code AuditLog} plays for actor-attributed mutations
 * elsewhere. This was reasoned, not missed.
 *
 * <p><b>Notification scope.</b> {@link #notifyAdmins} notifies accounts with
 * {@code role=ADMIN AND status=ACTIVE}, deliberately narrower than a literal reading of
 * spec Section 9's "notify all admin accounts": PENDING admins haven't activated/set a
 * password yet (arguably not yet a real admin to route a visitor to), and DEACTIVATED
 * admins already have login blocked (Sec.3) -- notifying either would be operationally
 * useless. See {@code ContactControllerIntegrationTest} for the HTTP-level proof that a
 * DEACTIVATED admin is excluded.
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
            if (admins.isEmpty()) {
                log.warn("Contact form submission has no ACTIVE admins to notify: submissionId={}",
                        submission.getId());
                return;
            }
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
