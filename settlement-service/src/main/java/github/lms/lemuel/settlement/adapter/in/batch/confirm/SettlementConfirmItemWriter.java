package github.lms.lemuel.settlement.adapter.in.batch.confirm;

import github.lms.lemuel.ledger.application.port.in.EnqueueLedgerTaskPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 정산 확정 청크 배치의 라이터 — 청크 단위로 확정 정산을 저장하고 후속 이벤트를 발행한다.
 *
 * <p>저장 · loan SettlementConfirmed 발행 · 원장 분개 아웃박스 적재 · ES 인덱싱 이벤트가 모두
 * 청크 트랜잭션과 같은 커밋에 묶인다(아웃박스 패턴 → 크래시 일관성). 하루치 전체를 단일 트랜잭션으로
 * 처리하던 기존 구조와 달리, 청크마다 커밋해 롱 트랜잭션·락 보유 시간을 제한한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementConfirmItemWriter implements ItemWriter<Settlement> {

    private final SaveSettlementPort saveSettlementPort;
    private final LoadSellerIdPort loadSellerIdPort;
    private final PublishSettlementDomainEventPort publishSettlementDomainEventPort;
    private final EnqueueLedgerTaskPort enqueueLedgerTaskPort;
    private final PublishSettlementEventPort publishSettlementEventPort;

    @Override
    public void write(Chunk<? extends Settlement> chunk) {
        List<Long> confirmedIds = new ArrayList<>(chunk.size());

        for (Settlement settlement : chunk) {
            Settlement saved = saveSettlementPort.save(settlement);
            confirmedIds.add(saved.getId());

            // loan-service 로 SettlementConfirmed 발행(상환 차감 트리거). 판매자 미해석은 발행 생략.
            // 같은 청크 트랜잭션의 Outbox 에 적재 → lemuel.settlement.confirmed.
            loadSellerIdPort.findSellerIdByPaymentId(saved.getPaymentId()).ifPresent(sellerId ->
                    publishSettlementDomainEventPort.publishSettlementConfirmed(
                            saved.getId(), sellerId, saved.getNetAmount()));
        }

        if (!confirmedIds.isEmpty()) {
            // 원장 분개 작업을 같은 트랜잭션에 아웃박스로 적재(크래시 내성) + ES 인덱싱 이벤트
            enqueueLedgerTaskPort.enqueueCreate(confirmedIds);
            publishSettlementEventPort.publishSettlementConfirmedEvent(confirmedIds);
            log.info("정산 확정 청크 처리: confirmed={}", confirmedIds.size());
        }
    }
}
