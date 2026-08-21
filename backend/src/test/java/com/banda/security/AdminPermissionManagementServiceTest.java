package com.banda.security;

import com.banda.audit.AuditService;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPermissionManagementServiceTest {

    private UserAccountRepository userAccountRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private AdminPermissionManagementService service;
    private UserAccount actor;
    private UserAccount target;

    @BeforeEach
    void setUp() throws Exception {
        userAccountRepository = mock(UserAccountRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        service = new AdminPermissionManagementService(userAccountRepository, permissionService, auditService);
        actor = account(1L, "actor@example.com", UserRole.ADMIN);
        target = account(2L, "target@example.com", UserRole.ADMIN);
    }

    @Test
    void grantAuditsOnlyWhenPermissionServiceReportsAStateChange() {
        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(target));
        when(permissionService.grant(target, Permission.MANAGE_EVENTS)).thenReturn(true, false);
        when(permissionService.permissionsFor(target)).thenReturn(Set.of(Permission.MANAGE_EVENTS));

        service.grant(actor, 2L, Permission.MANAGE_EVENTS);
        service.grant(actor, 2L, Permission.MANAGE_EVENTS);

        verify(auditService).record(1L, "ADMIN_PERMISSION_GRANTED", "UserAccount", 2L,
                "permission=MANAGE_EVENTS");
    }

    @Test
    void revokeDoesNotAuditANoOp() {
        when(userAccountRepository.findById(2L)).thenReturn(Optional.of(target));
        when(permissionService.revoke(target, Permission.MANAGE_EVENTS)).thenReturn(false);
        when(permissionService.permissionsFor(target)).thenReturn(Set.of());

        service.revoke(actor, 2L, Permission.MANAGE_EVENTS);

        verify(auditService, never()).record(1L, "ADMIN_PERMISSION_REVOKED", "UserAccount", 2L,
                "permission=MANAGE_EVENTS");
    }

    @Test
    void cannotRevokeOwnAdminRoleManagementPermission() {
        assertThatThrownBy(() -> service.revoke(actor, 1L, Permission.MANAGE_ADMIN_ROLES))
                .isInstanceOf(AdminPermissionConflictException.class);

        verify(permissionService, never()).revoke(actor, Permission.MANAGE_ADMIN_ROLES);
    }

    @Test
    void rejectsANonAdminTargetAfterAuthorizingTheActor() throws Exception {
        UserAccount musician = account(3L, "musician@example.com", UserRole.MUSICIAN);
        when(userAccountRepository.findById(3L)).thenReturn(Optional.of(musician));

        assertThatThrownBy(() -> service.getPermissions(actor, 3L))
                .isInstanceOf(AdminPermissionConflictException.class);

        verify(permissionService).requirePermission(actor, Permission.MANAGE_ADMIN_ROLES);
        verify(permissionService, never()).permissionsFor(musician);
    }

    private UserAccount account(Long id, String email, UserRole role) throws Exception {
        UserAccount account = new UserAccount(email, role, UserStatus.ACTIVE, Instant.now());
        Field idField = UserAccount.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(account, id);
        return account;
    }
}
