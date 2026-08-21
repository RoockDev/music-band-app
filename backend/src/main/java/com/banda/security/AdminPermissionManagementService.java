package com.banda.security;

import com.banda.audit.AuditService;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserNotFoundException;
import com.banda.users.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Administrative use cases for reading and changing explicit permission grants. */
@Service
public class AdminPermissionManagementService {

    private final UserAccountRepository userAccountRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public AdminPermissionManagementService(UserAccountRepository userAccountRepository,
                                            PermissionService permissionService,
                                            AuditService auditService) {
        this.userAccountRepository = userAccountRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Set<Permission> getPermissions(UserAccount actor, Long targetId) {
        permissionService.requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
        return permissionService.permissionsFor(requireAdminTarget(targetId));
    }

    @Transactional
    public Set<Permission> grant(UserAccount actor, Long targetId, Permission permission) {
        permissionService.requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
        UserAccount target = requireAdminTarget(targetId);
        boolean changed = permissionService.grant(target, permission);
        if (changed) {
            auditService.record(actor.getId(), "ADMIN_PERMISSION_GRANTED", "UserAccount", targetId,
                    "permission=" + permission);
        }
        return permissionService.permissionsFor(target);
    }

    @Transactional
    public Set<Permission> revoke(UserAccount actor, Long targetId, Permission permission) {
        permissionService.requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);

        UserAccount target;
        if (permission == Permission.MANAGE_ADMIN_ROLES) {
            if (Objects.equals(actor.getId(), targetId)) {
                throw new AdminPermissionConflictException(
                        "You cannot revoke your own admin-role management permission");
            }
            target = lockActorAndTarget(actor, targetId);
            permissionService.requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
        } else {
            target = requireAdminTarget(targetId);
        }

        boolean changed = permissionService.revoke(target, permission);
        if (changed) {
            auditService.record(actor.getId(), "ADMIN_PERMISSION_REVOKED", "UserAccount", targetId,
                    "permission=" + permission);
        }
        return permissionService.permissionsFor(target);
    }

    private UserAccount lockActorAndTarget(UserAccount actor, Long targetId) {
        List<Long> ids = List.of(actor.getId(), targetId).stream().sorted().toList();
        List<UserAccount> locked = userAccountRepository.findAllByIdForPermissionMutation(ids);
        UserAccount target = locked.stream()
                .filter(account -> Objects.equals(account.getId(), targetId))
                .findFirst()
                .orElseThrow(() -> new UserNotFoundException(targetId));
        requireAdminRole(target);
        return target;
    }

    private UserAccount requireAdminTarget(Long targetId) {
        UserAccount target = userAccountRepository.findById(targetId)
                .orElseThrow(() -> new UserNotFoundException(targetId));
        requireAdminRole(target);
        return target;
    }

    private void requireAdminRole(UserAccount target) {
        if (target.getRole() != UserRole.ADMIN) {
            throw new AdminPermissionConflictException("Permissions can only be managed for admin accounts");
        }
    }
}
