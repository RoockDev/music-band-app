package com.banda.security;

import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 2 ("Permission gate") / design data model: proves {@code admin_permission} is a
 * real many-to-many toggle — grant/revoke round-trips, one admin can hold several
 * permissions, one permission can be granted to several admins, and a duplicate toggle for
 * the same (admin, permission) pair is rejected at the DB level.
 */
class AdminPermissionRepositoryTest extends IntegrationTestBase {

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void togglingAPermissionOnGrantsItAndTogglingOffRemovesIt() {
        UserAccount admin = userAccountRepository.saveAndFlush(
                new UserAccount("admin-toggle@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));

        assertThat(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .isFalse();

        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_SHEET_MUSIC));
        assertThat(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .isTrue();

        adminPermissionRepository.deleteByAdminAndPermission(admin, Permission.MANAGE_SHEET_MUSIC);
        adminPermissionRepository.flush();
        assertThat(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .isFalse();
    }

    @Test
    void isManyToManyOneAdminCanHoldSeveralPermissionsAndOnePermissionCanBeGrantedToSeveralAdmins() {
        UserAccount admin1 = userAccountRepository.saveAndFlush(
                new UserAccount("admin1@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));
        UserAccount admin2 = userAccountRepository.saveAndFlush(
                new UserAccount("admin2@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));

        adminPermissionRepository.saveAndFlush(new AdminPermission(admin1, Permission.MANAGE_USERS));
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin1, Permission.MANAGE_EVENTS));
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin2, Permission.MANAGE_USERS));

        List<AdminPermission> admin1Permissions = adminPermissionRepository.findByAdmin(admin1);
        List<AdminPermission> admin2Permissions = adminPermissionRepository.findByAdmin(admin2);

        assertThat(admin1Permissions).extracting(AdminPermission::getPermission)
                .containsExactlyInAnyOrder(Permission.MANAGE_USERS, Permission.MANAGE_EVENTS);
        assertThat(admin2Permissions).extracting(AdminPermission::getPermission)
                .containsExactly(Permission.MANAGE_USERS);
    }

    @Test
    void grantingTheSamePermissionTwiceToTheSameAdminViolatesTheUniqueConstraint() {
        UserAccount admin = userAccountRepository.saveAndFlush(
                new UserAccount("admin-dup@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));
        adminPermissionRepository.saveAndFlush(new AdminPermission(admin, Permission.MANAGE_GROUPS));

        AdminPermission duplicate = new AdminPermission(admin, Permission.MANAGE_GROUPS);

        assertThatThrownBy(() -> adminPermissionRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
