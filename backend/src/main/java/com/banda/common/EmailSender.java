package com.banda.common;

/**
 * Design decision #5: a swappable email-sending abstraction, mirroring {@link FileStorage}'s
 * role for design decision #3. Password/security links (activation, reset) and now the
 * Section 9 contact-form admin notification all need real deliverability, so the concrete
 * provider (Mailpit locally, a transactional SMTP provider such as Brevo/Mailgun/SES in
 * prod) is a config-only swap behind this interface, never a hardcoded dependency baked
 * into a caller.
 */
public interface EmailSender {

    /** Sends a plain-text email. Implementations MAY throw an unchecked exception on
     * failure (e.g. {@link org.springframework.mail.MailException}); callers that must not
     * let a notification failure block their own primary operation are responsible for
     * catching and logging it themselves -- see {@code ContactService#notifyAdmins} for the
     * established pattern. */
    void send(String to, String subject, String body);
}
