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

    /** Sends a plain-text email, synchronously/blocking the calling thread until the send
     * either succeeds or fails (see {@code spring.mail.properties.mail.smtp.*} timeouts in
     * {@code application.yml}, which bound how long that block can last). Implementations
     * MUST throw an unchecked exception on failure (e.g. {@link
     * org.springframework.mail.MailException}) and MUST NEVER silently swallow one --
     * callers that must not let a notification failure block their own primary operation
     * are responsible for catching and logging it themselves -- see
     * {@code ContactService#notifyAdmins} for the established pattern, which depends on this
     * contract to detect and isolate a failed send. */
    void send(String to, String subject, String body);
}
