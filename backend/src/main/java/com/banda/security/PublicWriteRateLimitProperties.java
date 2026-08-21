package com.banda.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.security.public-write-rate-limit")
public class PublicWriteRateLimitProperties {

    private Limit login = new Limit(10, Duration.ofMinutes(1));
    private Limit passwordReset = new Limit(5, Duration.ofMinutes(15));
    private Limit tokenRedemption = new Limit(10, Duration.ofMinutes(15));
    private Limit contact = new Limit(5, Duration.ofMinutes(10));
    private int maxTrackedClients = 10_000;

    public Limit getLogin() {
        return login;
    }

    public void setLogin(Limit login) {
        this.login = login;
    }

    public Limit getPasswordReset() {
        return passwordReset;
    }

    public void setPasswordReset(Limit passwordReset) {
        this.passwordReset = passwordReset;
    }

    public Limit getTokenRedemption() {
        return tokenRedemption;
    }

    public void setTokenRedemption(Limit tokenRedemption) {
        this.tokenRedemption = tokenRedemption;
    }

    public Limit getContact() {
        return contact;
    }

    public void setContact(Limit contact) {
        this.contact = contact;
    }

    public int getMaxTrackedClients() {
        return maxTrackedClients;
    }

    public void setMaxTrackedClients(int maxTrackedClients) {
        this.maxTrackedClients = maxTrackedClients;
    }

    public record Limit(int requests, Duration window) {
        public Limit {
            if (requests < 1 || window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("Rate limits require positive requests and window");
            }
        }
    }
}
