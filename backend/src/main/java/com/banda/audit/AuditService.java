package com.banda.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><b>Atomicity:</b> audit writes join the caller's transaction. A mutation and the record
 * describing it therefore commit or roll back together: the application never reports a
 * successful security-sensitive mutation without its audit row, and never retains an audit
 * row for business state that ultimately rolled back. Audit persistence failures propagate
 * deliberately so the enclosing mutation cannot commit without evidence.
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

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /** Convenience overload for mutations with no extra context to record. */
    @Transactional
    public void record(Long actorId, String action, String entityType, Long entityId) {
        record(actorId, action, entityType, entityId, null);
    }

    /** Writes one audit record atomically with the caller's mutation. */
    @Transactional
    public void record(Long actorId, String action, String entityType, Long entityId, String details) {
        auditLogRepository.saveAndFlush(
                new AuditLog(actorId, action, entityType, entityId, details, clock.instant()));
    }

    /** Full history for one resource, newest first (Section 11: "shown chronologically"). */
    @Transactional(readOnly = true)
    public List<AuditLog> history(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(entityType, entityId);
    }
}
