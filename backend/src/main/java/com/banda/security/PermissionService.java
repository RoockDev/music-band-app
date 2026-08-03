package com.banda.security;

import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
public class PermissionService {

    private final AdminPermissionRepository adminPermissionRepository;

    public PermissionService(AdminPermissionRepository adminPermissionRepository) {
        this.adminPermissionRepository = adminPermissionRepository;
    }

    /** Grants {@code permission} to {@code admin}. Idempotent: granting an already-held
     * permission is a no-op, never a duplicate row. */
    public void grant(UserAccount admin, Permission permission) {
        requireAdminRole(admin);
        if (!adminPermissionRepository.existsByAdminAndPermission(admin, permission)) {
            adminPermissionRepository.save(new AdminPermission(admin, permission));
        }
    }

    /** Revokes {@code permission} from {@code admin}. Idempotent: revoking a
     * not-currently-held permission is a no-op. */
    public void revoke(UserAccount admin, Permission permission) {
        adminPermissionRepository.deleteByAdminAndPermission(admin, permission);
    }

    /**
     * Throws {@link PermissionDeniedException} unless {@code actor} holds BOTH the base
     * ADMIN role AND the specific {@code permission} toggle. Holding ADMIN alone is never
     * sufficient (Sec.2 "Permission gate" scenario, Sec.10).
     */
    public void requirePermission(UserAccount actor, Permission permission) {
        if (actor.getRole() != UserRole.ADMIN
                || !adminPermissionRepository.existsByAdminAndPermission(actor, permission)) {
            throw new PermissionDeniedException(permission);
        }
    }

    private void requireAdminRole(UserAccount admin) {
        if (admin.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("Only ADMIN accounts can hold permission toggles");
        }
    }
}
