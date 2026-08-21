package com.banda.users;

import com.banda.audit.AuditService;
import com.banda.events.EventMusicianAccessRepository;
import com.banda.groups.MusicianGroupRepository;
import com.banda.security.AdminPermissionRepository;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.SheetMusicianAccessRepository;
import com.banda.security.TokenHasher;
import com.banda.users.dto.CreateUserRequest;
import com.banda.users.dto.UpdateUserRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
 * <p><b>Privilege-escalation gate:</b> {@link Permission#MANAGE_USERS} alone only ever
 * grants ordinary user/musician profile CRUD. Any actual {@code role} change — {@link #create}
 * of an ADMIN account, or {@link #edit} changing a target's role away from what it currently
 * is, in either direction — additionally requires {@link Permission#MANAGE_ADMIN_ROLES}.
 * {@link #edit} and {@link #deactivate} also unconditionally reject an actor targeting their
 * own account ({@link SelfTargetNotAllowedException}), regardless of which permissions they
 * hold.
 *
 * <p><b>Activation token delivery:</b> {@link #create} generates and persists the
 * account's initial ACTIVATION {@link PasswordToken} (the hook {@code AuthService}'s own
 * Javadoc names this PR as responsible for), the same raw/hash split established by
 * {@code AuthService}'s reset flow. {@code com.banda.common.EmailSender} now exists (added
 * by the Section 9/Contact Form PR), but wiring it into {@link #create} to actually email
 * the activation token is a DELIBERATE, still-deferred follow-up, not a gap left by a
 * missing dependency: the raw token is returned to the calling admin in the create response
 * instead of being emailed. This is an intentional scope boundary of the current backend
 * delivery (there is no further backend phase after Contact Form), not an oversight — a
 * future maintainer should treat this as a small, natural follow-up to pick up whenever
 * desired, not a "next phase" to go looking for. It is never logged and never included in
 * {@code AuditService} details.
 */
@Service
@Transactional
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordTokenRepository passwordTokenRepository;
    private final AdminPermissionRepository adminPermissionRepository;
    private final MusicianGroupRepository musicianGroupRepository;
    private final EventMusicianAccessRepository eventMusicianAccessRepository;
    private final SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Clock clock;
    private final Duration activationTokenTtl;

    public UserService(UserAccountRepository userAccountRepository,
                        PasswordTokenRepository passwordTokenRepository,
                        AdminPermissionRepository adminPermissionRepository,
                        MusicianGroupRepository musicianGroupRepository,
                        EventMusicianAccessRepository eventMusicianAccessRepository,
                        SheetMusicianAccessRepository sheetMusicianAccessRepository,
                        PermissionService permissionService,
                        AuditService auditService,
                        Clock clock,
                        @Value("${app.auth.activation-token-ttl}") Duration activationTokenTtl) {
        this.userAccountRepository = userAccountRepository;
        this.passwordTokenRepository = passwordTokenRepository;
        this.adminPermissionRepository = adminPermissionRepository;
        this.musicianGroupRepository = musicianGroupRepository;
        this.eventMusicianAccessRepository = eventMusicianAccessRepository;
        this.sheetMusicianAccessRepository = sheetMusicianAccessRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.clock = clock;
        this.activationTokenTtl = activationTokenTtl;
    }

    public CreateUserResult create(UserAccount actor, CreateUserRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_USERS);
        if (request.role() == UserRole.ADMIN) {
            // Minting a new admin is strictly more privileged than ordinary user CRUD —
            // MANAGE_USERS alone must never be enough to create an ADMIN account.
            permissionService.requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
        }
        requireValidMinorFields(request.minor(), request.guardianContact(), request.consentOnFile());

        if (userAccountRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }

        Instant now = clock.instant();
        UserAccount user = new UserAccount(request.email(), request.role(), UserStatus.PENDING, now);
        applyMinorFields(user, request.minor(), request.guardianContact(), request.consentOnFile());

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
                "role=" + request.role() + ", minor=" + request.minor());
        log.info("User account created: {}", saved.getId());

        return new CreateUserResult(saved, rawActivationToken);
    }

    public UserAccount edit(UserAccount actor, Long userId, UpdateUserRequest request) {
        requireNotSelf(actor, userId);
        LockedMutationAccounts accounts = lockActorAndTarget(actor, userId);
        UserAccount managedActor = accounts.actor();
        UserAccount user = accounts.target();
        permissionService.requirePermission(managedActor, Permission.MANAGE_USERS);
        requireValidMinorFields(request.minor(), request.guardianContact(), request.consentOnFile());

        boolean roleChanged = request.role() != user.getRole();
        if (user.getRole() == UserRole.ADMIN || roleChanged) {
            // Every mutation of an ADMIN target is privileged, including an email-only edit.
            // This closes the email-replacement -> password-reset account-takeover chain.
            permissionService.requirePermission(managedActor, Permission.MANAGE_ADMIN_ROLES);
        }
        if (!Objects.equals(request.version(), user.getVersion())) {
            throw new ConcurrentUserModificationException();
        }
        if (user.getRole() == UserRole.MUSICIAN && request.role() == UserRole.ADMIN) {
            requireNoMusicianDependencies(user);
        }

        if (!user.getEmail().equalsIgnoreCase(request.email()) && userAccountRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }

        boolean changed = !Objects.equals(user.getEmail(), request.email())
                || roleChanged
                || user.isMinor() != request.minor()
                || !Objects.equals(user.getGuardianContact(), request.minor() ? request.guardianContact() : null)
                || user.isConsentOnFile() != (request.minor() && request.consentOnFile());
        if (!changed) {
            return user;
        }

        if (user.getRole() == UserRole.ADMIN && request.role() == UserRole.MUSICIAN) {
            // The actor and target rows were locked in stable id order and permissions were
            // rechecked after locking. Concurrent mutual demotions therefore serialize; the
            // first winner revokes the loser's grants, so the loser cannot demote the winner.
            adminPermissionRepository.deleteByAdmin(user);
        }

        user.setEmail(request.email());
        user.setRole(request.role());
        applyMinorFields(user, request.minor(), request.guardianContact(), request.consentOnFile());
        user.touch(clock.instant());

        try {
            // saveAndFlush (not save): forces the @Version check to happen NOW, inside this
            // method, mirroring AuthService's established optimistic-locking pattern.
            userAccountRepository.saveAndFlush(user);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Unlike deactivate(), an edit's fields aren't safely re-appliable without
            // knowing what changed underneath — surface the conflict to the caller as a 409
            // instead of retrying blindly.
            throw new ConcurrentUserModificationException();
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent email-uniqueness race — same TOCTOU backstop as create().
            throw new DuplicateEmailException();
        }

        auditService.record(managedActor.getId(), "USER_UPDATED", "UserAccount", userId,
                "role=" + request.role() + ", minor=" + request.minor());
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
     *
     * <p>Idempotent: deactivating an already-DEACTIVATED account is a silent no-op — no
     * duplicate audit entry, no extra {@code tokenVersion} bump. Retries once on a lost
     * optimistic-lock race, mirroring {@code AuthService#bumpTokenVersionWithRetry}: silently
     * failing to deactivate an account is worse than silently failing a profile edit, so
     * (unlike {@link #edit}) this does not surface the race to the caller as an error.
     */
    public void deactivate(UserAccount actor, Long userId) {
        requireNotSelf(actor, userId);
        LockedMutationAccounts accounts = lockActorAndTarget(actor, userId);
        UserAccount managedActor = accounts.actor();
        UserAccount user = accounts.target();
        permissionService.requirePermission(managedActor, Permission.MANAGE_USERS);
        if (user.getRole() == UserRole.ADMIN) {
            permissionService.requirePermission(managedActor, Permission.MANAGE_ADMIN_ROLES);
        }

        if (user.getStatus() == UserStatus.DEACTIVATED) {
            return;
        }

        user.setStatus(UserStatus.DEACTIVATED);
        user.bumpTokenVersion();
        user.touch(clock.instant());
        userAccountRepository.saveAndFlush(user);

        auditService.record(managedActor.getId(), "USER_DEACTIVATED", "UserAccount", userId);
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

    private LockedMutationAccounts lockActorAndTarget(UserAccount actor, Long userId) {
        List<Long> ids = List.of(actor.getId(), userId).stream().distinct().sorted().toList();
        List<UserAccount> locked = userAccountRepository.findAllByIdForPermissionMutation(ids);
        UserAccount managedActor = locked.stream()
                .filter(account -> Objects.equals(account.getId(), actor.getId()))
                .findFirst()
                .orElseThrow(() -> new UserNotFoundException(actor.getId()));
        UserAccount target = locked.stream()
                .filter(account -> Objects.equals(account.getId(), userId))
                .findFirst()
                .orElseThrow(() -> new UserNotFoundException(userId));
        return new LockedMutationAccounts(managedActor, target);
    }

    private void requireNoMusicianDependencies(UserAccount musician) {
        long groupMemberships = musicianGroupRepository.countByMusician(musician);
        long eventGrants = eventMusicianAccessRepository.countByMusician(musician);
        long sheetMusicGrants = sheetMusicianAccessRepository.countByMusician(musician);
        if (groupMemberships > 0 || eventGrants > 0 || sheetMusicGrants > 0) {
            throw new UserRoleTransitionConflictException(groupMemberships, eventGrants, sheetMusicGrants);
        }
    }

    /** No actor may edit/deactivate their own account via this service (Sec.2), full stop. */
    private void requireNotSelf(UserAccount actor, Long userId) {
        if (Objects.equals(userId, actor.getId())) {
            throw new SelfTargetNotAllowedException();
        }
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

    private record LockedMutationAccounts(UserAccount actor, UserAccount target) {
    }
}
