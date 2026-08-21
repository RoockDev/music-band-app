package com.banda.security;

import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.Set;

/**
 * Section 2 ("Permission gate") / Section 10 (Admin Panel Permission-Gated Actions):
 * reusable per-action permission-gate check. Holding the base ADMIN role is NEVER
 * sufficient on its own — every gated mutation MUST additionally hold the specific
 * permission toggle for its action category. PRs 5-9 are expected to call
 * {@link #requirePermission} from their own service layer before performing a gated
 * mutation, the same explicit "no magic" style {@code AuditService.record} already
 * established (design decision #10) — a plain method call, not a
 * {@code @PreAuthorize}/AOP expression, so the check is greppable and directly testable.
 *
 * <p>{@link #grant}/{@link #revoke} are the only mutation entry points for
 * {@code admin_permission} rows introduced by this PR — no controller exists yet for
 * managing permission toggles; Phase 4 (user management, PR 5) is the first real consumer
 * and is expected to expose an admin-facing endpoint that calls these methods.
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final AdminPermissionRepository adminPermissionRepository;
    private final TransactionTemplate requiresNewTransaction;

    public PermissionService(AdminPermissionRepository adminPermissionRepository,
                              PlatformTransactionManager transactionManager) {
        this.adminPermissionRepository = adminPermissionRepository;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Grants {@code permission} to {@code admin}. Idempotent: granting an already-held
     * permission is a no-op, never a duplicate row — including when two concurrent
     * {@code grant()} calls for the same (admin, permission) pair race each other.
     *
     * <p>The exists-check below is a plain check-then-act, so a concurrent caller can also
     * pass it before either side commits; the DB's {@code UNIQUE(admin_id, permission)}
     * constraint is the real source of truth. {@code saveAndFlush} (not {@code save}) forces
     * that constraint check to happen NOW, so the loser of the race is caught here and
     * translated back into the idempotent no-op this method promises — the same pattern
     * already established by {@code AuthService#activate}/{@code AuthService#resetPassword}
     * for their own optimistic-locking races.
     *
     * <p>The check-then-insert runs in its own {@code REQUIRES_NEW} transaction (mirroring
     * {@code AuditService#record}'s isolation approach) so that a lost race — which Spring
     * would otherwise mark the participating transaction rollback-only for, even though this
     * method catches and swallows the exception — cannot poison a transaction this call is
     * nested in; only the failed insert attempt itself rolls back.
     */
    public boolean grant(UserAccount admin, Permission permission) {
        requireNonNullArgs(admin, permission);
        requireAdminRole(admin);
        try {
            return Boolean.TRUE.equals(requiresNewTransaction.execute(status -> {
                if (!adminPermissionRepository.existsByAdminAndPermission(admin, permission)) {
                    adminPermissionRepository.saveAndFlush(new AdminPermission(admin, permission));
                    return true;
                }
                return false;
            }));
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent grant() race for the same (admin, permission) pair — the
            // other caller's insert already committed. This is the expected, benign no-op
            // this method's own Javadoc promises, not a fault: no ERROR log.
            log.debug("Lost a concurrent grant race for admin {} permission {}; already granted",
                    admin.getId(), permission);
            return false;
        }
    }

    /**
     * Revokes {@code permission} from {@code admin}. Idempotent: revoking a
     * not-currently-held permission is a no-op.
     *
     * <p><b>No role validation:</b> unlike {@link #grant}, this method does NOT check
     * {@code admin.getRole()}. It is a pure idempotent delete-by-match against
     * {@code admin_permission} — calling it for a non-ADMIN account, a deactivated account,
     * or any account that never held the toggle is silently a no-op, by design, not an
     * accidental gap.
     */
    @Transactional
    public boolean revoke(UserAccount admin, Permission permission) {
        requireNonNullArgs(admin, permission);
        return adminPermissionRepository.deleteByAdminAndPermission(admin, permission) > 0;
    }

    /** Returns the explicitly granted permission set for an ADMIN account. */
    @Transactional(readOnly = true)
    public Set<Permission> permissionsFor(UserAccount admin) {
        if (admin == null) {
            throw new IllegalArgumentException("admin must not be null");
        }
        requireAdminRole(admin);
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        adminPermissionRepository.findByAdmin(admin).forEach(row -> permissions.add(row.getPermission()));
        return Set.copyOf(permissions);
    }

    /**
     * Throws {@link PermissionDeniedException} unless {@code actor} holds BOTH the base
     * ADMIN role AND the specific {@code permission} toggle, AND the actor's account is
     * currently ACTIVE. Holding ADMIN alone is never sufficient (Sec.2 "Permission gate"
     * scenario, Sec.10).
     *
     * <p>The ACTIVE check is defense-in-depth: today {@code JwtAuthFilter} already filters
     * non-ACTIVE accounts per-request upstream of every controller, but this method is the
     * reusable entry point PRs 5-9 are expected to call directly from their own service
     * layer — it must not silently depend on always running downstream of that filter.
     *
     * <p>A database outage during the permission lookup fails closed (denies), matching the
     * established posture of {@code JwtAuthFilter#authenticate} and {@code AuditService#record}.
     *
     * @throws PermissionDeniedException if {@code actor} lacks the ADMIN role, is not
     *         ACTIVE, lacks the specific {@code permission} toggle, or the permission
     *         lookup fails (fail-closed on a database outage)
     */
    @Transactional(readOnly = true)
    public void requirePermission(UserAccount actor, Permission permission) {
        requireNonNullArgs(actor, permission);
        if (actor.getRole() != UserRole.ADMIN || actor.getStatus() != UserStatus.ACTIVE) {
            throw new PermissionDeniedException(permission);
        }

        boolean granted;
        try {
            granted = adminPermissionRepository.existsByAdminAndPermission(actor, permission);
        } catch (DataAccessException e) {
            log.warn("Database unavailable while checking permission {} for actor {}",
                    permission, actor.getId(), e);
            throw new PermissionDeniedException(permission);
        }

        if (!granted) {
            throw new PermissionDeniedException(permission);
        }
    }

    private void requireAdminRole(UserAccount admin) {
        if (admin.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("Only ADMIN accounts can hold permission toggles");
        }
    }

    private void requireNonNullArgs(UserAccount actor, Permission permission) {
        if (actor == null) {
            throw new IllegalArgumentException("actor/admin must not be null");
        }
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
    }
}
