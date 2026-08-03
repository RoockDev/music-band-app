package com.banda.audit;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogRepositoryTest extends IntegrationTestBase {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void persistsAndReloadsAnAuditLog() {
        // Truncated to microseconds: Postgres' timestamp column has microsecond precision,
        // so a full-nanosecond Instant.now() would not round-trip byte-for-byte.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AuditLog log = new AuditLog(42L, "USER_CREATED", "UserAccount", 7L, "created via admin panel", now);

        AuditLog saved = auditLogRepository.saveAndFlush(log);

        Optional<AuditLog> reloaded = auditLogRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getActorId()).isEqualTo(42L);
        assertThat(reloaded.get().getAction()).isEqualTo("USER_CREATED");
        assertThat(reloaded.get().getEntityType()).isEqualTo("UserAccount");
        assertThat(reloaded.get().getEntityId()).isEqualTo(7L);
        assertThat(reloaded.get().getDetails()).isEqualTo("created via admin panel");
        assertThat(reloaded.get().getTimestamp()).isEqualTo(now);
    }

    @Test
    void findsRecordsForAnEntityOrderedNewestFirst() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        AuditLog older = new AuditLog(1L, "CREATED", "SheetMusic", 99L, null, t0);
        AuditLog newer = new AuditLog(1L, "UPDATED", "SheetMusic", 99L, null, t0.plus(Duration.ofMinutes(5)));
        AuditLog otherEntity = new AuditLog(1L, "CREATED", "SheetMusic", 100L, null, t0.plus(Duration.ofMinutes(1)));

        // Persisted out of chronological order on purpose to prove ordering is not an
        // insertion-order accident.
        auditLogRepository.saveAndFlush(newer);
        auditLogRepository.saveAndFlush(otherEntity);
        auditLogRepository.saveAndFlush(older);

        List<AuditLog> history =
                auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc("SheetMusic", 99L);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getTimestamp()).isEqualTo(t0.plus(Duration.ofMinutes(5)));
        assertThat(history.get(1).getTimestamp()).isEqualTo(t0);
    }

    @Test
    void ordersTiedTimestampsDeterministicallyByIdDescendingAcrossRepeatedQueries() {
        // Both records share the exact same Instant (not just equal after truncation) to
        // simulate two near-simultaneous writes that land in the same Postgres microsecond
        // bucket, where ORDER BY timestamp alone gives no deterministic order.
        Instant tied = Instant.parse("2026-01-01T00:00:00Z");
        AuditLog first = new AuditLog(1L, "CREATED", "SheetMusic", 77L, null, tied);
        AuditLog second = new AuditLog(1L, "UPDATED", "SheetMusic", 77L, null, tied);

        AuditLog savedFirst = auditLogRepository.saveAndFlush(first);
        AuditLog savedSecond = auditLogRepository.saveAndFlush(second);

        List<AuditLog> firstQuery =
                auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc("SheetMusic", 77L);
        List<AuditLog> secondQuery =
                auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc("SheetMusic", 77L);

        assertThat(firstQuery).extracting(AuditLog::getId)
                .containsExactly(savedSecond.getId(), savedFirst.getId());
        assertThat(secondQuery).extracting(AuditLog::getId)
                .containsExactly(savedSecond.getId(), savedFirst.getId());
    }
}
