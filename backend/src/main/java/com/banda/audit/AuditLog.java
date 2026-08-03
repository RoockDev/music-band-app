package com.banda.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single immutable audit record: who (actorId) did what (action) to which resource
 * (entityType/entityId) and when. Written by explicit {@link AuditService#record} calls
 * in the service layer, per design decision #10 — no AOP magic, one greppable call per
 * mutation.
 *
 * <p>Deliberately has no setters: Section 11 requires history to be shown "chronologically,
 * unaltered" — an audit trail that can be edited after the fact is not an audit trail.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false)
    private Instant timestamp;

    /** Optional free-text/JSON metadata about the mutation. May be null. */
    @Column
    private String details;

    protected AuditLog() {
        // JPA
    }

    public AuditLog(Long actorId, String action, String entityType, Long entityId, String details, Instant timestamp) {
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getDetails() {
        return details;
    }
}
