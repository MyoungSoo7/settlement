package github.lms.lemuel.settlement.adapter.in.monitoring;

import github.lms.lemuel.settlement.application.dto.SettlementBatchHealthSnapshot;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementBatchHealthPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementBatchHealthIndicator — 배치 헬스 상태 매핑")
class SettlementBatchHealthIndicatorTest {

    @Mock LoadSettlementBatchHealthPort loadPort;
    SettlementBatchHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new SettlementBatchHealthIndicator(loadPort);
    }

    private SettlementBatchHealthSnapshot snapshot(long pending, long confirmed, long adjPending) {
        return new SettlementBatchHealthSnapshot(LocalDate.of(2026, 3, 9), pending, confirmed, adjPending);
    }

    @Test
    @DisplayName("정상 건수 → UP + details 노출")
    void up() {
        when(loadPort.loadHealthSnapshot(any(LocalDate.class))).thenReturn(snapshot(10, 100, 5));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("settlement_pending_count", 10L);
    }

    @Test
    @DisplayName("대기 정산 과다(>100) → DOWN + reason")
    void down_tooManyPendingSettlements() {
        when(loadPort.loadHealthSnapshot(any(LocalDate.class))).thenReturn(snapshot(101, 0, 0));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "Too many pending settlements");
    }

    @Test
    @DisplayName("대기 조정 과다(>50) → WARNING")
    void warning_tooManyPendingAdjustments() {
        when(loadPort.loadHealthSnapshot(any(LocalDate.class))).thenReturn(snapshot(10, 0, 51));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(new Status("WARNING"));
        assertThat(health.getDetails()).containsEntry("reason", "Too many pending adjustments");
    }

    @Test
    @DisplayName("조회 예외 → DOWN (헬스체크가 예외를 전파하지 않음)")
    void down_onException() {
        when(loadPort.loadHealthSnapshot(any(LocalDate.class)))
                .thenThrow(new RuntimeException("DB 연결 끊김"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
