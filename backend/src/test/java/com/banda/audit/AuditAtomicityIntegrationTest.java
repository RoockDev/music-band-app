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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that business state and audit evidence commit or roll back atomically against real
 * Postgres transactions.
 */
class AuditAtomicityIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private BusinessMutationWithAuditFailure businessMutationWithAuditFailure;

    @Test
    void auditFailureRollsBackTheBusinessMutation() {
        UserAccount user = new UserAccount("audit-isolation@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE,
                Instant.now());
        userAccountRepository.saveAndFlush(user);

        assertThatThrownBy(() ->
                businessMutationWithAuditFailure.bumpUserTokenVersionAndRecordFailingAudit(user.getId()))
                .isInstanceOf(RuntimeException.class);

        UserAccount reloaded = userAccountRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isZero();

        assertThat(auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(
                "UserAccount", user.getId())).isEmpty();
    }

    @Test
    void callerRollbackAlsoRemovesTheAuditRecord() {
        UserAccount user = new UserAccount("audit-caller-rollback@example.com", UserRole.MUSICIAN,
                UserStatus.ACTIVE, Instant.now());
        userAccountRepository.saveAndFlush(user);

        assertThatThrownBy(() -> businessMutationWithAuditFailure.bumpRecordAndFail(user.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("business failure");

        UserAccount reloaded = userAccountRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTokenVersion()).isZero();
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

    @Transactional
    public void bumpRecordAndFail(Long userId) {
        UserAccount user = userAccountRepository.findById(userId).orElseThrow();
        user.bumpTokenVersion();
        userAccountRepository.saveAndFlush(user);
        auditService.record(userId, "USER_TOKEN_BUMPED", "UserAccount", userId);
        throw new IllegalStateException("business failure");
    }
}
