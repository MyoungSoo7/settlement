package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ConsumeHoldbackForRecoveryUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.Settlement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 지급후 회수분의 홀드백 소진 — recovery 가 하던 애그리거트 조작을 소유 슬라이스로 되가져온 서비스.
 *
 * <p>이전 구조에서는 {@code recovery.RecoverPostPayoutAdjustmentService} 가 settlement 의 출력 포트
 * 4종(Load/Save/PublishDomainEvent/LoadSellerId)을 직접 주입받아 {@link Settlement} 를 로드·변경·저장하고
 * 정산 도메인 이벤트까지 발행했다. 홀드백 소진은 정산의 규칙인데 그 실행이 정산 밖에 있었다.
 *
 * <p>트랜잭션은 {@code REQUIRED} — 호출자(회수 채권 발생)의 트랜잭션에 합류한다.
 * 흡수와 채권 발생이 갈라지면 홀드백만 깎이고 채권이 없는 상태가 생길 수 있다.
 */
@Slf4j
@Service
public class ConsumeHoldbackForRecoveryService implements ConsumeHoldbackForRecoveryUseCase {

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSellerIdPort loadSellerIdPort;
    private final PublishSettlementDomainEventPort publishSettlementDomainEventPort;

    public ConsumeHoldbackForRecoveryService(LoadSettlementPort loadSettlementPort,
                                             SaveSettlementPort saveSettlementPort,
                                             LoadSellerIdPort loadSellerIdPort,
                                             PublishSettlementDomainEventPort publishSettlementDomainEventPort) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
    }

    @Override
    @Transactional
    public Optional<HoldbackConsumption> consumeForRecovery(Long settlementId, Long adjustmentId,
                                                            BigDecimal recoveredAmount) {
        Optional<Settlement> loaded = loadSettlementPort.findById(settlementId);
        if (loaded.isEmpty()) {
            log.warn("[Recovery] 정산 미발견 — 홀드백 소진 생략. settlementId={}, adjustmentId={}",
                    settlementId, adjustmentId);
            return Optional.empty();
        }
        Settlement settlement = loaded.get();

        Optional<Long> sellerId = loadSellerIdPort.findSellerIdByPaymentId(settlement.getPaymentId());
        if (sellerId.isEmpty()) {
            log.warn("[Recovery] 셀러 미해석 — 홀드백 소진 생략(조정 레코드로 수기 대응). "
                    + "settlementId={}, adjustmentId={}", settlementId, adjustmentId);
            return Optional.empty();
        }

        BigDecimal consumed = settlement.consumeHoldbackForRefund(recoveredAmount);
        if (consumed.signum() > 0) {
            saveSettlementPort.save(settlement);
            // account 로 유보 소진(현금유출) 이벤트 발행 — 회수 조정이 홀드백을 실제로 깎은 만큼.
            publishSettlementDomainEventPort.publishHoldbackConsumed(
                    adjustmentId, settlementId, sellerId.get(), consumed);
        }
        return Optional.of(new HoldbackConsumption(sellerId.get(), consumed));
    }
}
