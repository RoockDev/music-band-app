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
 */
@Service
@Transactional
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /** Convenience overload for mutations with no extra context to record. */
    public void record(Long actorId, String action, String entityType, Long entityId) {
        record(actorId, action, entityType, entityId, null);
    }

    public void record(Long actorId, String action, String entityType, Long entityId, String details) {
        auditLogRepository.save(new AuditLog(actorId, action, entityType, entityId, details, clock.instant()));
    }

    /** Full history for one resource, oldest first (Section 11: "shown chronologically"). */
    @Transactional(readOnly = true)
    public List<AuditLog> history(String entityType, Long entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampAsc(entityType, entityId);
    }
}
