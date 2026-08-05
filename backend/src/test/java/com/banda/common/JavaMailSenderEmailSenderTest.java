package com.banda.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Design decision #5's chosen {@link EmailSender} implementation: delegates to Spring's
 * {@link JavaMailSender}, configured for Mailpit locally / a real transactional SMTP
 * provider in prod purely via {@code spring.mail.*} properties -- this class itself stays
 * provider-agnostic, exactly the "one-class swap" the design doc promises (mirrors {@code
 * LocalFileStorage}'s equivalent role for {@code FileStorage}, design decision #3).
 */
class JavaMailSenderEmailSenderTest {

    private JavaMailSender javaMailSender;
    private JavaMailSenderEmailSender emailSender;

    @BeforeEach
    void setUp() {
        javaMailSender = mock(JavaMailSender.class);
        emailSender = new JavaMailSenderEmailSender(javaMailSender, "no-reply@banda.local");
    }

    @Test
    void sendBuildsASimpleMailMessageWithTheGivenRecipientSubjectAndBody() {
        emailSender.send("admin@example.com", "New contact form submission", "A visitor submitted the contact form.");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getFrom()).isEqualTo("no-reply@banda.local");
        assertThat(sent.getTo()).containsExactly("admin@example.com");
        assertThat(sent.getSubject()).isEqualTo("New contact form submission");
        assertThat(sent.getText()).isEqualTo("A visitor submitted the contact form.");
    }

    @Test
    void sendPropagatesAMailExceptionFromTheUnderlyingJavaMailSenderToTheCaller() {
        doThrow(new MailSendException("smtp unreachable")).when(javaMailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailSender.send("admin@example.com", "Subject", "Body"))
                .isInstanceOf(MailSendException.class);
    }
}
