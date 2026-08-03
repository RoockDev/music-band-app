package com.banda.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Section 11 (Audit Trail) implementation of design decision #10: a single {@code audit_log}
 * table written by explicit {@code record(...)} calls in the service layer — no AOP, no
 * magic. Every mutating action across every feature (users, sheet music, events, ...) is
 * expected to call {@link #record} once it has successfully persisted its change.
 *
 * <p>The API is deliberately generic ({@code actorId}/{@code action}/{@code entityType}/
 * {@code entityId}/{@code details}) so later phases (PRs 5-8) can call it for any entity
 * without this class knowing about their domain types.
 *
 * <p><b>Failure isolation:</b> {@link #record} always runs in its own
 * {@code REQUIRES_NEW} transaction, deliberately isolated from the caller's own transaction,
 * and never rethrows. PRs 5-8 call {@code record(...)} from inside their own transactional
 * business mutations (e.g. saving a SheetMusic edit); a broken audit write must never fail —
 * or roll back — the real business mutation it's describing. Failures are instead logged at
 * ERROR with full context (actorId, action, entityType, entityId) so they're visible in
 * monitoring without becoming the caller's problem. Because Spring AOP proxies don't apply to
 * self-invocation, this is implemented explicitly via {@link TransactionTemplate} rather than
 * a {@code @Transactional(propagation = REQUIRES_NEW)} method on this same class, which would
 * silently run with whatever propagation the caller's transaction already has.
 *
 * <h2>Contracts every caller MUST honor</h2>
 * <ul>
 *   <li><b>{@code actorId}</b> MUST be derived from the authenticated principal (e.g.
 *   {@code SecurityContextHolder}), never from client-supplied request data. This is an
 *   append-only, "immutable" audit trail; forging {@code actorId} would let a user attribute
 *   their own actions to someone else with no way to detect it after the fact.</li>
 *   <li><b>{@code details}</b> MUST NEVER contain secrets, tokens, password hashes, or PII
 *   belonging to another domain. It is stored verbatim and is readable by any ADMIN via
 *   {@link AuditController}.</li>
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public AuditService(AuditLogRepository auditLogRepository, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Convenience overload for mutations with no extra context to record. */
    public void record(Long actorId, String action, String entityType, Long entityId) {
        record(actorId, action, entityType, entityId, null);
    }

    /**
     * Writes one audit record. Never throws: a failure here is logged at ERROR and
     * swallowed so the caller's own transaction is never marked rollback-only because of an
     * audit side-effect. See the class-level Javadoc for the full failure-isolation
     * rationale and the {@code actorId}/{@code details} contracts.
     */
    public void record(Long actorId, String action, String entityType, Long entityId, String details) {
        try {
            requiresNewTransaction.executeWithoutResult(status ->
                    auditLogRepository.save(new AuditLog(actorId, action, entityType, entityId, details, clock.instant())));
        } catch (RuntimeException e) {
            log.error("Audit write failed: actorId={}, action={}, entityType={}, entityId={}",
                    actorId, action, entityType, entityId, e);
        }
    }

    /** Full history for one resource, newest first (Section 11: "shown chronologically"). */
    @Transactional(readOnly = true)
    public List<AuditLog> history(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(entityType, entityId);
    }
}
