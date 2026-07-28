package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.recovery.application.port.in.EscalateStaleRecoveryUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryEscalationSchedulerTest {

    @Mock EscalateStaleRecoveryUseCase useCase;
    @Mock AuditLogger auditLogger;

    @Test
    @DisplayName("escalateStale — KST 현재 시각으로 escalateStaleOpenRecoveries 를 호출한다")
    void escalateStale_invokesUseCaseWithKstNow() {
        // 2026-08-20T18:30Z = KST 2026-08-21 03:30
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T18:30:00Z"), ZoneId.of("Asia/Seoul"));
        RecoveryEscalationScheduler scheduler = new RecoveryEscalationScheduler(useCase, clock, auditLogger);
        OffsetDateTime kstNow = OffsetDateTime.now(clock);
        when(useCase.escalateStaleOpenRecoveries(kstNow)).thenReturn(2);

        scheduler.escalateStale();

        verify(useCase).escalateStaleOpenRecoveries(kstNow);
        verify(auditLogger).record(eq(AuditAction.SELLER_RECOVERY_ESCALATED), eq("RecoveryEscalationJob"),
                eq(kstNow.toString()), contains("\"escalated\":2"));
    }

    @Test
    @DisplayName("이관 건수 0 이어도 잡 요약 감사는 남긴다")
    void escalateStale_recordsAuditEvenWhenZero() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T18:30:00Z"), ZoneId.of("Asia/Seoul"));
        RecoveryEscalationScheduler scheduler = new RecoveryEscalationScheduler(useCase, clock, auditLogger);
        when(useCase.escalateStaleOpenRecoveries(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        scheduler.escalateStale();

        verify(auditLogger).record(eq(AuditAction.SELLER_RECOVERY_ESCALATED), eq("RecoveryEscalationJob"),
                org.mockito.ArgumentMatchers.any(), contains("\"escalated\":0"));
    }
}
