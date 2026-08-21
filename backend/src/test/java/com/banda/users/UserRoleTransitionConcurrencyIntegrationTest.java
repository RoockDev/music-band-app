package com.banda.users;

import com.banda.security.AdminPermission;
import com.banda.security.AdminPermissionRepository;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.support.IntegrationTestBase;
import com.banda.users.dto.UpdateUserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class UserRoleTransitionConcurrencyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Test
    void concurrentMutualDemotionsLeaveOneAuthorizedAdminManager() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UserAccount first = userAccountRepository.saveAndFlush(
                new UserAccount("race-manager-first@example.com", UserRole.ADMIN, UserStatus.ACTIVE, now));
        UserAccount second = userAccountRepository.saveAndFlush(
                new UserAccount("race-manager-second@example.com", UserRole.ADMIN, UserStatus.ACTIVE, now));
        for (UserAccount manager : List.of(first, second)) {
            adminPermissionRepository.saveAndFlush(new AdminPermission(manager, Permission.MANAGE_USERS));
            adminPermissionRepository.saveAndFlush(new AdminPermission(manager, Permission.MANAGE_ADMIN_ROLES));
        }

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstOutcome = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return catchThrowable(() -> userService.edit(first, second.getId(),
                        new UpdateUserRequest(second.getEmail(), UserRole.MUSICIAN, false, null, false,
                                second.getVersion())));
            });
            Future<Throwable> secondOutcome = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return catchThrowable(() -> userService.edit(second, first.getId(),
                        new UpdateUserRequest(first.getEmail(), UserRole.MUSICIAN, false, null, false,
                                first.getVersion())));
            });

            List<Throwable> outcomes = Arrays.asList(
                    firstOutcome.get(10, TimeUnit.SECONDS), secondOutcome.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(outcome -> outcome == null).hasSize(1);
            assertThat(outcomes).filteredOn(PermissionDeniedException.class::isInstance).hasSize(1);

            UserAccount reloadedFirst = userAccountRepository.findById(first.getId()).orElseThrow();
            UserAccount reloadedSecond = userAccountRepository.findById(second.getId()).orElseThrow();
            List<UserAccount> pair = List.of(reloadedFirst, reloadedSecond);
            assertThat(pair).filteredOn(account -> account.getRole() == UserRole.ADMIN).hasSize(1);
            assertThat(pair).filteredOn(account -> adminPermissionRepository.existsByAdminAndPermission(
                    account, Permission.MANAGE_ADMIN_ROLES)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
