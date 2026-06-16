package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.common.config.observability.MdcKeys;
import github.lms.lemuel.common.config.observability.MdcScope;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerSettlementCyclePort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import github.lms.lemuel.settlement.application.port.out.PublishSettlementDomainEventPort;
import github.lms.lemuel.settlement.application.port.out.SaveSettlementPort;
import github.lms.lemuel.settlement.domain.HoldbackPolicy;
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
    private final LoadSellerIdPort loadSellerIdPort;
    private final PublishSettlementDomainEventPort publishSettlementDomainEventPort;

    public CreateSettlementFromPaymentService(LoadSettlementPort loadSettlementPort,
                                              SaveSettlementPort saveSettlementPort,
                                              LoadSellerTierPort loadSellerTierPort,
                                              LoadSellerSettlementCyclePort loadSellerSettlementCyclePort,
                                              LoadSellerIdPort loadSellerIdPort,
                                              PublishSettlementDomainEventPort publishSettlementDomainEventPort) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.loadSellerTierPort = loadSellerTierPort;
        this.loadSellerSettlementCyclePort = loadSellerSettlementCyclePort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
    }

    @Override
    public Settlement createSettlementFromPayment(Long paymentId, Long orderId, BigDecimal amount) {
        return createSettlementFromPayment(paymentId, orderId, amount, null, null, null);
    }

    @Override
    public Settlement createSettlementFromPayment(Long paymentId, Long orderId, BigDecimal amount,
                                                  Long sellerId, String sellerTier, String settlementCycle) {
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

            // 판매자 등급별 수수료율 — 이벤트 동봉값 우선(ADR 0020 Phase 1), 없으면 order DB 조인 fallback, 그래도 없으면 NORMAL
            SellerTier tier = sellerTier != null
                    ? SellerTier.fromStringOrDefault(sellerTier)
                    : loadSellerTierPort.findTierByPaymentId(paymentId).orElse(SellerTier.NORMAL);
            // 정산 주기 — 이벤트 동봉값 우선, 없으면 조인 fallback, 그래도 없으면 등급별 default
            // (NORMAL=T+7, VIP=T+3, STRATEGIC=T+1)
            SettlementCycle cycle = settlementCycle != null
                    ? SettlementCycle.fromStringOrDefault(settlementCycle)
                    : loadSellerSettlementCyclePort.findCycleByPaymentId(paymentId).orElse(tier.defaultCycle());
            log.info("Applying seller tier={}, rate={}, cycle={}", tier, tier.rate(), cycle);

            // 정산 생성 — 주기별 resolveSettlementDate 규칙으로 정산일 계산
            LocalDate settlementDate = cycle.resolveSettlementDate(LocalDate.now());
            Settlement settlement = Settlement.createFromPayment(
                    paymentId, orderId, amount, settlementDate, tier.rate());

            // 셀러 등급별 보류 정책 적용 (NORMAL=30%/30일, VIP=10%/14일, STRATEGIC=0%)
            HoldbackPolicy holdback = HoldbackPolicy.forTier(tier);
            settlement.applyHoldback(holdback.rate(), holdback.computeReleaseDate(settlementDate));
            log.info("Applying holdback: rate={}, amount={}, releaseDate={}",
                    holdback.rate(), settlement.getHoldbackAmount(), settlement.getHoldbackReleaseDate());

            // 저장
            Settlement savedSettlement = saveSettlementPort.save(settlement);
            try (var ignoreSettlement = MdcScope.of(MdcKeys.SETTLEMENT_ID,
                    String.valueOf(savedSettlement.getId()))) {
                log.info("Settlement created successfully. status={}", savedSettlement.getStatus());
            }

            // loan-service 로 SettlementCreated 발행 (선정산 대출 담보 = 미지급 정산예정금).
            // 같은 트랜잭션의 Outbox 에 적재 → 폴러가 lemuel.settlement.created 로 발행.
            // 판매자 미할당 정산은 대출 대상이 아니므로 발행 생략.
            // 이벤트 동봉 sellerId 우선, 없으면 order DB 조인 fallback
            Long resolvedSellerId = sellerId != null
                    ? sellerId
                    : loadSellerIdPort.findSellerIdByPaymentId(paymentId).orElse(null);
            if (resolvedSellerId != null) {
                publishSettlementDomainEventPort.publishSettlementCreated(
                        savedSettlement.getId(), resolvedSellerId,
                        savedSettlement.getNetAmount(), savedSettlement.getSettlementDate());
            } else {
                log.debug("판매자 미해석 — SettlementCreated 발행 생략. settlementId={}",
                        savedSettlement.getId());
            }

            return savedSettlement;
        }
    }
}
