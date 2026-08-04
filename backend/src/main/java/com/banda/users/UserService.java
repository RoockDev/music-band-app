package com.banda.users;

import com.banda.audit.AuditService;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.security.TokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Section 3 (User/Musician Management) use cases: admin-driven CRUD over
 * {@link UserAccount}, gated by {@link Permission#MANAGE_USERS} independent of the base
 * ADMIN role (Sec.2/Sec.10), and audited on every mutation (Sec.11) — the pattern later
 * phases (groups, sheet music, events) are expected to copy.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@code UserController}) MUST
 * resolve it from the authenticated principal ({@code SecurityContextHolder} via
 * {@code @AuthenticationPrincipal}), never from client-supplied request data — the same
 * contract {@link PermissionService} and {@link AuditService} themselves document.
 *
 * <p><b>Activation token delivery:</b> {@link #create} generates and persists the
 * account's initial ACTIVATION {@link PasswordToken} (the hook {@code AuthService}'s own
 * Javadoc names this PR as responsible for), the same raw/hash split established by
 * {@code AuthService}'s reset flow. No {@code EmailSender} exists yet in this codebase
 * (design decision #5 is not implemented by any PR up to and including this one), so the
 * raw token is returned to the calling admin in the create response instead of being
 * emailed — a deliberate, documented interim step, not an oversight. It is never logged
 * and never included in {@code AuditService} details.
 */
@Service
@Transactional
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordTokenRepository passwordTokenRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration activationTokenTtl;

    public UserService(UserAccountRepository userAccountRepository,
                        PasswordTokenRepository passwordTokenRepository,
                        PermissionService permissionService,
                        AuditService auditService,
                        Clock clock,
                        @Value("${app.auth.activation-token-ttl}") Duration activationTokenTtl) {
        this.userAccountRepository = userAccountRepository;
        this.passwordTokenRepository = passwordTokenRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.clock = clock;
        this.activationTokenTtl = activationTokenTtl;
    }

    public CreateUserResult create(UserAccount actor, String email, UserRole role,
                                    boolean minor, String guardianContact, boolean consentOnFile) {
        permissionService.requirePermission(actor, Permission.MANAGE_USERS);
        requireValidMinorFields(minor, guardianContact, consentOnFile);

        if (userAccountRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        Instant now = clock.instant();
        UserAccount user = new UserAccount(email, role, UserStatus.PENDING, now);
        applyMinorFields(user, minor, guardianContact, consentOnFile);

        UserAccount saved;
        try {
            saved = userAccountRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent create() race for the same email — the unique constraint
            // is the real source of truth, mirroring PermissionService#grant's established
            // TOCTOU backstop.
            throw new DuplicateEmailException();
        }

        String rawActivationToken = TokenHasher.generateRawToken();
        PasswordToken activationToken = new PasswordToken(saved, PasswordTokenType.ACTIVATION,
                TokenHasher.sha256Hex(rawActivationToken), now.plus(activationTokenTtl), now);
        passwordTokenRepository.saveAndFlush(activationToken);

        auditService.record(actor.getId(), "USER_CREATED", "UserAccount", saved.getId(),
                "role=" + role + ", minor=" + minor);
        log.info("User account created: {}", saved.getId());

        return new CreateUserResult(saved, rawActivationToken);
    }

    public UserAccount edit(UserAccount actor, Long userId, String email, UserRole role,
                             boolean minor, String guardianContact, boolean consentOnFile) {
        permissionService.requirePermission(actor, Permission.MANAGE_USERS);
        UserAccount user = requireUser(userId);
        requireValidMinorFields(minor, guardianContact, consentOnFile);

        if (!user.getEmail().equalsIgnoreCase(email) && userAccountRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        user.setEmail(email);
        user.setRole(role);
        applyMinorFields(user, minor, guardianContact, consentOnFile);
        user.touch(clock.instant());
        userAccountRepository.saveAndFlush(user);

        auditService.record(actor.getId(), "USER_UPDATED", "UserAccount", userId,
                "role=" + role + ", minor=" + minor);
        log.info("User account updated: {}", userId);

        return user;
    }

    /**
     * Sets the account to DEACTIVATED (Sec.3: "login blocked; history retained" — never
     * deleted) and bumps {@code tokenVersion}, mirroring {@code AuthService#resetPassword}'s
     * own "defensive, beyond the literal spec scenario" bump: {@code JwtAuthFilter} already
     * fail-closes on a non-ACTIVE status for every subsequent request, so this bump is
     * defense-in-depth invalidating any already-authenticated in-flight session state too,
     * not the sole mechanism the block relies on.
     */
    public void deactivate(UserAccount actor, Long userId) {
        permissionService.requirePermission(actor, Permission.MANAGE_USERS);
        UserAccount user = requireUser(userId);

        user.setStatus(UserStatus.DEACTIVATED);
        user.bumpTokenVersion();
        user.touch(clock.instant());
        userAccountRepository.saveAndFlush(user);

        auditService.record(actor.getId(), "USER_DEACTIVATED", "UserAccount", userId, null);
        log.info("User account deactivated: {}", userId);
    }

    @Transactional(readOnly = true)
    public UserAccount get(UserAccount actor, Long userId) {
        permissionService.requirePermission(actor, Permission.MANAGE_USERS);
        return requireUser(userId);
    }

    @Transactional(readOnly = true)
    public List<UserAccount> list(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_USERS);
        return userAccountRepository.findAll();
    }

    private UserAccount requireUser(Long userId) {
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    /** Sec.3: minor accounts require guardian contact + consent, enforced. */
    private void requireValidMinorFields(boolean minor, String guardianContact, boolean consentOnFile) {
        if (!minor) {
            return;
        }
        if (guardianContact == null || guardianContact.isBlank()) {
            throw new InvalidUserDataException("Guardian contact is required for minor accounts");
        }
        if (!consentOnFile) {
            throw new InvalidUserDataException("Consent on file is required for minor accounts");
        }
    }

    /** Guardian fields are only ever populated for minors (design decision, Sec.3). */
    private void applyMinorFields(UserAccount user, boolean minor, String guardianContact, boolean consentOnFile) {
        user.setMinor(minor);
        user.setGuardianContact(minor ? guardianContact : null);
        user.setConsentOnFile(minor && consentOnFile);
    }

    public record CreateUserResult(UserAccount user, String activationToken) {
    }
}
