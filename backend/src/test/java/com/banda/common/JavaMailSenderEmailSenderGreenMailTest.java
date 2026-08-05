package com.banda.common;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 9 (Contact Form) post-review WARNING finding: this is the first email-sending
 * feature in the codebase, and every other {@code EmailSender}/{@code JavaMailSenderEmailSender}
 * test mocks {@link org.springframework.mail.javamail.JavaMailSender} itself, which proves
 * message construction but never proves the real SMTP wire-protocol send actually works. This
 * one focused test uses GreenMail (a lightweight in-JVM fake SMTP server) to prove a real
 * {@link JavaMailSenderImpl}, configured the same way Spring Boot's {@code spring.mail.*}
 * autoconfiguration would configure one, actually sends a receivable email over real SMTP.
 * Deliberately NOT a full parallel test suite -- {@code JavaMailSenderEmailSenderTest} already
 * covers message-construction and exception-propagation behavior via mocks; this test only
 * closes the "does the real wiring work at all" gap.
 */
class JavaMailSenderEmailSenderGreenMailTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    void sendDeliversARealReceivableEmailOverRealSmtp() throws Exception {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost("localhost");
        javaMailSender.setPort(greenMail.getSmtp().getPort());

        JavaMailSenderEmailSender emailSender = new JavaMailSenderEmailSender(javaMailSender, "no-reply@banda.local");

        emailSender.send("admin@example.com", "New contact form submission",
                "A visitor submitted the contact form.");

        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getFrom()[0].toString()).isEqualTo("no-reply@banda.local");
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("admin@example.com");
        assertThat(received[0].getSubject()).isEqualTo("New contact form submission");
        assertThat(GreenMailUtil.getBody(received[0])).isEqualTo("A visitor submitted the contact form.");
    }
}
