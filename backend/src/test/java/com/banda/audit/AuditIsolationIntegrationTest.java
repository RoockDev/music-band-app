package com.banda.audit;

import com.banda.support.IntegrationTestBase;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves the BLOCKER fix end-to-end against a real Postgres transaction: a caller's own
 * business-mutation transaction must commit successfully even when the audit write nested
 * inside it fails, because {@link AuditService#record} isolates itself in its own
 * {@code REQUIRES_NEW} transaction and never rethrows. {@link AuditServiceTest} already
 * proves the propagation behavior and catch/log behavior at the unit level; this class
 * proves it holds with real transactions, real rollback, and a real business mutation —
 * exactly the shape PRs 5-8 will use.
 */
class AuditIsolationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private BusinessMutationWithAuditFailure businessMutationWithAuditFailure;

    @Test
    void businessMutationCommitsEvenWhenTheAuditWriteInsideItsTransactionFails() {
        UserAccount user = new UserAccount("audit-isolation@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE,
                Instant.now());
        userAccountRepository.saveAndFlush(user);

        assertThatCode(() -> businessMutationWithAuditFailure.bumpUserTokenVersionAndRecordFailingAudit(user.getId()))
                .doesNotThrowAnyException();

        // The business mutation committed despite the nested audit write failing...
        UserAccount reloaded = userAccountRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isEqualTo(1L);

        // ...and the failed audit write left no row behind (its own sub-transaction rolled back).
        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "UserAccount", user.getId())).isEmpty();
    }
}

/**
 * Minimal stand-in for a future PR 5-8 business service: performs a real mutation and calls
 * {@link AuditService#record} from inside the same transaction, exactly as PRs 5-8 will.
 */
@Component
class BusinessMutationWithAuditFailure {

    private final UserAccountRepository userAccountRepository;
    private final AuditService auditService;

    BusinessMutationWithAuditFailure(UserAccountRepository userAccountRepository, AuditService auditService) {
        this.userAccountRepository = userAccountRepository;
        this.auditService = auditService;
    }

    @Transactional
    public void bumpUserTokenVersionAndRecordFailingAudit(Long userId) {
        UserAccount user = userAccountRepository.findById(userId).orElseThrow();
        user.bumpTokenVersion();
        userAccountRepository.saveAndFlush(user);

        // entityId=null forces AuditLogRepository.save(...) to throw a real (unmocked)
        // DataIntegrityViolationException at INSERT time: audit_log.entity_id is NOT NULL,
        // and IDENTITY-strategy inserts execute immediately rather than being batched/deferred.
        auditService.record(userId, "USER_TOKEN_BUMPED", "UserAccount", null);
    }
}
