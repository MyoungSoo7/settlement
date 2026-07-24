package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.common.config.observability.MdcKeys;
import github.lms.lemuel.common.config.observability.MdcScope;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
import github.lms.lemuel.settlement.application.port.out.BackfillChargebackSettlementLinkPort;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final BackfillChargebackSettlementLinkPort backfillChargebackPort;
    private final AuditLogger auditLogger;
    /** KST 기준 시각 소스 — 결제 시각 부재 시 정산 기준일 폴백에만 사용(정본은 결제 시각). */
    private final Clock clock;

    public CreateSettlementFromPaymentService(LoadSettlementPort loadSettlementPort,
                                              SaveSettlementPort saveSettlementPort,
                                              LoadSellerTierPort loadSellerTierPort,
                                              LoadSellerSettlementCyclePort loadSellerSettlementCyclePort,
                                              LoadSellerIdPort loadSellerIdPort,
                                              PublishSettlementDomainEventPort publishSettlementDomainEventPort,
                                              BackfillChargebackSettlementLinkPort backfillChargebackPort,
                                              AuditLogger auditLogger,
                                              Clock clock) {
        this.loadSettlementPort = loadSettlementPort;
        this.saveSettlementPort = saveSettlementPort;
        this.loadSellerTierPort = loadSellerTierPort;
        this.loadSellerSettlementCyclePort = loadSellerSettlementCyclePort;
        this.loadSellerIdPort = loadSellerIdPort;
        this.publishSettlementDomainEventPort = publishSettlementDomainEventPort;
        this.backfillChargebackPort = backfillChargebackPort;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @Override
    public Settlement createSettlementFromPayment(Long paymentId, Long orderId, BigDecimal amount) {
        return createSettlementFromPayment(paymentId, orderId, amount, null, null, null, null);
    }

    @Override
    public Settlement createSettlementFromPayment(Long paymentId, Long orderId, BigDecimal amount,
                                                  Long sellerId, String sellerTier, String settlementCycle) {
        return createSettlementFromPayment(paymentId, orderId, amount, sellerId, sellerTier, settlementCycle, null);
    }

    @Override
    public Settlement createSettlementFromPayment(Long paymentId, Long orderId, BigDecimal amount,
                                                  Long sellerId, String sellerTier, String settlementCycle,
                                                  LocalDateTime paymentCapturedAt) {
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

            // 정산일 계산 — 기준은 소비 시각이 아니라 "결제 발생일"이다(SettlementCycle 계약, ADR 0020).
            // 결제 시각의 날짜를 기준으로 resolveSettlementDate 를 태워, 지연/백필/재처리와 무관하게
            // 같은 결제는 항상 같은 정산일을 얻게 한다.
            LocalDate paymentDate = resolvePaymentDate(paymentCapturedAt);
            LocalDate settlementDate = cycle.resolveSettlementDate(paymentDate);
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

            // 이벤트드리븐 정산 생성 감사 — 정산금이 발생하는 지점이라 audit_logs 에 남긴다(멱등 반환 경로는 제외).
            // 컨슈머 경로라 actor 는 system. 값은 전부 id/금액이라 주입 위험이 없어 컴팩트 JSON 을 직접 조립한다.
            recordCreated(savedSettlement, paymentId);

            // 사전분쟁 백필 — 정산보다 먼저 접수된 분쟁을 연결하고, ACCEPTED 건은 환수 조정을 지금 만든다.
            // 같은 트랜잭션이라 정산 생성과 원자적(백필 실패 시 함께 롤백 — 반쪽 회계 상태 방지).
            int linkedChargebacks = backfillChargebackPort.backfillChargebacks(
                    paymentId, savedSettlement.getId());
            if (linkedChargebacks > 0) {
                log.warn("사전분쟁 백필 완료. settlementId={}, linkedChargebacks={}",
                        savedSettlement.getId(), linkedChargebacks);
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
                        savedSettlement.getNetAmount(), savedSettlement.getSettlementDate(),
                        savedSettlement.getHoldbackAmount());
            } else {
                log.debug("판매자 미해석 — SettlementCreated 발행 생략. settlementId={}",
                        savedSettlement.getId());
            }

            return savedSettlement;
        }
    }

    /**
     * 정산 생성을 audit_logs 에 기록. paymentId·settlementId·금액 요약만 담는다(actor 는 system).
     * AuditLogger 자체가 {@code REQUIRES_NEW} + 예외 흡수라 감사 실패가 정산 생성 트랜잭션을 깨지 않는다.
     */
    private void recordCreated(Settlement s, Long paymentId) {
        String detail = String.format(
                "{\"paymentId\":%d,\"settlementId\":%s,\"paymentAmount\":\"%s\",\"netAmount\":\"%s\",\"settlementDate\":\"%s\"}",
                paymentId, s.getId(), s.getPaymentAmount().toPlainString(),
                s.getNetAmount().toPlainString(), s.getSettlementDate());
        auditLogger.record(AuditAction.SETTLEMENT_CREATED, "Settlement",
                String.valueOf(s.getId()), detail);
    }

    /**
     * 정산 기준일(결제 발생일) 해석. 정본은 결제 이벤트가 실어 온 결제 시각의 날짜다.
     *
     * <p>결제 시각이 없으면(구 이벤트 등) KST {@link Clock} 현재일로 폴백한다 — 이 경우에만 소비 시각에
     * 의존하므로 경고 로그를 남긴다. 폴백은 {@code LocalDate.now(clock)} 로 반드시 KST 를 통과시켜
     * UTC JVM 의 자정 off-by-one 을 방지한다.
     */
    private LocalDate resolvePaymentDate(LocalDateTime paymentCapturedAt) {
        if (paymentCapturedAt != null) {
            return paymentCapturedAt.toLocalDate();
        }
        LocalDate fallback = LocalDate.now(clock);
        log.warn("payment.captured 에 capturedAt 이 없어 정산 기준일을 KST now({})로 폴백 — "
                + "컨슈머 지연/백필 시 결제일과 어긋날 수 있음", fallback);
        return fallback;
    }
}
