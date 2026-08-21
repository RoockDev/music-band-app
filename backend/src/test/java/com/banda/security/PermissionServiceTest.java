package com.banda.security;

import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Section 2 ("Permission gate") / Section 10 (Admin Panel Permission-Gated Actions): proves
 * the gate check is enforced independent of the base ADMIN role — holding ADMIN alone is
 * never sufficient, and (defensively) holding a stray permission row without the ADMIN role
 * is likewise never sufficient. {@link AdminPermissionRepositoryTest} already proves the
 * real JPA-level persistence/uniqueness; this class proves the gate's decision logic.
 */
class PermissionServiceTest {

    private AdminPermissionRepository adminPermissionRepository;
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        adminPermissionRepository = mock(AdminPermissionRepository.class);
        permissionService = new PermissionService(adminPermissionRepository, new NoOpTransactionManager());
    }

    @Test
    void requirePermissionDeniesAnAdminWhoLacksTheSpecificPermissionToggle() {
        UserAccount admin = adminUser();
        when(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .thenReturn(false);

        assertThatThrownBy(() -> permissionService.requirePermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void requirePermissionAllowsAnAdminWhoHoldsTheSpecificPermissionToggle() {
        UserAccount admin = adminUser();
        when(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .thenReturn(true);

        assertThatCode(() -> permissionService.requirePermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .doesNotThrowAnyException();
    }

    @Test
    void requirePermissionDeniesANonAdminEvenIfAStrayPermissionRowExists() {
        UserAccount musician =
                new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now());
        when(adminPermissionRepository.existsByAdminAndPermission(musician, Permission.MANAGE_SHEET_MUSIC))
                .thenReturn(true);

        assertThatThrownBy(() -> permissionService.requirePermission(musician, Permission.MANAGE_SHEET_MUSIC))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void grantAddsTheToggleOnlyOnceWhenCalledTwiceForTheSamePair() {
        UserAccount admin = adminUser();
        when(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_EVENTS))
                .thenReturn(false, true);

        assertThat(permissionService.grant(admin, Permission.MANAGE_EVENTS)).isTrue();
        assertThat(permissionService.grant(admin, Permission.MANAGE_EVENTS)).isFalse();

        // saveAndFlush, not save: this forces the unique-constraint check to happen
        // synchronously inside grant() so a lost race is catchable there, matching the
        // established AuthService#activate/#resetPassword pattern.
        verify(adminPermissionRepository, times(1)).saveAndFlush(any(AdminPermission.class));
    }

    @Test
    void grantRejectsANonAdminAccount() {
        UserAccount musician =
                new UserAccount("musician2@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, Instant.now());

        assertThatThrownBy(() -> permissionService.grant(musician, Permission.MANAGE_USERS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantIsAnIdempotentNoOpWhenItLosesTheUniqueConstraintRaceAgainstAConcurrentGrant() {
        UserAccount admin = adminUser();
        // Simulates the TOCTOU race: the exists-check reads false (the concurrent winner
        // hasn't committed yet), but by the time this call's own insert flushes, the
        // winner already has — the DB's UNIQUE(admin_id, permission) constraint is the
        // real source of truth and rejects the loser's insert.
        when(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_SHEET_MUSIC))
                .thenReturn(false);
        when(adminPermissionRepository.saveAndFlush(any(AdminPermission.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThat(permissionService.grant(admin, Permission.MANAGE_SHEET_MUSIC)).isFalse();
    }

    @Test
    void requirePermissionDeniesADeactivatedAdminEvenWithTheRoleAndTheToggle() {
        UserAccount deactivatedAdmin =
                new UserAccount("deactivated-admin@example.com", UserRole.ADMIN, UserStatus.DEACTIVATED, Instant.now());
        when(adminPermissionRepository.existsByAdminAndPermission(deactivatedAdmin, Permission.MANAGE_USERS))
                .thenReturn(true);

        assertThatThrownBy(() -> permissionService.requirePermission(deactivatedAdmin, Permission.MANAGE_USERS))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void requirePermissionFailsClosedAndLogsWhenTheRepositoryThrowsADataAccessException() {
        UserAccount admin = adminUser();
        when(adminPermissionRepository.existsByAdminAndPermission(admin, Permission.MANAGE_EVENTS))
                .thenThrow(new QueryTimeoutException("db down"));

        Logger logger = (Logger) LoggerFactory.getLogger(PermissionService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> permissionService.requirePermission(admin, Permission.MANAGE_EVENTS))
                    .isInstanceOf(PermissionDeniedException.class);

            boolean logged = appender.list.stream()
                    .anyMatch(event -> (event.getLevel() == Level.WARN || event.getLevel() == Level.ERROR)
                            && event.getFormattedMessage().contains("MANAGE_EVENTS"));
            assertThat(logged).isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void requirePermissionRejectsANullActor() {
        assertThatThrownBy(() -> permissionService.requirePermission(null, Permission.MANAGE_USERS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requirePermissionRejectsANullPermission() {
        assertThatThrownBy(() -> permissionService.requirePermission(adminUser(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantRejectsANullAdmin() {
        assertThatThrownBy(() -> permissionService.grant(null, Permission.MANAGE_USERS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantRejectsANullPermission() {
        assertThatThrownBy(() -> permissionService.grant(adminUser(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeRejectsANullAdmin() {
        assertThatThrownBy(() -> permissionService.revoke(null, Permission.MANAGE_USERS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeRejectsANullPermission() {
        assertThatThrownBy(() -> permissionService.revoke(adminUser(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeReportsWhetherTheRepositoryDeletedAGrant() {
        UserAccount admin = adminUser();
        when(adminPermissionRepository.deleteByAdminAndPermission(admin, Permission.MANAGE_GROUPS))
                .thenReturn(1, 0);

        assertThat(permissionService.revoke(admin, Permission.MANAGE_GROUPS)).isTrue();
        assertThat(permissionService.revoke(admin, Permission.MANAGE_GROUPS)).isFalse();

        verify(adminPermissionRepository, times(2))
                .deleteByAdminAndPermission(admin, Permission.MANAGE_GROUPS);
    }

    private UserAccount adminUser() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, Instant.now());
    }

    /**
     * Minimal fake transaction manager (no real resource/connection) used purely to drive
     * {@link org.springframework.transaction.support.TransactionTemplate}'s real
     * begin/commit/rollback control flow inside {@link PermissionService#grant}, without
     * needing a database — mirrors {@code AuditServiceTest}'s equivalent fake.
     */
    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // no-op: no real resource to begin
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op: no real resource to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // no-op: no real resource to roll back
        }
    }
}
