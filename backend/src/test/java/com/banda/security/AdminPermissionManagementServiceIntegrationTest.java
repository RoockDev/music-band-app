package com.banda.security;

import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPermissionManagementServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AdminPermissionManagementService service;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Test
    void concurrentCrossRevocationsCannotRemoveEveryAdminRoleManager() throws Exception {
        UserAccount first = userAccountRepository.saveAndFlush(new UserAccount(
                "cross-revoke-first@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));
        UserAccount second = userAccountRepository.saveAndFlush(new UserAccount(
                "cross-revoke-second@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));
        adminPermissionRepository.saveAndFlush(new AdminPermission(first, Permission.MANAGE_ADMIN_ROLES));
        adminPermissionRepository.saveAndFlush(new AdminPermission(second, Permission.MANAGE_ADMIN_ROLES));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstResult = executor.submit(() -> revokeAfterBarrier(barrier, first, second));
            Future<Throwable> secondResult = executor.submit(() -> revokeAfterBarrier(barrier, second, first));
            List<Throwable> outcomes = new ArrayList<>();
            outcomes.add(firstResult.get(10, TimeUnit.SECONDS));
            outcomes.add(secondResult.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(error -> error == null).hasSize(1);
            assertThat(outcomes).filteredOn(error -> error instanceof PermissionDeniedException).hasSize(1);
            long remainingManagers = List.of(first, second).stream()
                    .filter(admin -> adminPermissionRepository.existsByAdminAndPermission(
                            admin, Permission.MANAGE_ADMIN_ROLES))
                    .count();
            assertThat(remainingManagers).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Throwable revokeAfterBarrier(CyclicBarrier barrier, UserAccount actor, UserAccount target) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            service.revoke(actor, target.getId(), Permission.MANAGE_ADMIN_ROLES);
            return null;
        } catch (Throwable error) {
            return error;
        }
    }
}
