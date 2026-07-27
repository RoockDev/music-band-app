package com.banda.users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** Null until the account is activated and a password is set. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    /** Bumped on logout/password-reset to invalidate previously issued JWTs server-side. */
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(nullable = false)
    private boolean minor;

    @Column(name = "guardian_contact")
    private String guardianContact;

    @Column(name = "consent_on_file", nullable = false)
    private boolean consentOnFile;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: prevents a concurrent double-redemption of a token or a lost
     * tokenVersion bump (e.g. simultaneous logout + password reset) from silently
     * corrupting state — see AuthService for how conflicts here are handled. */
    @Version
    @Column(name = "version")
    private Long version;

    protected UserAccount() {
        // JPA
    }

    public UserAccount(String email, UserRole role, UserStatus status, Instant now) {
        this.email = email;
        this.role = role;
        this.status = status;
        this.tokenVersion = 0L;
        this.minor = false;
        this.consentOnFile = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    /** Invalidates every JWT issued before this call (logout, password reset). */
    public void bumpTokenVersion() {
        this.tokenVersion++;
    }

    public boolean isMinor() {
        return minor;
    }

    public void setMinor(boolean minor) {
        this.minor = minor;
    }

    public String getGuardianContact() {
        return guardianContact;
    }

    public void setGuardianContact(String guardianContact) {
        this.guardianContact = guardianContact;
    }

    public boolean isConsentOnFile() {
        return consentOnFile;
    }

    public void setConsentOnFile(boolean consentOnFile) {
        this.consentOnFile = consentOnFile;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public Long getVersion() {
        return version;
    }
}
