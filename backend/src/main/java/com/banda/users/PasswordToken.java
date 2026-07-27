package com.banda.users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * A single-use, TTL-bound, hashed token used for account activation and password reset.
 * The raw token is never persisted — only its hash — so a leaked database dump cannot be
 * replayed to activate/reset an account.
 */
@Entity
@Table(name = "password_token")
public class PasswordToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PasswordTokenType type;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Optimistic lock: prevents a concurrent double-redemption of this single-use token
     * from silently succeeding twice — see AuthService for how conflicts are handled. */
    @Version
    @Column(name = "version")
    private Long version;

    protected PasswordToken() {
        // JPA
    }

    public PasswordToken(UserAccount user, PasswordTokenType type, String tokenHash, Instant expiresAt, Instant now) {
        this.user = user;
        this.type = type;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public PasswordTokenType getType() {
        return type;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    /** A token can only be consumed once, and only before its TTL expires. */
    public boolean isValid(Instant now) {
        return !isUsed() && !isExpired(now);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public Long getVersion() {
        return version;
    }
}
