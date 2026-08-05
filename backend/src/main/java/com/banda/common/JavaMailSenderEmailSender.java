package com.banda.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Design decision #5's chosen {@link EmailSender} implementation: delegates to Spring's
 * {@link JavaMailSender}, which is itself configured entirely via {@code spring.mail.*}
 * properties (host/port/username/password) -- Mailpit locally, a real transactional SMTP
 * provider in prod, with zero code change either way. {@code app.mail.from} is the one
 * additional property this class needs beyond Spring Boot's own mail auto-configuration.
 */
@Component
public class JavaMailSenderEmailSender implements EmailSender {

    private final JavaMailSender javaMailSender;
    private final String fromAddress;

    public JavaMailSenderEmailSender(JavaMailSender javaMailSender, @Value("${app.mail.from}") String fromAddress) {
        this.javaMailSender = javaMailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        javaMailSender.send(message);
    }
}
