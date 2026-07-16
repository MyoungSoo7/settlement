package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldbackReleaseSchedulerTest {

    @Mock ReleaseHoldbackUseCase useCase;
    @Mock AuditLogger auditLogger;

    @Test
    @DisplayName("releaseDue — KST 오늘 날짜로 releaseAllDueOn 을 호출한다")
    void releaseDue_invokesUseCaseWithKstToday() {
        // 2026-07-15T15:30Z = KST 2026-07-16 00:30 — KST 로 넘어간 자정 직후.
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T15:30:00Z"), ZoneId.of("Asia/Seoul"));
        HoldbackReleaseScheduler scheduler = new HoldbackReleaseScheduler(useCase, clock, auditLogger);
        LocalDate kstToday = LocalDate.of(2026, 7, 16);
        when(useCase.releaseAllDueOn(kstToday)).thenReturn(3);

        scheduler.releaseDue();

        verify(useCase).releaseAllDueOn(kstToday);
        // 홀드백 해제 잡 요약 감사 — actor=system, 해제 건수 포함.
        verify(auditLogger).record(eq(AuditAction.HOLDBACK_RELEASED), eq("HoldbackReleaseJob"),
                eq("2026-07-16"), contains("\"released\":3"));
    }

    @Test
    @DisplayName("KST 자정 직후 실행 — UTC 였다면 전일이 되어 release_date==오늘 홀드백이 하루 늦게 풀리는 off-by-one 을 막는다")
    void releaseDue_kstMidnightBoundary_noOffByOne() {
        // 이 순간 UTC 날짜는 07-15, KST 날짜는 07-16. 본문이 now(clock)=KST 를 써야 07-16 이 나온다.
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        HoldbackReleaseScheduler scheduler = new HoldbackReleaseScheduler(useCase, clock, auditLogger);
        when(useCase.releaseAllDueOn(LocalDate.of(2026, 7, 16))).thenReturn(0);

        scheduler.releaseDue();

        // 07-15(UTC 날짜)가 아니라 07-16(KST 날짜)로 해제해야 한다.
        verify(useCase).releaseAllDueOn(LocalDate.of(2026, 7, 16));
    }
}
