package com.banda.audit.dto;

import com.banda.audit.AuditLog;

import java.time.Instant;

public record AuditLogResponse(Long id, Long actorId, String action, String entityType, Long entityId,
                                Instant timestamp, String details) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getActorId(), log.getAction(), log.getEntityType(),
                log.getEntityId(), log.getTimestamp(), log.getDetails());
    }
}
