package com.banda.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Full history for one resource, newest first (Section 11 says "shown chronologically"
     * without specifying direction; product decision: descending, matching the standard
     * admin/activity-log convention — GitHub audit log, AWS CloudTrail, Stripe Dashboard).
     *
     * <p>{@code id} is a required secondary sort key, not cosmetic: Postgres truncates
     * {@code timestamp} (an {@link java.time.Instant}) to microsecond precision, so two
     * writes to the same entity that land in the same microsecond tie on {@code timestamp}
     * alone and would otherwise return in a nondeterministic order.
     */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc(String entityType, Long entityId);
}
