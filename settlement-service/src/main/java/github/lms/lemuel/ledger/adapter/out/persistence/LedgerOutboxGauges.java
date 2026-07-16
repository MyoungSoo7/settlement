package github.lms.lemuel.ledger.adapter.out.persistence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 원장 아웃박스 상태 게이지 — {@code ledger_outbox} 의 FAILED/PENDING 행 수를 노출한다.
 *
 * <p>FAILED 는 재시도 한도(기본 10회)를 넘겨 원장 분개가 <b>죽은</b> 작업이라 0 이 아니면 운영자 개입이
 * 필요하다(수동/일괄 재큐). PENDING 은 폴러 적체 신호다. 게이지는 스크레이프 시점마다 가벼운 COUNT
 * 쿼리를 돌린다(폴러 경로와 무관 — 처리에 영향 없음).
 *
 * <p>노출: {@code settlement_ledger_outbox{status=failed|pending}}.
 */
@Component
public class LedgerOutboxGauges {

    public LedgerOutboxGauges(MeterRegistry registry, SpringDataLedgerOutboxRepository repository) {
        Gauge.builder("settlement.ledger.outbox", repository, r -> (double) r.countByStatus("FAILED"))
                .tag("status", "failed")
                .description("ledger_outbox FAILED 행 수 — 원장 작업 사멸 감지(0 이 정상)")
                .register(registry);
        Gauge.builder("settlement.ledger.outbox", repository, r -> (double) r.countByStatus("PENDING"))
                .tag("status", "pending")
                .description("ledger_outbox PENDING 행 수 — 폴러 적체 감지")
                .register(registry);
    }
}
