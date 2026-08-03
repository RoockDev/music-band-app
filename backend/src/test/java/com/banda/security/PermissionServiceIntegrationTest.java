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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Real Spring context + real Postgres (Testcontainers) coverage of {@link PermissionService}.
 * {@link PermissionServiceTest} already proves the gate's decision logic against a mocked
 * repository; this class proves the same behavior actually holds against real JPA
 * persistence and — critically — that {@link PermissionService#grant} is safe under a real
 * concurrent race against the DB's {@code UNIQUE(admin_id, permission)} constraint, not just
 * against a scripted mock.
 */
class PermissionServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void grantThenRequirePermissionThenRevokeThenRequirePermissionLifecycleAgainstRealPersistence() {
        UserAccount admin = userAccountRepository.saveAndFlush(
                new UserAccount("lifecycle-admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));

        assertThat(catchThrowable(() -> permissionService.requirePermission(admin, Permission.MANAGE_GROUPS)))
                .as("no toggle yet -> denied")
                .isInstanceOf(PermissionDeniedException.class);

        permissionService.grant(admin, Permission.MANAGE_GROUPS);
        assertThat(catchThrowable(() -> permissionService.requirePermission(admin, Permission.MANAGE_GROUPS)))
                .as("toggle granted -> allowed")
                .isNull();

        permissionService.revoke(admin, Permission.MANAGE_GROUPS);
        assertThat(catchThrowable(() -> permissionService.requirePermission(admin, Permission.MANAGE_GROUPS)))
                .as("toggle revoked -> denied again")
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void revokingAPermissionThatWasNeverGrantedIsANoOp() {
        UserAccount admin = userAccountRepository.saveAndFlush(
                new UserAccount("revoke-noop-admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));

        assertThat(catchThrowable(() -> permissionService.revoke(admin, Permission.MANAGE_CONTENT))).isNull();
        assertThat(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_CONTENT)).isFalse();
    }

    /**
     * The critical resilience proof: two real concurrent {@link PermissionService#grant}
     * calls for the SAME (admin, permission) pair, synchronized to start together via a
     * {@link CyclicBarrier} so both threads' exists-check reads {@code false} before either
     * commits — the exact interleaving the check-then-act race requires. Before the fix,
     * the loser's {@code saveAndFlush} would throw {@code DataIntegrityViolationException}
     * uncaught. After the fix, both calls MUST return normally and exactly one row must
     * exist.
     */
    @Test
    void concurrentGrantCallsForTheSameAdminAndPermissionResultInExactlyOneRowAndNoException() throws Exception {
        UserAccount admin = userAccountRepository.saveAndFlush(
                new UserAccount("race-grant-admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now()));

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Exception>> results = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        permissionService.grant(admin, Permission.MANAGE_USERS);
                        return null;
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }

            List<Exception> propagated = new ArrayList<>();
            for (Future<Exception> result : results) {
                Exception thrown = result.get(10, TimeUnit.SECONDS);
                if (thrown != null) {
                    propagated.add(thrown);
                }
            }

            assertThat(propagated)
                    .as("neither concurrent grant() call should propagate an exception to its caller")
                    .isEmpty();
        } finally {
            executor.shutdown();
        }

        List<AdminPermission> rows = adminPermissionRepository.findByAdmin(admin);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getPermission()).isEqualTo(Permission.MANAGE_USERS);
    }
}
