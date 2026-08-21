package com.banda.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests for the write path (record) and the read path delegation
 * (history). {@link AuditLogRepositoryTest} already proves the real JPA-level ordering,
 * and {@link AuditAtomicityIntegrationTest} proves atomic rollback against a real Postgres
 * transaction end-to-end; this class proves AuditService wires the Clock, repository, and
 * required transaction boundary correctly.
 */
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private AuditLogRepository auditLogRepository;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        auditService = new AuditService(auditLogRepository, clock);
    }

    @Test
    void recordWritesAnAuditLogWithTheCurrentClockTime() {
        auditService.record(7L, "USER_CREATED", "UserAccount", 99L, "created by admin");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getActorId()).isEqualTo(7L);
        assertThat(saved.getAction()).isEqualTo("USER_CREATED");
        assertThat(saved.getEntityType()).isEqualTo("UserAccount");
        assertThat(saved.getEntityId()).isEqualTo(99L);
        assertThat(saved.getDetails()).isEqualTo("created by admin");
        assertThat(saved.getTimestamp()).isEqualTo(NOW);
    }

    @Test
    void recordWithoutDetailsStoresNullDetails() {
        auditService.record(7L, "USER_DEACTIVATED", "UserAccount", 99L);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());

        assertThat(captor.getValue().getDetails()).isNull();
    }

    @Test
    void recordDeclaresARequiredTransactionBoundary() throws NoSuchMethodException {
        Transactional transactional = AuditService.class
                .getMethod("record", Long.class, String.class, String.class, Long.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
    }

    @Test
    void recordPropagatesRepositoryFailureSoTheBusinessTransactionCannotCommit() {
        when(auditLogRepository.saveAndFlush(any())).thenThrow(new RuntimeException("db unavailable"));

        assertThatThrownBy(() ->
                auditService.record(7L, "USER_CREATED", "UserAccount", 99L, "created by admin"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db unavailable");
    }

    @Test
    void historyDelegatesToTheNewestFirstRepositoryQuery() {
        AuditLog entry = new AuditLog(1L, "CREATED", "SheetMusic", 5L, null, NOW);
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc("SheetMusic", 5L))
                .thenReturn(List.of(entry));

        List<AuditLog> history = auditService.history("SheetMusic", 5L);

        assertThat(history).containsExactly(entry);
    }

}
