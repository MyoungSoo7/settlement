package github.lms.lemuel.settlement.adapter.in.monitoring;

import github.lms.lemuel.settlement.application.dto.SettlementBatchHealthSnapshot;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementBatchHealthPort;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Settlement Batch Health Indicator (Inbound Adapter - Monitoring)
 * /actuator/health 엔드포인트에서 배치 작업 상태 확인
 *
 * Clean Architecture:
 * - Actuator HealthIndicator는 외부 모니터링 시스템과의 인터페이스이므로 Inbound Adapter
 * - application 레이어의 port(out)만 의존하여 데이터 조회
 * - 상태 판단 로직만 수행 (비즈니스 로직 없음)
 */
@Component
public class SettlementBatchHealthIndicator implements HealthIndicator {

    private final LoadSettlementBatchHealthPort loadSettlementBatchHealthPort;

    public SettlementBatchHealthIndicator(LoadSettlementBatchHealthPort loadSettlementBatchHealthPort) {
        this.loadSettlementBatchHealthPort = loadSettlementBatchHealthPort;
    }

    @Override
    public Health health() {
        try {
            // 어제 날짜 기준 배치 상태 조회
            LocalDate yesterday = LocalDate.now().minusDays(1);
            SettlementBatchHealthSnapshot snapshot = loadSettlementBatchHealthPort.loadHealthSnapshot(yesterday);

            Map<String, Object> details = new HashMap<>();
            details.put("settlement_date", snapshot.getSettlementDate().toString());
            details.put("settlement_pending_count", snapshot.getSettlementPendingCount());
            details.put("settlement_confirmed_count", snapshot.getSettlementConfirmedCount());
            details.put("adjustment_pending_count", snapshot.getAdjustmentPendingCount());

            // 상태 판단: PENDING이 너무 많으면 경고
            // 새벽 3시 이후에도 PENDING이 많으면 배치 실패 가능성
            if (snapshot.hasTooManyPendingSettlements()) {
                return Health.down()
                        .withDetail("reason", "Too many pending settlements")
                        .withDetails(details)
                        .build();
            }

            if (snapshot.hasTooManyPendingAdjustments()) {
                return Health.status("WARNING")
                        .withDetail("reason", "Too many pending adjustments")
                        .withDetails(details)
                        .build();
            }

            return Health.up().withDetails(details).build();

        } catch (Exception e) {
            return Health.down()
                    .withException(e)
                    .build();
        }
    }
}
