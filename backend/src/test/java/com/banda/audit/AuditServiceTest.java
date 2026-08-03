package com.banda.audit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests for the write path (record) and the read path delegation
 * (history). {@link AuditLogRepositoryTest} already proves the real JPA-level ordering,
 * and {@link AuditIsolationIntegrationTest} proves the REQUIRES_NEW isolation against a
 * real Postgres transaction end-to-end; this class proves AuditService wires the Clock,
 * repository and transaction propagation correctly, and that a repository failure never
 * escapes {@link AuditService#record}.
 */
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private AuditLogRepository auditLogRepository;
    private RecordingTransactionManager transactionManager;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        transactionManager = new RecordingTransactionManager();
        auditService = new AuditService(auditLogRepository, clock, transactionManager);
    }

    @Test
    void recordWritesAnAuditLogWithTheCurrentClockTime() {
        auditService.record(7L, "USER_CREATED", "UserAccount", 99L, "created by admin");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

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
        verify(auditLogRepository).save(captor.capture());

        assertThat(captor.getValue().getDetails()).isNull();
    }

    @Test
    void recordRunsInARequiresNewTransactionIsolatedFromAnyCallerTransaction() {
        auditService.record(7L, "USER_CREATED", "UserAccount", 99L);

        assertThat(transactionManager.lastPropagationBehavior)
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void recordDoesNotPropagateWhenTheRepositorySaveThrowsAndLogsTheFailure() {
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("db unavailable"));

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(AuditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatCode(() -> auditService.record(7L, "USER_CREATED", "UserAccount", 99L, "created by admin"))
                    .doesNotThrowAnyException();

            // The audit write's own sub-transaction rolled back...
            assertThat(transactionManager.lastRolledBack).isTrue();

            // ...but the failure is visible at ERROR with enough context to investigate.
            boolean errorLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains("actorId=7")
                            && event.getFormattedMessage().contains("action=USER_CREATED")
                            && event.getFormattedMessage().contains("entityType=UserAccount")
                            && event.getFormattedMessage().contains("entityId=99"));
            assertThat(errorLogged).isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void historyDelegatesToTheNewestFirstRepositoryQuery() {
        AuditLog entry = new AuditLog(1L, "CREATED", "SheetMusic", 5L, null, NOW);
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByTimestampDescIdDesc("SheetMusic", 5L))
                .thenReturn(List.of(entry));

        List<AuditLog> history = auditService.history("SheetMusic", 5L);

        assertThat(history).containsExactly(entry);
    }

    /**
     * Minimal fake transaction manager (no real resource/connection) used purely to drive
     * {@link org.springframework.transaction.support.TransactionTemplate}'s real control
     * flow — begin/commit/rollback — so these unit tests exercise the actual propagation
     * behavior and rollback-on-exception semantics AuditService relies on, without needing
     * a database.
     */
    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private Integer lastPropagationBehavior;
        private boolean lastRolledBack;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            lastPropagationBehavior = definition.getPropagationBehavior();
            lastRolledBack = false;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // no-op: no real resource to commit
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            lastRolledBack = true;
        }
    }
}
