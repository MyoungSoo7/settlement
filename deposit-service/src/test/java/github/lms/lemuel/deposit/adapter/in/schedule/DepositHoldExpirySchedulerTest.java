package github.lms.lemuel.deposit.adapter.in.schedule;

import github.lms.lemuel.deposit.application.port.in.ExpireDueHoldsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 만료 회수 스케줄러 계약.
 *
 * <p>이 클래스에서 틀리면 조용히 틀린다 — 배치가 예외로 죽어도, cutoff 를 엉뚱하게 잡아도
 * 에러 응답을 받는 사람이 없다. 그래서 <b>fail-open 여부와 지표 증가</b>를 못 박는다.
 */
class DepositHoldExpirySchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED = Instant.parse("2026-08-13T04:05:00Z");

    private ExpireDueHoldsUseCase useCase;
    private MeterRegistry registry;
    private DepositHoldExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        useCase = mock(ExpireDueHoldsUseCase.class);
        registry = new SimpleMeterRegistry();
        scheduler = new DepositHoldExpiryScheduler(useCase, Clock.fixed(FIXED, KST), registry);
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    @DisplayName("주입된 Clock 의 '지금'을 cutoff 로 넘긴다 — 정적 now() 면 만료 경계를 고정할 수 없다")
    void passesClockNowAsCutoff() {
        when(useCase.expireDueHolds(any())).thenReturn(0);

        scheduler.reclaimExpiredHolds();

        verify(useCase).expireDueHolds(LocalDateTime.ofInstant(FIXED, KST));
    }

    @Test
    @DisplayName("회수 건수를 지표로 누적한다")
    void countsReclaimed() {
        when(useCase.expireDueHolds(any())).thenReturn(3);

        scheduler.reclaimExpiredHolds();
        scheduler.reclaimExpiredHolds();

        assertThat(counter("deposit.hold.expiry.reclaimed")).isEqualTo(6.0);
        assertThat(counter("deposit.hold.expiry.failed_runs")).isZero();
    }

    @Test
    @DisplayName("배치가 터져도 예외를 밖으로 내보내지 않는다 — 스케줄러가 죽으면 복구 경로까지 사라진다")
    void failsOpen() {
        when(useCase.expireDueHolds(any())).thenThrow(new IllegalStateException("DB 연결 끊김"));

        assertThatCode(scheduler::reclaimExpiredHolds).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실패 회차를 따로 센다 — 회수 0건은 '대상 없음'과 '배치 실패'가 같은 값이라 구분되지 않는다")
    void countsFailedRunsSeparately() {
        when(useCase.expireDueHolds(any())).thenThrow(new IllegalStateException("boom"));

        scheduler.reclaimExpiredHolds();

        assertThat(counter("deposit.hold.expiry.failed_runs")).isEqualTo(1.0);
        assertThat(counter("deposit.hold.expiry.reclaimed")).isZero();
    }
}
