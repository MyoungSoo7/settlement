package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.common.config.observability.MdcKeys;
import github.lms.lemuel.common.config.observability.MdcScope;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerSettlementCyclePort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.SellerTier;
import github.lms.lemuel.settlement.domain.Settlement;
import github.lms.lemuel.settlement.domain.SettlementCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 결제 완료 시 정산 자동 생성 서비스
 */
@Service
@Transactional
public class CreateSettlementFromPaymentService implements CreateSettlementFromPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateSettlementFromPaymentService.class);

    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadSellerTierPort loadSellerTierPort;
    private final LoadSellerSettlementCyclePort loadSellerSettlementCyclePort;

    public CreateSettlementFromPaymentService(LoadSettlementPort loadSettlementPort,
                                              SaveSettlementPort saveSettlementPort,
                                              LoadSellerTierPort loadSellerTierPort,
                                              LoadSellerSettlementCyclePort loadSellerSettlementCyclePort) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.loadSellerTierPort = loadSellerTierPort;
        this.loadSellerSettlementCyclePort = loadSellerSettlementCyclePort;
    }

    @Override
    public Settlement createSettlementFromPayment(Long paymentId, Long orderId, BigDecimal amount) {
        try (var ignorePayment = MdcScope.of(MdcKeys.PAYMENT_ID, String.valueOf(paymentId));
             var ignoreOrder = MdcScope.of(MdcKeys.ORDER_ID, String.valueOf(orderId))) {
            log.info("Creating settlement from payment. amount={}", amount);

            // Idempotency: 이미 정산이 존재하는지 확인
            Optional<Settlement> existingSettlement = loadSettlementPort.findByPaymentId(paymentId);
            if (existingSettlement.isPresent()) {
                log.info("Settlement already exists. settlementId={} — returning existing.",
                        existingSettlement.get().getId());
                return existingSettlement.get();
            }

            // 판매자 등급별 수수료율 — 매핑 없으면 NORMAL fallback
            SellerTier tier = loadSellerTierPort.findTierByPaymentId(paymentId)
                    .orElse(SellerTier.NORMAL);
            SettlementCycle cycle = loadSellerSettlementCyclePort.findCycleByPaymentId(paymentId)
                    .orElse(SettlementCycle.DAILY);
            log.info("Applying seller tier={}, rate={}, cycle={}", tier, tier.rate(), cycle);

            // 정산 생성 — 주기별 resolveSettlementDate 규칙으로 정산일 계산
            LocalDate settlementDate = cycle.resolveSettlementDate(LocalDate.now());
            Settlement settlement = Settlement.createFromPayment(
                    paymentId, orderId, amount, settlementDate, tier.rate());

            // 저장
            Settlement savedSettlement = saveSettlementPort.save(settlement);
            try (var ignoreSettlement = MdcScope.of(MdcKeys.SETTLEMENT_ID,
                    String.valueOf(savedSettlement.getId()))) {
                log.info("Settlement created successfully. status={}", savedSettlement.getStatus());
            }

            return savedSettlement;
        }
    }
}
