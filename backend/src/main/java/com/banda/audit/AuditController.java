package com.banda.audit;

import com.banda.audit.dto.AuditLogResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin panel read path for Section 11 (Audit Trail). Write access is not exposed here —
 * audit records are only ever created internally via {@link AuditService#record}. Access
 * is restricted to ADMIN accounts by {@code SecurityConfig} (path-based gate); a
 * finer-grained per-action permission gate lands in Phase 3 (RBAC).
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/{entityType}/{entityId}")
    public List<AuditLogResponse> history(@PathVariable String entityType, @PathVariable Long entityId) {
        return auditService.history(entityType, entityId).stream()
                .map(AuditLogResponse::from)
                .toList();
    }
}
